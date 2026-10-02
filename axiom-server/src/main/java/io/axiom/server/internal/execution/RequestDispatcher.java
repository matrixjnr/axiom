package io.axiom.server.internal.execution;

import io.axiom.execution.ExecutionContext;
import java.util.concurrent.Callable;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.TimeUnit;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;

/** Protocol-neutral, listener-owned execution; internal to Axiom's runtime modules. */
public final class RequestDispatcher implements AutoCloseable {
    private final int capacity;
    private final ExecutorService workers;
    private final ScheduledExecutorService deadlines;
    private final Set<Task<?>> tasks = new HashSet<>();
    private final CompletableFuture<Void> stopped = new CompletableFuture<>();
    private boolean closed;

    /**
     * Creates virtual-thread execution with admission before thread creation and no waiting queue.
     * @param capacity maximum submitted tasks, including cancelled tasks whose code has not exited
     */
    public RequestDispatcher(int capacity) {
        this(capacity, Executors.newThreadPerTaskExecutor(
                Thread.ofVirtual().name("axiom-handler-", 0).factory()), scheduler());
    }

    RequestDispatcher(int capacity, ExecutorService workers, ScheduledExecutorService deadlines) {
        if (capacity < 1) { throw new IllegalArgumentException("Capacity must be positive"); }
        this.capacity = capacity;
        this.workers = workers;
        this.deadlines = deadlines;
    }

    private static ScheduledExecutorService scheduler() {
        var scheduler = new ScheduledThreadPoolExecutor(1,
                Thread.ofPlatform().name("axiom-deadline-", 0).factory());
        scheduler.setRemoveOnCancelPolicy(true);
        scheduler.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);
        return scheduler;
    }

    /**
     * Admits work, including response preparation, under a monotonic deadline.
     * @param context request identity and remaining budget
     * @param action framework-owned work; no transport types are needed here
     * @param <T> result type
     * @return cancellation handle with a single observable outcome
     * @throws RejectedExecutionException if closed or at capacity
     */
    public synchronized <T> Task<T> submit(ExecutionContext context, Callable<T> action) {
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(action, "action");
        if (closed || tasks.size() >= capacity) { throw new RejectedExecutionException("Request capacity unavailable"); }
        var task = new Task<T>(context, action);
        tasks.add(task);
        try {
            task.timer(deadlines.schedule(task::expire, context.remainingTime().toNanos(), TimeUnit.NANOSECONDS));
            workers.execute(task::run);
        } catch (RuntimeException | Error failure) {
            task.finish(null, failure, true);
            tasks.remove(task); // Submission failed, so no worker owns release.
            throw failure;
        }
        return task;
    }

    /**
     * Observes full dispatcher termination, including handlers that ignore cancellation.
     * @return completion after executor resources stop
     */
    public CompletionStage<Void> termination() { return stopped.minimalCompletionStage(); }

    /** Stops admission, cancels work and starts joining owned executors without blocking the caller. */
    @Override public void close() {
        Set<Task<?>> owned;
        synchronized (this) {
            if (closed) { return; }
            closed = true;
            owned = Set.copyOf(tasks);
        }
        owned.forEach(Task::cancel);
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

    private synchronized void release(Task<?> task) { tasks.remove(task); }

    /** Runtime deadline expiry, distinct from a TimeoutException thrown by application code. */
    public static final class DeadlineExceededException extends TimeoutException {
        private static final long serialVersionUID = 1L;
        private DeadlineExceededException() { super("Request deadline exceeded"); }
    }

    /**
     * A request's outcome and cancellation handle. Completion does not imply its code has exited.
     * @param <T> result type
     */
    public final class Task<T> {
        private final ExecutionContext context;
        private final Callable<T> action;
        private final CompletableFuture<T> result = new CompletableFuture<>();
        private ScheduledFuture<?> timer;
        private Thread runner;
        private boolean finished;

        private Task(ExecutionContext context, Callable<T> action) {
            this.context = context;
            this.action = action;
        }

        /**
         * Observes the single request outcome.
         * @return read-only observation of the request outcome
         */
        public CompletionStage<T> result() { return result.minimalCompletionStage(); }

        /** Cancels the outcome and interrupts execution, retaining capacity until the worker exits. */
        public void cancel() { finish(null, new CancellationException("Request cancelled"), true); }

        private void expire() { finish(null, new DeadlineExceededException(), true); }

        private synchronized void timer(ScheduledFuture<?> value) {
            timer = value;
            if (finished) { value.cancel(false); }
        }

        private void finish(T value, Throwable failure, boolean interrupt) {
            synchronized (this) {
                if (finished) { return; }
                finished = true;
                if (timer != null) { timer.cancel(false); }
                if (interrupt && runner != null) { runner.interrupt(); }
            }
            // No runtime lock is held while a completion callback runs.
            if (failure == null) { result.complete(value); }
            else { result.completeExceptionally(failure); }
        }

        private void run() {
            try {
                synchronized (this) {
                    if (finished) { return; }
                    runner = Thread.currentThread();
                }
                if (context.isExpired()) { expire(); return; }
                var value = action.call();
                if (context.isExpired()) { expire(); }
                else { finish(value, null, false); }
            } catch (Throwable failure) {
                if (context.isExpired()) { expire(); }
                else { finish(null, failure, false); }
                if (failure instanceof Error error) { throw error; }
            } finally {
                synchronized (this) { runner = null; }
                release(this);
            }
        }
    }
}
