package com.jsgalactic.axiom.server.internal.execution;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.jsgalactic.axiom.execution.ExecutionContext;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.List;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Delayed;
import java.util.concurrent.Executors;
import java.util.concurrent.FutureTask;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

class RequestDispatcherTest {
    @Test void executesOnVirtualThreadAndStopsOwnedResources() throws Exception {
        var dispatcher = new RequestDispatcher(1);
        try {
            assertThat(dispatcher.submit(context(), () -> Thread.currentThread().isVirtual())
                    .result().toCompletableFuture().get(5, TimeUnit.SECONDS)).isTrue();
        } finally { stop(dispatcher); }
        assertThatThrownBy(() -> dispatcher.submit(context(), () -> null))
                .isInstanceOf(RejectedExecutionException.class);
    }

    @Test void timeoutBeforeStartSkipsUserCodeAndReleasesOnlyWhenWorkerExits() throws Exception {
        var workers = new ManualWorkers();
        var timers = new ManualTimers();
        var dispatcher = new RequestDispatcher(1, workers, timers);
        var called = new AtomicBoolean();
        try {
            var task = dispatcher.submit(context(), () -> { called.set(true); return "late"; });
            timers.fire();
            assertThatThrownBy(() -> task.result().toCompletableFuture().join()).hasCauseInstanceOf(TimeoutException.class);
            assertThatThrownBy(() -> dispatcher.submit(context(), () -> null)).isInstanceOf(RejectedExecutionException.class);
            workers.runNext();
            assertThat(called).isFalse();
            var next = dispatcher.submit(context(), () -> "next");
            workers.runNext();
            assertThat(next.result().toCompletableFuture().join()).isEqualTo("next");
        } finally { stop(dispatcher); }
    }

    @Test void completionAndTimeoutHaveOnlyOneOutcomeInEitherOrder() throws Exception {
        var workers = new ManualWorkers();
        var timers = new ManualTimers();
        var dispatcher = new RequestDispatcher(1, workers, timers);
        try {
            var success = dispatcher.submit(context(), () -> "success");
            workers.runNext();
            timers.fire(); // Simulate a timer callback already dequeued when cancellation happened.
            success.cancel();
            assertThat(success.result().toCompletableFuture().join()).isEqualTo("success");
            var timeout = dispatcher.submit(context(), () -> { timers.fire(); return "too late"; });
            workers.runNext();
            Thread.interrupted(); // Manual execution ran on this test's thread.
            assertThatThrownBy(() -> timeout.result().toCompletableFuture().join()).hasCauseInstanceOf(TimeoutException.class);
            var third = dispatcher.submit(context(), () -> "capacity returned");
            workers.runNext();
            assertThat(third.result().toCompletableFuture().join()).isEqualTo("capacity returned");
        } finally { stop(dispatcher); }
    }

    @Test void cancellationAndFailureReleaseCapacityExactlyOnce() throws Exception {
        var workers = new ManualWorkers();
        var timers = new ManualTimers();
        var dispatcher = new RequestDispatcher(1, workers, timers);
        try {
            var cancelled = dispatcher.submit(context(), () -> { throw new AssertionError("must not run"); });
            cancelled.cancel();
            cancelled.cancel();
            timers.fire();
            workers.runNext();
            assertThatThrownBy(() -> cancelled.result().toCompletableFuture().join())
                    .hasCauseInstanceOf(CancellationException.class);
            var failed = dispatcher.submit(context(), () -> { throw new IllegalArgumentException("failure"); });
            workers.runNext();
            assertThatThrownBy(() -> failed.result().toCompletableFuture().join())
                    .hasCauseInstanceOf(IllegalArgumentException.class);
            dispatcher.submit(context(), () -> "one slot");
            assertThatThrownBy(() -> dispatcher.submit(context(), () -> null)).isInstanceOf(RejectedExecutionException.class);
            workers.runNext();
        } finally { stop(dispatcher); }
    }

    @Test void failedWorkerSubmissionCancelsTimerAndReturnsCapacity() throws Exception {
        var workers = new ManualWorkers();
        var timers = new ManualTimers();
        var dispatcher = new RequestDispatcher(1, workers, timers);
        try {
            workers.reject = true;
            assertThatThrownBy(() -> dispatcher.submit(context(), () -> null)).isInstanceOf(RejectedExecutionException.class);
            assertThat(timers.last.isCancelled()).isTrue();
            workers.reject = false;
            var task = dispatcher.submit(context(), () -> "recovered");
            workers.runNext();
            assertThat(task.result().toCompletableFuture().join()).isEqualTo("recovered");
        } finally { stop(dispatcher); }
    }

    @Test void cancelledUncooperativeHandlerKeepsCapacityAndBlocksTermination() throws Exception {
        var entered = new CountDownLatch(1);
        var interrupted = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var timers = new ManualTimers();
        var dispatcher = new RequestDispatcher(1, Executors.newVirtualThreadPerTaskExecutor(), timers);
        try {
            var task = dispatcher.submit(context(), () -> {
                entered.countDown();
                for (;;) {
                    try { release.await(); break; }
                    catch (InterruptedException ignored) { interrupted.countDown(); }
                }
                return "late";
            });
            assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
            timers.fire();
            assertThat(interrupted.await(5, TimeUnit.SECONDS)).isTrue();
            assertThatThrownBy(() -> task.result().toCompletableFuture().join()).hasCauseInstanceOf(TimeoutException.class);
            assertThatThrownBy(() -> dispatcher.submit(context(), () -> null)).isInstanceOf(RejectedExecutionException.class);
            dispatcher.close();
            assertThat(dispatcher.termination().toCompletableFuture()).isNotDone();
            release.countDown();
        } finally { release.countDown(); stop(dispatcher); }
    }

    @Test void handlerMayCloseDispatcherWithoutWaitingForItself() throws Exception {
        var dispatcher = new RequestDispatcher(1);
        var returned = new CountDownLatch(1);
        try {
            var task = dispatcher.submit(context(), () -> { dispatcher.close(); returned.countDown(); return "closed"; });
            assertThat(returned.await(5, TimeUnit.SECONDS)).isTrue();
            assertThatThrownBy(() -> task.result().toCompletableFuture().join()).hasCauseInstanceOf(CancellationException.class);
        } finally { stop(dispatcher); }
    }

    @Test void completionRemovesRealScheduledTimeout() throws Exception {
        var timers = new ScheduledThreadPoolExecutor(1);
        timers.setRemoveOnCancelPolicy(true);
        var dispatcher = new RequestDispatcher(1, Executors.newVirtualThreadPerTaskExecutor(), timers);
        try {
            dispatcher.submit(context(), () -> "done").result().toCompletableFuture().get(5, TimeUnit.SECONDS);
            assertThat(timers.getQueue()).isEmpty();
        } finally { stop(dispatcher); }
    }

    @Test void simultaneousSubmittersCannotExceedCapacity() throws Exception {
        var dispatcher = new RequestDispatcher(1);
        var ready = new CountDownLatch(8);
        var start = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        try (var submitters = Executors.newVirtualThreadPerTaskExecutor()) {
            var submissions = new java.util.ArrayList<java.util.concurrent.Future<Boolean>>();
            for (int i = 0; i < 8; i++) {
                submissions.add(submitters.submit(() -> {
                    ready.countDown();
                    start.await();
                    try {
                        dispatcher.submit(context(), () -> { release.await(); return null; });
                        return true;
                    } catch (RejectedExecutionException expected) { return false; }
                }));
            }
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            int accepted = 0;
            for (var submission : submissions) {
                if (submission.get(5, TimeUnit.SECONDS)) { accepted++; }
            }
            assertThat(accepted).isEqualTo(1);
        } finally {
            start.countDown();
            release.countDown();
            stop(dispatcher);
        }
    }

    @Test void rejectedTimerSubmissionDoesNotLeakCapacityOrRunWork() throws Exception {
        var workers = new ManualWorkers();
        var timers = new ManualTimers();
        var dispatcher = new RequestDispatcher(1, workers, timers);
        try {
            timers.reject = true;
            assertThatThrownBy(() -> dispatcher.submit(context(), () -> null)).isInstanceOf(RejectedExecutionException.class);
            assertThat(workers.tasks).isEmpty();
            timers.reject = false;
            var task = dispatcher.submit(context(), () -> "recovered");
            workers.runNext();
            assertThat(task.result().toCompletableFuture().join()).isEqualTo("recovered");
        } finally { stop(dispatcher); }
    }
    @Test void aTimerArmedForTheOriginalDeadlineWaitsForAnExtendedOne() throws Exception {
        var workers = new ManualWorkers();
        var timers = new ManualTimers();
        var dispatcher = new RequestDispatcher(1, workers, timers);
        var context = context();
        var extended = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        try {
            var task = dispatcher.submit(context, () -> {
                context.extendDeadline(Duration.ofHours(2));
                extended.countDown();
                release.await();
                return "long";
            });
            var runner = Thread.ofVirtual().start(workers::runNext);
            assertThat(extended.await(30, TimeUnit.SECONDS)).isTrue();
            // The original timer fires while the deadline has moved: it arms itself again instead of failing the request.
            timers.fire();
            assertThat(task.result().toCompletableFuture().isDone()).isFalse();
            assertThat(timers.callbacks).hasSize(1);
            timers.fire();
            assertThat(task.result().toCompletableFuture().isDone()).isFalse();
            release.countDown();
            runner.join();
            assertThat(task.result().toCompletableFuture().join()).isEqualTo("long");
        } finally { release.countDown(); stop(dispatcher); }
    }

    private static ExecutionContext context() { return ExecutionContext.create(Duration.ofHours(1)); }
    private static void stop(RequestDispatcher dispatcher) throws Exception {
        dispatcher.close();
        dispatcher.termination().toCompletableFuture().get(5, TimeUnit.SECONDS);
    }

    private static final class ManualWorkers extends AbstractExecutorService {
        private final ArrayDeque<Runnable> tasks = new ArrayDeque<>();
        private volatile boolean closed;
        boolean reject;
        @Override public void execute(Runnable command) {
            if (reject || closed) { throw new RejectedExecutionException(); }
            tasks.addLast(command);
        }
        void runNext() { tasks.removeFirst().run(); }
        @Override public void shutdown() { closed = true; }
        @Override public List<Runnable> shutdownNow() { closed = true; return List.of(); }
        @Override public boolean isShutdown() { return closed; }
        @Override public boolean isTerminated() { return closed; }
        @Override public boolean awaitTermination(long timeout, TimeUnit unit) { return closed; }
    }

    private static final class ManualTimers extends ScheduledThreadPoolExecutor {
        private final ArrayDeque<Runnable> callbacks = new ArrayDeque<>();
        private ScheduledFuture<?> last;
        boolean reject;
        ManualTimers() { super(1); }
        @Override public ScheduledFuture<?> schedule(Runnable command, long delay, TimeUnit unit) {
            if (reject) { throw new RejectedExecutionException(); }
            callbacks.addLast(command);
            last = new ManualTimer();
            return last;
        }
        void fire() { callbacks.removeFirst().run(); }
    }

    private static final class ManualTimer extends FutureTask<Void> implements ScheduledFuture<Void> {
        ManualTimer() { super(() -> null); }
        @Override public long getDelay(TimeUnit unit) { return 0; }
        @Override public int compareTo(Delayed other) { return 0; }
    }
}
