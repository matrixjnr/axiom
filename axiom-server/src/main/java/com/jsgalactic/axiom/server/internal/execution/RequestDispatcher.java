package com.jsgalactic.axiom.server.internal.execution;

import com.jsgalactic.axiom.execution.AdmissionPolicy;
import com.jsgalactic.axiom.execution.AdmissionSnapshot;
import com.jsgalactic.axiom.execution.ExecutionBudget;
import com.jsgalactic.axiom.execution.ExecutionContext;
import com.jsgalactic.axiom.execution.StreamPolicy;
import com.jsgalactic.axiom.observability.Metrics;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.Callable;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.LongSupplier;
import java.util.function.ToIntFunction;

/**
 * Protocol-neutral execution shared by every listener and test client of one application. All
 * admission state is protected by this object's lock; a session scopes one submitter's lifetime
 * without giving it a private copy of the budget.
 */
public final class RequestDispatcher implements AutoCloseable {
  private static final Object DEFAULT_KEY = new Object();
  private static final StreamPolicy DEFAULT_STREAM_POLICY = StreamPolicy.reject(36);
  private final AdmissionPolicy policy;
  private final StreamPolicy streamPolicy;
  private final ExecutorService workers;
  private final ScheduledExecutorService deadlines;
  private final LongSupplier clock;
  private final DispatchMetrics metrics;
  private final Set<Task<?>> tasks = new HashSet<>();
  private final Set<Session> sessions = new HashSet<>();
  private final Set<Bucket> queuedBuckets = new LinkedHashSet<>();
  private final Map<Object, Bucket> buckets = new HashMap<>();
  private final Map<Object, StreamBucket> streamBuckets = new HashMap<>();

  /** The task executing on this thread, so a stream can transfer its own slot. */
  private final ThreadLocal<Task<?>> currentTask = new ThreadLocal<>();

  /**
   * Waiting requests ordered by the moment their queue wait or execution deadline runs out,
   * whichever is first, so expired waits are found in order of expiry whatever their budgets.
   * Insertion and removal are logarithmic; expiring k requests costs k removals.
   */
  private final TreeSet<Task<?>> byExpiry = new TreeSet<>(RequestDispatcher::expiresBefore);

  private final CompletableFuture<Void> stopped = new CompletableFuture<>();
  private int active;
  private int queued;
  private int streamActive;
  private long sequence;
  private long accepted;
  private long rejected;
  private long queueTimeouts;
  private boolean stopping;
  private boolean retiring;
  private boolean closed;

  /**
   * Creates execution with immediate rejection when busy.
   *
   * @param capacity maximum reserved or running executions
   */
  public RequestDispatcher(int capacity) {
    this(AdmissionPolicy.reject(capacity));
  }

  /**
   * Creates bounded admission before virtual-thread creation. Streams use the default bound of 36.
   *
   * @param policy aggregate limits for this dispatcher
   */
  public RequestDispatcher(AdmissionPolicy policy) {
    this(policy, DEFAULT_STREAM_POLICY);
  }

  /**
   * Creates bounded admission for requests and streams before virtual-thread creation.
   *
   * @param policy aggregate limits for this dispatcher
   * @param streamPolicy aggregate limit on active streams
   */
  public RequestDispatcher(AdmissionPolicy policy, StreamPolicy streamPolicy) {
    this(policy, streamPolicy, Metrics.NOOP);
  }

  /**
   * Creates bounded admission before virtual-thread creation, recording request, latency and
   * admission measurements. Metrics calls happen while the dispatcher holds its lock, so an
   * implementation must not block.
   *
   * @param policy aggregate limits for this dispatcher
   * @param metrics receiver of measurements; {@link Metrics#NOOP} disables recording
   */
  public RequestDispatcher(AdmissionPolicy policy, Metrics metrics) {
    this(policy, DEFAULT_STREAM_POLICY, metrics);
  }

  /**
   * Creates bounded admission for requests and streams, recording measurements. Metrics calls
   * happen while the dispatcher holds its lock, so an implementation must not block.
   *
   * @param policy aggregate limits for this dispatcher
   * @param streamPolicy aggregate limit on active streams
   * @param metrics receiver of measurements; {@link Metrics#NOOP} disables recording
   */
  public RequestDispatcher(AdmissionPolicy policy, StreamPolicy streamPolicy, Metrics metrics) {
    this(ExecutionBudget.of(policy), streamPolicy, metrics);
  }

  /**
   * Creates the application-owned budget with its aggregate limits, streams and measurements. Route
   * overrides are supplied per submission by the application's compiled routes.
   *
   * @param budget aggregate admission limits of the application
   * @param streamPolicy aggregate limit on active streams
   * @param metrics receiver of measurements; {@link Metrics#NOOP} disables recording
   */
  public RequestDispatcher(ExecutionBudget budget, StreamPolicy streamPolicy, Metrics metrics) {
    this(
        budget,
        streamPolicy,
        Executors.newThreadPerTaskExecutor(Thread.ofVirtual().name("axiom-handler-", 0).factory()),
        scheduler(),
        System::nanoTime,
        metrics);
  }

  RequestDispatcher(int capacity, ExecutorService workers, ScheduledExecutorService deadlines) {
    this(AdmissionPolicy.reject(capacity), workers, deadlines, System::nanoTime);
  }

  RequestDispatcher(
      AdmissionPolicy policy,
      ExecutorService workers,
      ScheduledExecutorService deadlines,
      LongSupplier clock) {
    this(policy, DEFAULT_STREAM_POLICY, workers, deadlines, clock);
  }

  RequestDispatcher(
      AdmissionPolicy policy,
      ExecutorService workers,
      ScheduledExecutorService deadlines,
      LongSupplier clock,
      Metrics metrics) {
    this(policy, DEFAULT_STREAM_POLICY, workers, deadlines, clock, metrics);
  }

  RequestDispatcher(
      AdmissionPolicy policy,
      StreamPolicy streamPolicy,
      ExecutorService workers,
      ScheduledExecutorService deadlines,
      LongSupplier clock) {
    this(policy, streamPolicy, workers, deadlines, clock, Metrics.NOOP);
  }

  RequestDispatcher(
      AdmissionPolicy policy,
      StreamPolicy streamPolicy,
      ExecutorService workers,
      ScheduledExecutorService deadlines,
      LongSupplier clock,
      Metrics metrics) {
    this(ExecutionBudget.of(policy), streamPolicy, workers, deadlines, clock, metrics);
  }

  private RequestDispatcher(
      ExecutionBudget budget,
      StreamPolicy streamPolicy,
      ExecutorService workers,
      ScheduledExecutorService deadlines,
      LongSupplier clock,
      Metrics metrics) {
    Objects.requireNonNull(metrics, "metrics");
    this.metrics =
        metrics == Metrics.NOOP ? DispatchMetrics.DISABLED : new DispatchMetrics(metrics);
    this.policy = Objects.requireNonNull(budget, "budget").aggregate();
    this.streamPolicy = Objects.requireNonNull(streamPolicy, "streamPolicy");
    this.workers = workers;
    this.deadlines = deadlines;
    this.clock = clock;
  }

  private static ScheduledExecutorService scheduler() {
    var scheduler =
        new ScheduledThreadPoolExecutor(
            1, Thread.ofPlatform().name("axiom-deadline-", 0).factory());
    scheduler.setRemoveOnCancelPolicy(true);
    scheduler.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);
    return scheduler;
  }

  /**
   * Submits work in the default admission bucket.
   *
   * @param context request identity and deadline
   * @param action framework-owned work, including response preparation
   * @param <T> result type
   * @return cancellation and outcome handle
   */
  public <T> Task<T> submit(ExecutionContext context, Callable<T> action) {
    return submit(DEFAULT_KEY, policy, context, action);
  }

  /**
   * Admits work against aggregate and endpoint limits, queuing only when both policies allow it.
   *
   * @param key stable endpoint identity, never a request-specific path
   * @param endpointPolicy immutable policy for this endpoint
   * @param context request identity and remaining deadline
   * @param action framework-owned work, including response preparation
   * @param <T> result type
   * @return cancellation handle; queued work has no execution thread
   * @throws RejectedExecutionException if closed or capacity is exhausted
   */
  public <T> Task<T> submit(
      Object key, AdmissionPolicy endpointPolicy, ExecutionContext context, Callable<T> action) {
    return submit(key, endpointPolicy, context, action, null);
  }

  /**
   * Admits work like {@link #submit(Object, AdmissionPolicy, ExecutionContext, Callable)} and
   * reports the status class of its result to the metrics. The endpoint key is also the metrics
   * identity: a {@link com.jsgalactic.axiom.routing.Route} is tagged by method and template, any
   * other key as unmatched.
   *
   * @param key stable endpoint identity, never a request-specific path
   * @param endpointPolicy immutable policy for this endpoint
   * @param context request identity and remaining deadline
   * @param action framework-owned work, including response preparation
   * @param status maps a result to its HTTP status code, or null to count results as unknown
   * @param <T> result type
   * @return cancellation handle; queued work has no execution thread
   * @throws RejectedExecutionException if closed or capacity is exhausted
   */
  public <T> Task<T> submit(
      Object key,
      AdmissionPolicy endpointPolicy,
      ExecutionContext context,
      Callable<T> action,
      ToIntFunction<? super T> status) {
    return submitWithSession(null, key, endpointPolicy, context, action, status);
  }

  /**
   * Admits work on behalf of one session, so closing that session alone stops its new work and
   * fails its waiting work. The budget, queue and workers remain shared with every other session.
   *
   * @param session submitting listener or test client, or null for a submitter with no lifetime
   * @param key stable endpoint identity, never a request-specific path
   * @param endpointPolicy immutable policy for this endpoint
   * @param context request identity and remaining deadline
   * @param action framework-owned work, including response preparation
   * @param <T> result type
   * @return cancellation handle; queued work has no execution thread
   * @throws RejectedExecutionException if the session, dispatcher or capacity is closed
   */
  public <T> Task<T> submit(
      Session session,
      Object key,
      AdmissionPolicy endpointPolicy,
      ExecutionContext context,
      Callable<T> action) {
    return submitWithSession(session, key, endpointPolicy, context, action, null);
  }

  /**
   * Like {@link #submit(Session, Object, AdmissionPolicy, ExecutionContext, Callable)}, also
   * reporting the status class of the result to the metrics.
   *
   * @param session submitting listener or test client, or null for a submitter with no lifetime
   * @param key stable endpoint identity, never a request-specific path
   * @param endpointPolicy immutable policy for this endpoint
   * @param context request identity and remaining deadline
   * @param action framework-owned work, including response preparation
   * @param status maps a result to its HTTP status code, or null to count results as unknown
   * @param <T> result type
   * @return cancellation handle; queued work has no execution thread
   * @throws RejectedExecutionException if the session, dispatcher or capacity is closed
   */
  public <T> Task<T> submit(
      Session session,
      Object key,
      AdmissionPolicy endpointPolicy,
      ExecutionContext context,
      Callable<T> action,
      ToIntFunction<? super T> status) {
    return submitWithSession(session, key, endpointPolicy, context, action, status);
  }

  private <T> Task<T> submitWithSession(
      Session session,
      Object key,
      AdmissionPolicy endpointPolicy,
      ExecutionContext context,
      Callable<T> action,
      ToIntFunction<? super T> status) {
    Objects.requireNonNull(key, "key");
    Objects.requireNonNull(endpointPolicy, "endpointPolicy");
    Objects.requireNonNull(context, "context");
    Objects.requireNonNull(action, "action");
    var signals = new ArrayList<Runnable>();
    Task<T> task = null;
    Throwable submissionFailure = null;
    var series = metrics.series(key);
    synchronized (this) {
      if (closed || stopping || (session != null && !session.accepting)) {
        rejected++;
        metrics.refused(series, DispatchMetrics.Reason.SHUTDOWN);
        throw new RejectedExecutionException("Dispatcher closed");
      }
      var bucket = buckets.get(key);
      if (bucket != null && !bucket.policy.equals(endpointPolicy)) {
        // A caller bug, not overload: reported as IllegalArgumentException and not counted as
        // rejected.
        throw new IllegalArgumentException("Endpoint policy changed after admission");
      }
      if (bucket == null) {
        bucket = new Bucket(key, endpointPolicy);
      }
      boolean room = active < policy.maxActive() && bucket.active < endpointPolicy.maxActive();
      boolean full = !room && queueFull(bucket);
      if (full) {
        // Slots held by waits that already expired are reclaimed before refusing.
        expireDue(signals);
        full = queueFull(bucket);
      }
      if (full) {
        rejected++;
        metrics.refused(series, DispatchMetrics.Reason.CAPACITY);
        submissionFailure = new RejectedExecutionException("Request capacity unavailable");
      } else {
        buckets.put(key, bucket);
        task = new Task<>(session, context, action, bucket, series, status, clock.getAsLong());
        tasks.add(task);
        if (session != null) {
          session.outstanding++;
        }
        if (room) {
          submissionFailure = activate(task, signals);
        } else {
          task.waiting = true;
          task.queuedAt = clock.getAsLong();
          task.queueBudget =
              Math.min(policy.queueTimeout().toNanos(), endpointPolicy.queueTimeout().toNanos());
          task.sequence = sequence++;
          // Both bounds in this dispatcher's clock, from the time the request joined the queue.
          task.deadlineAt = task.queuedAt + context.remainingTime().toNanos();
          task.expiresAt = Math.min(task.queuedAt + task.queueBudget, task.deadlineAt);
          bucket.enqueue(task);
          byExpiry.add(task);
          queued++;
          metrics.queued(1);
          queuedBuckets.add(bucket);
          try {
            schedule(task, Math.min(task.queueBudget, context.remainingTime().toNanos()));
          } catch (RuntimeException | Error failure) {
            finish(
                task,
                null,
                failure instanceof RejectedExecutionException
                    ? new DispatchRejectedException(failure)
                    : failure,
                false,
                signals);
            submissionFailure = failure;
          }
        }
        if (submissionFailure == null) {
          accepted++;
        } else if (submissionFailure instanceof RejectedExecutionException) {
          rejected++;
          metrics.rejected(series, DispatchMetrics.Reason.EXECUTOR);
        }
      }
    }
    publish(signals);
    if (submissionFailure instanceof RuntimeException failure) {
      throw failure;
    }
    if (submissionFailure instanceof Error failure) {
      throw failure;
    }
    return task;
  }

  /**
   * Returns the metric tags of an endpoint key, from the same bounded set the request series use
   * (at most 1024 endpoints, then {@code other}), so other measurements of the endpoint, such as
   * streams, line up with its request series.
   *
   * @param key the endpoint identity given to {@link #submit(Object, AdmissionPolicy,
   *     ExecutionContext, Callable)}
   * @return its tags, or null when metrics are disabled
   */
  public EndpointTag endpointTag(Object key) {
    return metrics.tag(Objects.requireNonNull(key, "key"));
  }

  private boolean queueFull(Bucket bucket) {
    return queued >= policy.maxQueued() || bucket.waiting.size() >= bucket.policy.maxQueued();
  }

  /**
   * Observes a consistent admission snapshot without callbacks. {@code rejected} counts closed or
   * full-capacity rejections and executor or scheduler rejections during submission; a conflicting
   * endpoint policy is a caller error ({@link IllegalArgumentException}) and is not counted.
   * Promotion failures after acceptance fail that request but are not counted as rejections.
   *
   * @return counters scoped to this dispatcher
   */
  public synchronized AdmissionSnapshot snapshot() {
    return new AdmissionSnapshot(active, queued, accepted, rejected, queueTimeouts);
  }

  /**
   * Returns the number of active stream slots, and the number for one endpoint when a key is given.
   *
   * @param key endpoint identity to inspect, or null for the aggregate count
   * @return active streams in this dispatcher
   */
  public synchronized int activeStreams(Object key) {
    if (key == null) {
      return streamActive;
    }
    var bucket = streamBuckets.get(key);
    return bucket == null ? 0 : bucket.active;
  }

  /** Returns the aggregate number of active stream slots. */
  public synchronized int activeStreams() {
    return activeStreams(null);
  }

  /**
   * Transfers the calling task's request slot into a stream slot for one endpoint. The capacity
   * checks and the transfer happen under this dispatcher's lock, so no head bytes are written for a
   * stream that cannot be admitted. A refusal leaves the request slot in place and records a {@code
   * stream_capacity} rejection; it does not change the request's deadline or cancellation.
   *
   * @param key stable endpoint identity, never a request-specific path
   * @param endpointPolicy immutable stream bound for this endpoint
   * @return true when the slot was transferred, false when either bound is full
   * @throws IllegalStateException if called outside a dispatcher task or its slot is no longer
   *     active
   */
  public boolean admitStream(Object key, StreamPolicy endpointPolicy) {
    Objects.requireNonNull(key, "key");
    Objects.requireNonNull(endpointPolicy, "endpointPolicy");
    var task = currentTask.get();
    if (task == null) {
      throw new IllegalStateException("No active dispatcher task on this thread");
    }
    var signals = new ArrayList<Runnable>();
    boolean admitted;
    synchronized (this) {
      if (task.finished || !task.reserved || task.streaming) {
        throw new IllegalStateException("The request cannot become a stream");
      }
      var bucket = streamBuckets.get(key);
      if (bucket == null) {
        bucket = new StreamBucket(key, endpointPolicy);
      } else if (!bucket.policy.equals(endpointPolicy)) {
        throw new IllegalArgumentException("Endpoint stream policy changed after admission");
      }
      if (streamActive >= streamPolicy.maxActive() || bucket.active >= endpointPolicy.maxActive()) {
        metrics.rejected(task.series, DispatchMetrics.Reason.STREAM_CAPACITY);
        admitted = false;
      } else {
        releaseRequestSlot(task);
        prune(task.bucket);
        streamBuckets.put(key, bucket);
        bucket.active++;
        streamActive++;
        task.streamBucket = bucket;
        task.streaming = true;
        drain(signals);
        admitted = true;
      }
    }
    publish(signals);
    return admitted;
  }

  /**
   * Reserves a stream slot for a long-lived connection such as a WebSocket session, outside any
   * dispatcher task. The aggregate and endpoint bounds apply as for a streamed response. The caller
   * must release the slot with {@link #releaseStreamSlot} when the connection ends; a refused
   * reservation records a rejection and must not be released.
   *
   * @param key stable endpoint identity, never a request-specific path
   * @param endpointPolicy immutable stream bound for this endpoint
   * @return true when a slot was reserved, false when either bound was full
   */
  public boolean admitStreamSlot(Object key, StreamPolicy endpointPolicy) {
    Objects.requireNonNull(key, "key");
    Objects.requireNonNull(endpointPolicy, "endpointPolicy");
    boolean admitted;
    synchronized (this) {
      if (streamActive >= streamPolicy.maxActive()) {
        metrics.rejected(metrics.series(key), DispatchMetrics.Reason.STREAM_CAPACITY);
        admitted = false;
      } else {
        var bucket = streamBuckets.get(key);
        if (bucket == null) {
          bucket = new StreamBucket(key, endpointPolicy);
        } else if (!bucket.policy.equals(endpointPolicy)) {
          throw new IllegalArgumentException("Endpoint stream policy changed after admission");
        }
        admitted = bucket.active < endpointPolicy.maxActive();
        if (admitted) {
          bucket.active++;
          streamActive++;
          streamBuckets.put(key, bucket);
        } else {
          metrics.rejected(metrics.series(key), DispatchMetrics.Reason.STREAM_CAPACITY);
        }
      }
    }
    return admitted;
  }

  /**
   * Releases a slot reserved by {@link #admitStreamSlot}. Idempotent for an unknown key.
   *
   * @param key identity passed to {@code admitStreamSlot}
   */
  public void releaseStreamSlot(Object key) {
    Objects.requireNonNull(key, "key");
    synchronized (this) {
      var bucket = streamBuckets.get(key);
      if (bucket != null && bucket.active > 0) {
        bucket.active--;
        streamActive--;
        pruneStream(bucket);
      }
    }
  }

  /**
   * Observes complete resource termination, including handlers that ignore interruption.
   *
   * @return completion after executors stop
   */
  public CompletionStage<Void> termination() {
    return stopped.minimalCompletionStage();
  }

  /**
   * Registers one submitter. Closing a session stops only that submitter: the aggregate counters,
   * queue and workers remain shared with the other sessions of the application.
   *
   * @return session for one listener or test client
   * @throws RejectedExecutionException if the dispatcher is closed, stopping or retired
   */
  public Session openSession() {
    synchronized (this) {
      if (closed || stopping || retiring) {
        throw new RejectedExecutionException("Dispatcher closed");
      }
      var session = new Session();
      sessions.add(session);
      return session;
    }
  }

  /**
   * Stops admission for the whole application and shuts its executors down once the sessions have
   * closed. Running work keeps its capacity until its session finishes its drain.
   */
  public void retire() {
    synchronized (this) {
      retiring = true;
    }
    stopAdmission();
    maybeCloseDispatcher();
  }

  private void maybeCloseDispatcher() {
    boolean shutdown;
    synchronized (this) {
      shutdown = retiring && sessions.isEmpty() && !closed;
    }
    if (shutdown) {
      close();
    }
  }

  /**
   * One submitter's lifetime. A session shares all capacity with the other sessions of its
   * dispatcher; closing it refuses new requests, fails its waiting requests and cancels its running
   * work, without touching the work of another session.
   */
  public final class Session implements AutoCloseable {
    private final CompletableFuture<Void> stopped = new CompletableFuture<>();
    private boolean accepting = true;
    private boolean closed;
    private boolean terminated;
    private int outstanding;

    /** Refuses new submissions and fails this session's waiting requests with 503. */
    public void stop() {
      var signals = new ArrayList<Runnable>();
      synchronized (RequestDispatcher.this) {
        accepting = false;
        for (var task : List.copyOf(tasks)) {
          if (task.session == this && task.waiting) {
            finish(
                task,
                null,
                new DispatchRejectedException(new RejectedExecutionException("Session stopped")),
                false,
                signals);
            metrics.rejected(task.series, DispatchMetrics.Reason.SHUTDOWN);
          }
        }
        expireDue(signals);
        drain(signals);
      }
      publish(signals);
    }

    /** Stops this session, cancels its remaining work and completes its termination. */
    @Override
    public void close() {
      var signals = new ArrayList<Runnable>();
      synchronized (RequestDispatcher.this) {
        accepting = false;
        closed = true;
        sessions.remove(this);
        for (var task : List.copyOf(tasks)) {
          if (task.session == this) {
            finish(task, null, new CancellationException("Session closed"), true, signals);
          }
        }
        maybeTerminate(signals);
      }
      publish(signals);
      maybeCloseDispatcher();
    }

    /**
     * Observes when this session has closed and all of its work has exited, including handlers that
     * ignored interruption.
     *
     * @return completion after the session's work ends
     */
    public CompletionStage<Void> termination() {
      return stopped.minimalCompletionStage();
    }

    private void finished(List<Runnable> signals) {
      outstanding--;
      maybeTerminate(signals);
    }

    private void maybeTerminate(List<Runnable> signals) {
      if (closed && outstanding == 0 && !terminated) {
        terminated = true;
        signals.add(() -> stopped.complete(null));
      }
    }
  }

  /**
   * Stops admission without interrupting running work, for a graceful drain. Later submissions are
   * rejected and queued work fails with {@link DispatchRejectedException}, so it is never promoted.
   * Active work keeps its capacity until it exits or {@link #close()} cancels it.
   */
  public void stopAdmission() {
    var signals = new ArrayList<Runnable>();
    synchronized (this) {
      if (closed || stopping) {
        return;
      }
      stopping = true;
      for (var task : List.copyOf(tasks)) {
        if (task.waiting) {
          finish(
              task,
              null,
              new DispatchRejectedException(new RejectedExecutionException("Dispatcher stopping")),
              false,
              signals);
          metrics.rejected(task.series, DispatchMetrics.Reason.SHUTDOWN);
        }
      }
    }
    publish(signals);
  }

  /** Stops admission, cancels active and queued work, and asynchronously joins owned executors. */
  @Override
  public void close() {
    var signals = new ArrayList<Runnable>();
    synchronized (this) {
      if (closed) {
        return;
      }
      closed = true;
      retiring = true;
      for (var session : sessions) {
        session.accepting = false;
        session.closed = true;
        session.maybeTerminate(signals);
      }
      for (var task : List.copyOf(tasks)) {
        finish(task, null, new CancellationException("Dispatcher closed"), true, signals);
      }
    }
    publish(signals);
    workers.shutdownNow();
    deadlines.shutdownNow();
    Thread.ofPlatform()
        .daemon(true)
        .name("axiom-execution-shutdown")
        .start(
            () -> {
              try {
                while (!workers.awaitTermination(1, TimeUnit.DAYS)) {
                  /* Cooperative shutdown. */
                }
                while (!deadlines.awaitTermination(1, TimeUnit.DAYS)) {
                  /* Join timer callbacks. */
                }
                stopped.complete(null);
              } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                stopped.completeExceptionally(interrupted);
              }
            });
  }

  // Called only under the dispatcher lock. Notifications are published after releasing it.
  private Throwable activate(Task<?> task, List<Runnable> signals) {
    if (task.waiting) {
      unqueue(task);
    }
    task.reserved = true;
    active++;
    metrics.active(1);
    task.bucket.active++;
    try {
      schedule(task, task.context.remainingTime().toNanos());
      workers.execute(task::run);
      return null;
    } catch (RuntimeException | Error failure) {
      finish(
          task,
          null,
          failure instanceof RejectedExecutionException
              ? new DispatchRejectedException(failure)
              : failure,
          false,
          signals);
      release(task);
      return failure;
    }
  }

  private void schedule(Task<?> task, long delay) {
    if (task.timer != null) {
      task.timer.cancel(false);
    }
    long generation = ++task.timerGeneration;
    task.timer = deadlines.schedule(() -> timeout(task, generation), delay, TimeUnit.NANOSECONDS);
  }

  private void timeout(Task<?> task, long generation) {
    var signals = new ArrayList<Runnable>();
    synchronized (this) {
      if (task.finished || generation != task.timerGeneration) {
        return;
      }
      if (!task.waiting && task.context.isDeadlineExtended() && !task.context.isExpired()) {
        // The running request was given more time (a stream with its own lifetime): wait for that.
        schedule(task, task.context.remainingTime().toNanos());
        return;
      }
      Throwable failure =
          task.waiting && !task.context.isExpired()
              ? new QueueTimeoutException()
              : new DeadlineExceededException();
      finish(task, null, failure, true, signals);
    }
    publish(signals);
  }

  private <T> void complete(Task<T> task, T value, Throwable failure, boolean interrupt) {
    var signals = new ArrayList<Runnable>();
    synchronized (this) {
      finish(task, value, failure, interrupt, signals);
    }
    publish(signals);
  }

  private <T> void finish(
      Task<T> task, T value, Throwable failure, boolean interrupt, List<Runnable> signals) {
    if (task.finished) {
      return;
    }
    task.finished = true;
    if (task.timer != null) {
      task.timer.cancel(false);
    }
    if (failure instanceof QueueTimeoutException) {
      queueTimeouts++;
      metrics.rejected(task.series, DispatchMetrics.Reason.QUEUE_TIMEOUT);
    }
    if (metrics.enabled()) {
      metrics.request(
          task.series, outcome(task, value, failure), clock.getAsLong() - task.submittedAt);
    }
    if (task.waiting) {
      unqueue(task);
      tasks.remove(task);
      prune(task.bucket);
    }
    if (task.session != null) {
      task.session.finished(signals);
    }
    if (interrupt && task.runner != null) {
      task.runner.interrupt();
    }
    signals.add(
        () -> {
          if (failure == null) {
            task.result.complete(value);
          } else {
            task.result.completeExceptionally(failure);
          }
        });
  }

  /** The status code that classifies a finished request, or the cancelled and unknown codes. */
  private static <T> int outcome(Task<T> task, T value, Throwable failure) {
    if (failure == null) {
      if (task.status == null) {
        return DispatchMetrics.UNKNOWN;
      }
      try {
        return task.status.applyAsInt(value);
      } catch (RuntimeException unreadable) {
        return DispatchMetrics.UNKNOWN;
      }
    }
    if (failure instanceof CancellationException) {
      return DispatchMetrics.CANCELLED;
    }
    if (failure instanceof DeadlineExceededException) {
      return 504;
    }
    if (failure instanceof QueueTimeoutException || failure instanceof DispatchRejectedException) {
      return 503;
    }
    return 500;
  }

  private void unqueue(Task<?> task) {
    var bucket = task.bucket;
    byExpiry.remove(task);
    bucket.waiting.remove(task);
    queued--;
    metrics.queued(-1);
    if (metrics.enabled()) {
      metrics.queueWait(task.series, clock.getAsLong() - task.queuedAt);
    }
    if (bucket.waiting.isEmpty()) {
      queuedBuckets.remove(bucket);
    }
    task.waiting = false;
  }

  private void release(Task<?> task) {
    if (!task.reserved) {
      return;
    }
    task.reserved = false;
    if (task.streaming) {
      task.streaming = false;
      releaseStreamSlot(task);
      pruneStream(task.streamBucket);
      task.streamBucket = null;
    } else {
      releaseRequestSlot(task);
    }
    tasks.remove(task);
    prune(task.bucket);
  }

  private void releaseRequestSlot(Task<?> task) {
    active--;
    metrics.active(-1);
    task.bucket.active--;
  }

  private void releaseStreamSlot(Task<?> task) {
    streamActive--;
    task.streamBucket.active--;
  }

  private void prune(Bucket bucket) {
    if (bucket.active == 0 && bucket.waiting.isEmpty()) {
      buckets.remove(bucket.key, bucket);
    }
  }

  private void pruneStream(StreamBucket bucket) {
    if (bucket.active == 0) {
      streamBuckets.remove(bucket.key, bucket);
    }
  }

  private void drain(List<Runnable> signals) {
    // Oldest eligible request wins. Only the head of each endpoint FIFO is a candidate, and
    // endpoints at their own limit are skipped, so cost is bounded by queued endpoints
    // rather than queued requests, and a blocked endpoint does not hold up unrelated work.
    while (!closed && !stopping && active < policy.maxActive()) {
      Task<?> head = null;
      for (var bucket : queuedBuckets) {
        if (bucket.active >= bucket.policy.maxActive()) {
          continue;
        }
        var candidate = bucket.waiting.iterator().next();
        if (head == null || candidate.sequence < head.sequence) {
          head = candidate;
        }
      }
      if (head == null) {
        return;
      }
      var expired = expiry(head, clock.getAsLong());
      if (expired != null) {
        finish(head, null, expired, false, signals);
      } else {
        activate(head, signals);
      }
    }
  }

  /**
   * Fails every waiting request whose queue wait or execution deadline has run out, including
   * requests of endpoints at their own active limit, which {@link #drain} skips, and requests whose
   * own deadline is shorter than the wait of the requests ahead of them. Requests are visited in
   * order of expiry, so the cost is a logarithmic removal per expired request and never a scan of
   * the queue.
   */
  private void expireDue(List<Runnable> signals) {
    if (byExpiry.isEmpty()) {
      return;
    }
    long now = clock.getAsLong();
    while (!byExpiry.isEmpty()) {
      var first = byExpiry.first();
      var expired = expiry(first, now);
      if (expired == null) {
        return;
      }
      finish(first, null, expired, false, signals);
    }
  }

  /** Orders waiting requests by when they expire, then by arrival. */
  private static int expiresBefore(Task<?> a, Task<?> b) {
    long difference = a.expiresAt - b.expiresAt;
    return difference != 0 ? Long.signum(difference) : Long.compare(a.sequence, b.sequence);
  }

  private static Throwable expiry(Task<?> task, long now) {
    if (task.context.isExpired() || now - task.deadlineAt >= 0) {
      return new DeadlineExceededException();
    }
    if (now - task.queuedAt >= task.queueBudget) {
      return new QueueTimeoutException();
    }
    return null;
  }

  private static void publish(List<Runnable> signals) {
    signals.forEach(Runnable::run);
  }

  private static final class Bucket {
    private final Object key;
    private final AdmissionPolicy policy;
    private int active;
    private final Set<Task<?>> waiting = new LinkedHashSet<>();

    private Bucket(Object key, AdmissionPolicy policy) {
      this.key = key;
      this.policy = policy;
    }

    private void enqueue(Task<?> task) {
      waiting.add(task);
    }
  }

  /** Per-endpoint stream slots; unlike requests, streams have no waiting queue. */
  private static final class StreamBucket {
    private final Object key;
    private final StreamPolicy policy;
    private int active;

    private StreamBucket(Object key, StreamPolicy policy) {
      this.key = key;
      this.policy = policy;
    }
  }

  /** Runtime deadline expiry, distinct from application-thrown timeout exceptions. */
  public static final class DeadlineExceededException extends TimeoutException {
    private static final long serialVersionUID = 1L;

    private DeadlineExceededException() {
      super("Request deadline exceeded");
    }
  }

  /**
   * Executor or scheduler rejected framework-owned submission, or admission stopped while it
   * waited.
   */
  public static final class DispatchRejectedException extends RejectedExecutionException {
    private static final long serialVersionUID = 1L;

    private DispatchRejectedException(Throwable cause) {
      super("Request submission rejected", cause);
    }
  }

  /** Bounded queue wait expired before execution started. */
  public static final class QueueTimeoutException extends TimeoutException {
    private static final long serialVersionUID = 1L;

    private QueueTimeoutException() {
      super("Request queue wait exceeded");
    }
  }

  /**
   * A single request outcome and cancellation handle. Cancellation does not imply active code
   * exited.
   *
   * @param <T> result type
   */
  public final class Task<T> {
    private final Session session;
    private final ExecutionContext context;
    private final Callable<T> action;
    private final Bucket bucket;
    private final DispatchMetrics.Series series;
    private final ToIntFunction<? super T> status;
    private final long submittedAt;
    private final CompletableFuture<T> result = new CompletableFuture<>();
    private ScheduledFuture<?> timer;
    private long timerGeneration;
    private long sequence;
    private long queuedAt;
    private long queueBudget;

    /**
     * While waiting: when the execution deadline and the earlier of both bounds run out, on the
     * dispatcher clock.
     */
    private long deadlineAt;

    private long expiresAt;
    private Thread runner;
    private boolean waiting;
    private boolean reserved;
    private boolean streaming;
    private StreamBucket streamBucket;
    private boolean finished;

    private Task(
        Session session,
        ExecutionContext context,
        Callable<T> action,
        Bucket bucket,
        DispatchMetrics.Series series,
        ToIntFunction<? super T> status,
        long submittedAt) {
      this.session = session;
      this.context = context;
      this.action = action;
      this.bucket = bucket;
      this.series = series;
      this.status = status;
      this.submittedAt = submittedAt;
    }

    /**
     * Observes the single request outcome.
     *
     * @return read-only completion stage
     */
    public CompletionStage<T> result() {
      return result.minimalCompletionStage();
    }

    /** Cancels queued work immediately; running work retains capacity until it exits. */
    public void cancel() {
      complete(this, null, new CancellationException("Request cancelled"), true);
    }

    private void run() {
      T value = null;
      Throwable failure = null;
      Error fatal = null;
      currentTask.set(this);
      try {
        synchronized (RequestDispatcher.this) {
          if (finished) {
            return;
          }
          runner = Thread.currentThread();
        }
        if (context.isExpired()) {
          failure = new DeadlineExceededException();
        } else {
          value = action.call();
          if (context.isExpired()) {
            value = null;
            failure = new DeadlineExceededException();
          }
        }
      } catch (Throwable thrown) {
        failure = context.isExpired() ? new DeadlineExceededException() : thrown;
        if (thrown instanceof Error error) {
          fatal = error;
        }
      } finally {
        currentTask.remove();
        exit(value, failure);
      }
      if (fatal != null) {
        throw fatal;
      }
    }

    /**
     * Runner-thread exit: outcome, permit release and drain happen in one critical section, so a
     * completion callback that submits the next request never sees this slot still held.
     */
    private void exit(T value, Throwable failure) {
      var signals = new ArrayList<Runnable>();
      synchronized (RequestDispatcher.this) {
        finish(this, value, failure, false, signals);
        runner = null;
        release(this);
        expireDue(signals);
        drain(signals);
      }
      publish(signals);
    }
  }
}
