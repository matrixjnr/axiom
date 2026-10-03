package com.jsgalactic.axiom.server.internal.execution;

import com.jsgalactic.axiom.execution.AdmissionPolicy;
import com.jsgalactic.axiom.execution.AdmissionSnapshot;
import com.jsgalactic.axiom.execution.ExecutionContext;
import com.jsgalactic.axiom.observability.Metrics;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
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

/** Protocol-neutral, listener-owned execution. All admission state is protected by this object's lock. */
public final class RequestDispatcher implements AutoCloseable {
    private static final Object DEFAULT_KEY = new Object();
    private final AdmissionPolicy policy;
    private final ExecutorService workers;
    private final ScheduledExecutorService deadlines;
    private final LongSupplier clock;
    private final DispatchMetrics metrics;
    private final Set<Task<?>> tasks = new HashSet<>();
    private final Set<Bucket> queuedBuckets = new LinkedHashSet<>();
    private final Map<Object, Bucket> buckets = new HashMap<>();
    private final CompletableFuture<Void> stopped = new CompletableFuture<>();
    private int active;
    private int queued;
    private long sequence;
    private long accepted;
    private long rejected;
    private long queueTimeouts;
    private boolean stopping;
    private boolean closed;

    /**
     * Creates execution with immediate rejection when busy.
     * @param capacity maximum reserved or running executions
     */
    public RequestDispatcher(int capacity) { this(AdmissionPolicy.reject(capacity)); }

    /**
     * Creates bounded admission before virtual-thread creation.
     * @param policy aggregate limits for this dispatcher
     */
    public RequestDispatcher(AdmissionPolicy policy) { this(policy, Metrics.NOOP); }

    /**
     * Creates bounded admission before virtual-thread creation, recording request, latency and
     * admission measurements. Metrics calls happen while the dispatcher holds its lock, so an
     * implementation must not block.
     * @param policy aggregate limits for this dispatcher
     * @param metrics receiver of measurements; {@link Metrics#NOOP} disables recording
     */
    public RequestDispatcher(AdmissionPolicy policy, Metrics metrics) {
        this(policy, Executors.newThreadPerTaskExecutor(
                Thread.ofVirtual().name("axiom-handler-", 0).factory()), scheduler(), System::nanoTime, metrics);
    }

    RequestDispatcher(int capacity, ExecutorService workers, ScheduledExecutorService deadlines) {
        this(AdmissionPolicy.reject(capacity), workers, deadlines, System::nanoTime);
    }

    RequestDispatcher(AdmissionPolicy policy, ExecutorService workers,
            ScheduledExecutorService deadlines, LongSupplier clock) {
        this(policy, workers, deadlines, clock, Metrics.NOOP);
    }

    RequestDispatcher(AdmissionPolicy policy, ExecutorService workers,
            ScheduledExecutorService deadlines, LongSupplier clock, Metrics metrics) {
        Objects.requireNonNull(metrics, "metrics");
        this.metrics = metrics == Metrics.NOOP ? DispatchMetrics.DISABLED : new DispatchMetrics(metrics);
        this.policy = Objects.requireNonNull(policy, "policy");
        this.workers = workers;
        this.deadlines = deadlines;
        this.clock = clock;
    }

    private static ScheduledExecutorService scheduler() {
        var scheduler = new ScheduledThreadPoolExecutor(1,
                Thread.ofPlatform().name("axiom-deadline-", 0).factory());
        scheduler.setRemoveOnCancelPolicy(true);
        scheduler.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);
        return scheduler;
    }

    /**
     * Submits work in the default admission bucket.
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
     * @param key stable endpoint identity, never a request-specific path
     * @param endpointPolicy immutable policy for this endpoint
     * @param context request identity and remaining deadline
     * @param action framework-owned work, including response preparation
     * @param <T> result type
     * @return cancellation handle; queued work has no execution thread
     * @throws RejectedExecutionException if closed or capacity is exhausted
     */
    public <T> Task<T> submit(Object key, AdmissionPolicy endpointPolicy,
            ExecutionContext context, Callable<T> action) {
        return submit(key, endpointPolicy, context, action, null);
    }

    /**
     * Admits work like {@link #submit(Object, AdmissionPolicy, ExecutionContext, Callable)} and
     * reports the status class of its result to the metrics. The endpoint key is also the metrics
     * identity: a {@link com.jsgalactic.axiom.routing.Route} is tagged by method and template, any
     * other key as unmatched.
     * @param key stable endpoint identity, never a request-specific path
     * @param endpointPolicy immutable policy for this endpoint
     * @param context request identity and remaining deadline
     * @param action framework-owned work, including response preparation
     * @param status maps a result to its HTTP status code, or null to count results as unknown
     * @param <T> result type
     * @return cancellation handle; queued work has no execution thread
     * @throws RejectedExecutionException if closed or capacity is exhausted
     */
    public <T> Task<T> submit(Object key, AdmissionPolicy endpointPolicy,
            ExecutionContext context, Callable<T> action, ToIntFunction<? super T> status) {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(endpointPolicy, "endpointPolicy");
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(action, "action");
        var signals = new ArrayList<Runnable>();
        Task<T> task = null;
        Throwable submissionFailure = null;
        var series = metrics.series(key);
        synchronized (this) {
            if (closed || stopping) {
                rejected++;
                metrics.refused(series, DispatchMetrics.Reason.SHUTDOWN);
                new RejectedExecutionException("Dispatcher closed");
            }
            var bucket = buckets.get(key);
            if (bucket != null && !bucket.policy.equals(endpointPolicy)) {
                // A caller bug, not overload: reported as IllegalArgumentException and not counted as rejected.
                throw new IllegalArgumentException("Endpoint policy changed after admission");
            }
            if (bucket == null) { bucket = new Bucket(key, endpointPolicy); }
            boolean room = active < policy.maxActive() && bucket.active < endpointPolicy.maxActive();
            boolean full = !room && queueFull(bucket);
            if (full) {
                // Slots held by waits that already expired are reclaimed before refusing.
                expireStaleHeads(signals);
                full = queueFull(bucket);
            }
            if (full) {
                rejected++;
                metrics.refused(series, DispatchMetrics.Reason.CAPACITY);
                submissionFailure = new RejectedExecutionException("Request capacity unavailable");
            } else {
                buckets.put(key, bucket);
                task = new Task<>(context, action, bucket, series, status, clock.getAsLong());
                tasks.add(task);
                if (room) {
                    submissionFailure = activate(task, signals);
                } else {
                    task.waiting = true;
                    task.queuedAt = clock.getAsLong();
                    task.queueBudget = Math.min(policy.queueTimeout().toNanos(), endpointPolicy.queueTimeout().toNanos());
                    task.sequence = sequence++;
                    bucket.enqueue(task);
                    queued++;
                    metrics.queued(1);
                    queuedBuckets.add(bucket);
                    try { schedule(task, Math.min(task.queueBudget, context.remainingTime().toNanos())); }
                    catch (RuntimeException | Error failure) {
                        finish(task, null, failure instanceof RejectedExecutionException
                        ? new DispatchRejectedException(failure) : failure, false, signals);
                        submissionFailure = failure;
                    }
                }
                if (submissionFailure == null) { accepted++; }
                else if (submissionFailure instanceof RejectedExecutionException) {
                    rejected++;
                    metrics.rejected(series, DispatchMetrics.Reason.EXECUTOR);
                }
            }
        }
        publish(signals);
        if (submissionFailure instanceof RuntimeException failure) { throw failure; }
        if (submissionFailure instanceof Error failure) { throw failure; }
        return task;
    }

    private boolean queueFull(Bucket bucket) {
        return queued >= policy.maxQueued() || bucket.waiting.size() >= bucket.policy.maxQueued();
    }

    /**
     * Observes a consistent admission snapshot without callbacks. {@code rejected} counts closed or
     * full-capacity rejections and executor or scheduler rejections during submission; a conflicting
     * endpoint policy is a caller error ({@link IllegalArgumentException}) and is not counted.
     * Promotion failures after acceptance fail that request but are not counted as rejections.
     * @return counters scoped to this dispatcher
     */
    public synchronized AdmissionSnapshot snapshot() {
        return new AdmissionSnapshot(active, queued, accepted, rejected, queueTimeouts);
    }

    /**
     * Observes complete resource termination, including handlers that ignore interruption.
     * @return completion after executors stop
     */
    public CompletionStage<Void> termination() { return stopped.minimalCompletionStage(); }

    /**
     * Stops admission without interrupting running work, for a graceful drain. Later submissions are
     * rejected and queued work fails with {@link DispatchRejectedException}, so it is never promoted.
     * Active work keeps its capacity until it exits or {@link #close()} cancels it.
     */
    public void stopAdmission() {
        var signals = new ArrayList<Runnable>();
        synchronized (this) {
            if (closed || stopping) { return; }
            stopping = true;
            for (var task : List.copyOf(tasks)) {
                if (task.waiting) {
                    finish(task, null, new DispatchRejectedException(
                            new RejectedExecutionException("Dispatcher stopping")), false, signals);
                    metrics.rejected(task.series, DispatchMetrics.Reason.SHUTDOWN);
                }
            }
        }
        publish(signals);
    }

    /** Stops admission, cancels active and queued work, and asynchronously joins owned executors. */
    @Override public void close() {
        var signals = new ArrayList<Runnable>();
        synchronized (this) {
            if (closed) { return; }
            closed = true;
            for (var task : List.copyOf(tasks)) {
                finish(task, null, new CancellationException("Dispatcher closed"), true, signals);
            }
        }
        publish(signals);
        workers.shutdownNow();
        deadlines.shutdownNow();
        Thread.ofPlatform().daemon(true).name("axiom-execution-shutdown").start(() -> {
            try {
                while (!workers.awaitTermination(1, TimeUnit.DAYS)) { /* Cooperative shutdown. */ }
                while (!deadlines.awaitTermination(1, TimeUnit.DAYS)) { /* Join timer callbacks. */ }
                stopped.complete(null);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                stopped.completeExceptionally(interrupted);
            }
        });
    }

    // Called only under the dispatcher lock. Notifications are published after releasing it.
    private Throwable activate(Task<?> task, List<Runnable> signals) {
        if (task.waiting) { unqueue(task); }
        task.reserved = true;
        active++;
        metrics.active(1);
        task.bucket.active++;
        try {
            schedule(task, task.context.remainingTime().toNanos());
            workers.execute(task::run);
            return null;
        } catch (RuntimeException | Error failure) {
            finish(task, null, failure instanceof RejectedExecutionException
                    ? new DispatchRejectedException(failure) : failure, false, signals);
            release(task);
            return failure;
        }
    }

    private void schedule(Task<?> task, long delay) {
        if (task.timer != null) { task.timer.cancel(false); }
        long generation = ++task.timerGeneration;
        task.timer = deadlines.schedule(() -> timeout(task, generation), delay, TimeUnit.NANOSECONDS);
    }

    private void timeout(Task<?> task, long generation) {
        var signals = new ArrayList<Runnable>();
        synchronized (this) {
            if (task.finished || generation != task.timerGeneration) { return; }
            Throwable failure = task.waiting && !task.context.isExpired()
                    ? new QueueTimeoutException() : new DeadlineExceededException();
            finish(task, null, failure, true, signals);
        }
        publish(signals);
    }

    private <T> void complete(Task<T> task, T value, Throwable failure, boolean interrupt) {
        var signals = new ArrayList<Runnable>();
        synchronized (this) { finish(task, value, failure, interrupt, signals); }
        publish(signals);
    }

    private <T> void finish(Task<T> task, T value, Throwable failure, boolean interrupt, List<Runnable> signals) {
        if (task.finished) { return; }
        task.finished = true;
        if (task.timer != null) { task.timer.cancel(false); }
        if (failure instanceof QueueTimeoutException) {
            queueTimeouts++;
            metrics.rejected(task.series, DispatchMetrics.Reason.QUEUE_TIMEOUT);
        }
        if (metrics.enabled()) {
            metrics.request(task.series, outcome(task, value, failure), clock.getAsLong() - task.submittedAt);
        }
        if (task.waiting) {
            unqueue(task);
            tasks.remove(task);
            prune(task.bucket);
        }
        if (interrupt && task.runner != null) { task.runner.interrupt(); }
        signals.add(() -> {
            if (failure == null) { task.result.complete(value); }
            else { task.result.completeExceptionally(failure); }
        });
    }

    /** The status code that classifies a finished request, or the cancelled and unknown codes. */
    private static <T> int outcome(Task<T> task, T value, Throwable failure) {
        if (failure == null) {
            if (task.status == null) { return DispatchMetrics.UNKNOWN; }
            try { return task.status.applyAsInt(value); }
            catch (RuntimeException unreadable) { return DispatchMetrics.UNKNOWN; }
        }
        if (failure instanceof CancellationException) { return DispatchMetrics.CANCELLED; }
        if (failure instanceof DeadlineExceededException) { return 504; }
        if (failure instanceof QueueTimeoutException || failure instanceof DispatchRejectedException) { return 503; }
        return 500;
    }

    private void unqueue(Task<?> task) {
        var bucket = task.bucket;
        bucket.waiting.remove(task);
        queued--;
        metrics.queued(-1);
        if (metrics.enabled()) { metrics.queueWait(task.series, clock.getAsLong() - task.queuedAt); }
        if (bucket.waiting.isEmpty()) { queuedBuckets.remove(bucket); }
        task.waiting = false;
    }

    private void release(Task<?> task) {
        if (!task.reserved) { return; }
        task.reserved = false;
        active--;
        metrics.active(-1);
        task.bucket.active--;
        tasks.remove(task);
        prune(task.bucket);
    }

    private void prune(Bucket bucket) {
        if (bucket.active == 0 && bucket.waiting.isEmpty()) { buckets.remove(bucket.key, bucket); }
    }

    private void drain(List<Runnable> signals) {
        // Oldest eligible request wins. Only the head of each endpoint FIFO is a candidate, and
        // endpoints at their own limit are skipped, so cost is bounded by queued endpoints
        // rather than queued requests, and a blocked endpoint does not hold up unrelated work.
        while (!closed && !stopping && active < policy.maxActive()) {
            Task<?> head = null;
            for (var bucket : queuedBuckets) {
                if (bucket.active >= bucket.policy.maxActive()) { continue; }
                var candidate = bucket.waiting.iterator().next();
                if (head == null || candidate.sequence < head.sequence) { head = candidate; }
            }
            if (head == null) { return; }
            var expired = expiry(head, clock.getAsLong());
            if (expired != null) { finish(head, null, expired, false, signals); }
            else { activate(head, signals); }
        }
    }

    /**
     * Fails the expired heads of every endpoint queue, including endpoints at their own active
     * limit, which {@link #drain} skips. The queue budget is the same for every request of an
     * endpoint and arrival times are monotonic, so a queue-wait expiry always reaches the head
     * first: removing expired heads until a live one is found removes every expired wait. Cost
     * is bounded by queued endpoints plus the requests expired, never by queue length. A request
     * whose own execution deadline is shorter than the head's remains its timer's to expire.
     */
    private void expireStaleHeads(List<Runnable> signals) {
        if (queuedBuckets.isEmpty()) { return; }
        long now = clock.getAsLong();
        for (var bucket : List.copyOf(queuedBuckets)) {
            while (!bucket.waiting.isEmpty()) {
                var head = bucket.waiting.iterator().next();
                var expired = expiry(head, now);
                if (expired == null) { break; }
                finish(head, null, expired, false, signals);
            }
        }
    }

    private static Throwable expiry(Task<?> task, long now) {
        if (task.context.isExpired()) { return new DeadlineExceededException(); }
        if (now - task.queuedAt >= task.queueBudget) { return new QueueTimeoutException(); }
        return null;
    }

    private static void publish(List<Runnable> signals) { signals.forEach(Runnable::run); }

    private static final class Bucket {
        private final Object key;
        private final AdmissionPolicy policy;
        private int active;
        private final Set<Task<?>> waiting = new LinkedHashSet<>();
        private Bucket(Object key, AdmissionPolicy policy) { this.key = key; this.policy = policy; }
        private void enqueue(Task<?> task) { waiting.add(task); }
    }

    /** Runtime deadline expiry, distinct from application-thrown timeout exceptions. */
    public static final class DeadlineExceededException extends TimeoutException {
        private static final long serialVersionUID = 1L;
        private DeadlineExceededException() { super("Request deadline exceeded"); }
    }

    /** Executor or scheduler rejected framework-owned submission, or admission stopped while it waited. */
    public static final class DispatchRejectedException extends RejectedExecutionException {
        private static final long serialVersionUID = 1L;
        private DispatchRejectedException(Throwable cause) { super("Request submission rejected", cause); }
    }

    /** Bounded queue wait expired before execution started. */
    public static final class QueueTimeoutException extends TimeoutException {
        private static final long serialVersionUID = 1L;
        private QueueTimeoutException() { super("Request queue wait exceeded"); }
    }

    /**
     * A single request outcome and cancellation handle. Cancellation does not imply active code exited.
     * @param <T> result type
     */
    public final class Task<T> {
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
        private Thread runner;
        private boolean waiting;
        private boolean reserved;
        private boolean finished;

        private Task(ExecutionContext context, Callable<T> action, Bucket bucket,
                DispatchMetrics.Series series, ToIntFunction<? super T> status, long submittedAt) {
            this.context = context;
            this.action = action;
            this.bucket = bucket;
            this.series = series;
            this.status = status;
            this.submittedAt = submittedAt;
        }

        /**
         * Observes the single request outcome.
         * @return read-only completion stage
         */
        public CompletionStage<T> result() { return result.minimalCompletionStage(); }

        /** Cancels queued work immediately; running work retains capacity until it exits. */
        public void cancel() { complete(this, null, new CancellationException("Request cancelled"), true); }

        private void run() {
            T value = null;
            Throwable failure = null;
            Error fatal = null;
            try {
                synchronized (RequestDispatcher.this) {
                    if (finished) { return; }
                    runner = Thread.currentThread();
                }
                if (context.isExpired()) {
                    failure = new DeadlineExceededException();
                } else {
                    value = action.call();
                    if (context.isExpired()) { value = null; failure = new DeadlineExceededException(); }
                }
            } catch (Throwable thrown) {
                failure = context.isExpired() ? new DeadlineExceededException() : thrown;
                if (thrown instanceof Error error) { fatal = error; }
            } finally {
                exit(value, failure);
            }
            if (fatal != null) { throw fatal; }
        }

        /**
         * Runner-thread exit: outcome, permit release and drain happen in one critical section,
         * so a completion callback that submits the next request never sees this slot still held.
         */
        private void exit(T value, Throwable failure) {
            var signals = new ArrayList<Runnable>();
            synchronized (RequestDispatcher.this) {
                finish(this, value, failure, false, signals);
                runner = null;
                release(this);
                expireStaleHeads(signals);
                drain(signals);
            }
            publish(signals);
        }
    }
}
