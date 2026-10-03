package com.jsgalactic.axiom.server.internal.execution;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.jsgalactic.axiom.execution.AdmissionPolicy;
import com.jsgalactic.axiom.execution.ExecutionContext;
import java.time.Duration;
import java.util.ArrayList;
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
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

class AdmissionQueueTest {
    @Test void boundsActiveAndQueuedCountsAndPreservesFifo() {
        try (var f = new Fixture(policy(1, 2))) {
            var order = new ArrayList<Integer>();
            f.dispatcher.submit(context(), () -> order.add(1));
            var second = f.dispatcher.submit(context(), () -> order.add(2));
            f.dispatcher.submit(context(), () -> order.add(3));
            assertThat(f.workers.tasks).hasSize(1);
            assertThat(f.dispatcher.snapshot().active()).isEqualTo(1);
            assertThat(f.dispatcher.snapshot().queued()).isEqualTo(2);
            assertThatThrownBy(() -> f.dispatcher.submit(context(), () -> null)).isInstanceOf(RejectedExecutionException.class);
            assertThat(f.dispatcher.snapshot().rejected()).isEqualTo(1);
            f.workers.run(0);
            f.timers.fire(1); // Stale queue timer after second request was promoted.
            assertThat(second.result().toCompletableFuture()).isNotDone();
            f.workers.run(0);
            f.workers.run(0);
            assertThat(order).containsExactly(1, 2, 3);
            assertThat(f.dispatcher.snapshot().active()).isZero();
            assertThat(f.dispatcher.snapshot().queued()).isZero();
            assertThat(f.dispatcher.snapshot().accepted()).isEqualTo(3);
            assertThat(f.dispatcher.snapshot().queueTimeouts()).isZero();
        }
    }

    @Test void releasesCapacityBeforeCompletionCallbacksRun() {
        try (var f = new Fixture(com.jsgalactic.axiom.execution.AdmissionPolicy.reject(1))) {
            var followUp = new java.util.concurrent.atomic.AtomicReference<Object>();
            var first = f.dispatcher.submit(context(), () -> "first");
            first.result().thenAccept(v -> {
                try { followUp.set(f.dispatcher.submit(context(), () -> "second")); }
                catch (RuntimeException rejected) { followUp.set(rejected); }
            });
            f.workers.run(0);
            assertThat(followUp.get()).isInstanceOf(RequestDispatcher.Task.class);
            f.workers.run(0);
            assertThat(f.dispatcher.snapshot().rejected()).isZero();
            assertThat(f.dispatcher.snapshot().active()).isZero();
        }
    }

    @Test void skipsBlockedRoutesWithoutViolatingTheirLimits() {
        try (var f = new Fixture(policy(2, 4))) {
            var route = policy(1, 2);
            var order = new ArrayList<String>();
            f.dispatcher.submit("a", route, context(), () -> order.add("a1"));
            f.dispatcher.submit("a", route, context(), () -> order.add("a2"));
            f.dispatcher.submit("b", route, context(), () -> order.add("b1"));
            f.dispatcher.submit("b", route, context(), () -> order.add("b2"));
            f.workers.run(1); // b1 frees a global slot; a remains occupied.
            assertThat(f.dispatcher.snapshot().active()).isEqualTo(2);
            assertThat(f.dispatcher.snapshot().queued()).isEqualTo(1);
            f.workers.run(1);
            f.workers.run(0);
            f.workers.run(0);
            assertThat(order).containsExactly("b1", "b2", "a1", "a2");
        }
    }

    @Test void enforcesRouteQueueBoundEvenWhenAggregateQueueHasRoom() {
        try (var f = new Fixture(policy(2, 5))) {
            var route = policy(1, 1);
            f.dispatcher.submit("a", route, context(), () -> null);
            f.dispatcher.submit("a", route, context(), () -> null);
            assertThatThrownBy(() -> f.dispatcher.submit("a", route, context(), () -> null))
                    .isInstanceOf(RejectedExecutionException.class);
            f.dispatcher.submit("b", route, context(), () -> null);
            assertThat(f.dispatcher.snapshot().active()).isEqualTo(2);
            assertThat(f.dispatcher.snapshot().queued()).isEqualTo(1);
        }
    }

    @Test void eitherPolicyCanDisableQueuing() {
        try (var f = new Fixture(policy(1, 2))) {
            f.dispatcher.submit("a", AdmissionPolicy.reject(1), context(), () -> null);
            assertThatThrownBy(() -> f.dispatcher.submit("a", AdmissionPolicy.reject(1), context(), () -> null))
                    .isInstanceOf(RejectedExecutionException.class);
        }
        try (var f = new Fixture(AdmissionPolicy.reject(1))) {
            f.dispatcher.submit("a", policy(1, 2), context(), () -> null);
            assertThatThrownBy(() -> f.dispatcher.submit("a", policy(1, 2), context(), () -> null))
                    .isInstanceOf(RejectedExecutionException.class);
        }
    }

    @Test void queueTimeoutRemovesWorkWithoutCreatingAThread() {
        try (var f = new Fixture(policy(1, 1))) {
            f.dispatcher.submit(context(), () -> null);
            var expired = f.dispatcher.submit(context(), () -> { throw new AssertionError("must not run"); });
            f.timers.fire(1);
            f.timers.fire(1);
            expired.cancel();
            assertThatThrownBy(() -> expired.result().toCompletableFuture().join())
                    .hasCauseInstanceOf(RequestDispatcher.QueueTimeoutException.class);
            assertThat(f.dispatcher.snapshot().queued()).isZero();
            assertThat(f.dispatcher.snapshot().queueTimeouts()).isEqualTo(1);
            assertThat(f.workers.tasks).hasSize(1);
            f.dispatcher.submit(context(), () -> "replacement");
            assertThat(f.dispatcher.snapshot().queued()).isEqualTo(1);
        }
    }

    @Test void delayedQueueTimerCannotPromoteAnExpiredWait() {
        try (var f = new Fixture(policy(1, 1))) {
            f.dispatcher.submit(context(), () -> null);
            var expired = f.dispatcher.submit(context(), () -> { throw new AssertionError("must not run"); });
            f.clock.set(Duration.ofSeconds(5).toNanos());
            f.workers.run(0);
            assertThat(f.workers.tasks).isEmpty();
            assertThatThrownBy(() -> expired.result().toCompletableFuture().join())
                    .hasCauseInstanceOf(RequestDispatcher.QueueTimeoutException.class);
            assertThat(f.dispatcher.snapshot().active()).isZero();
        }
    }

    @Test void completionExpiresStaleWaitsBehindABlockedEndpointWithoutItsTimer() {
        try (var f = new Fixture(policy(2, 4))) {
            var route = policy(1, 2);
            f.dispatcher.submit("a", route, context(), () -> "a1");
            var stale = f.dispatcher.submit("a", route, context(), () -> { throw new AssertionError("must not run"); });
            var alsoStale = f.dispatcher.submit("a", route, context(), () -> { throw new AssertionError("must not run"); });
            f.dispatcher.submit("b", route, context(), () -> "b1");
            f.clock.set(Duration.ofSeconds(5).toNanos());
            // No timer fires: the queue timers are late. Endpoint a stays at its own active limit.
            f.workers.run(1);
            assertThat(stale.result().toCompletableFuture()).isCompletedExceptionally();
            assertThatThrownBy(() -> stale.result().toCompletableFuture().join())
                    .hasCauseInstanceOf(RequestDispatcher.QueueTimeoutException.class);
            assertThat(alsoStale.result().toCompletableFuture()).isCompletedExceptionally();
            assertThatThrownBy(() -> alsoStale.result().toCompletableFuture().join())
                    .hasCauseInstanceOf(RequestDispatcher.QueueTimeoutException.class);
            assertThat(f.dispatcher.snapshot().queued()).isZero();
            assertThat(f.dispatcher.snapshot().queueTimeouts()).isEqualTo(2);
            assertThat(f.dispatcher.snapshot().active()).isEqualTo(1);
        }
    }

    @Test void completionExpiresOnlyStaleHeadsAndKeepsLiveWaits() {
        try (var f = new Fixture(policy(2, 4))) {
            var route = policy(1, 3);
            f.dispatcher.submit("a", route, context(), () -> "a1");
            var stale = f.dispatcher.submit("a", route, context(), () -> null);
            f.clock.set(Duration.ofSeconds(3).toNanos());
            var live = f.dispatcher.submit("a", route, context(), () -> "live");
            f.dispatcher.submit("b", route, context(), () -> "b1");
            f.clock.set(Duration.ofSeconds(6).toNanos());
            f.workers.run(1);
            assertThat(stale.result().toCompletableFuture()).isCompletedExceptionally();
            assertThatThrownBy(() -> stale.result().toCompletableFuture().join())
                    .hasCauseInstanceOf(RequestDispatcher.QueueTimeoutException.class);
            assertThat(live.result().toCompletableFuture()).isNotDone();
            assertThat(f.dispatcher.snapshot().queued()).isEqualTo(1);
            f.workers.run(0);
            f.workers.run(0);
            assertThat(live.result().toCompletableFuture().join()).isEqualTo("live");
        }
    }

    @Test void submissionReclaimsQueueSlotsHeldByExpiredWaits() {
        try (var f = new Fixture(policy(1, 1))) {
            f.dispatcher.submit(context(), () -> null);
            var stale = f.dispatcher.submit(context(), () -> { throw new AssertionError("must not run"); });
            f.clock.set(Duration.ofSeconds(5).toNanos());
            var next = f.dispatcher.submit(context(), () -> "next");
            assertThat(stale.result().toCompletableFuture()).isCompletedExceptionally();
            assertThatThrownBy(() -> stale.result().toCompletableFuture().join())
                    .hasCauseInstanceOf(RequestDispatcher.QueueTimeoutException.class);
            assertThat(f.dispatcher.snapshot().rejected()).isZero();
            assertThat(f.dispatcher.snapshot().queued()).isEqualTo(1);
            f.workers.run(0);
            f.workers.run(0);
            assertThat(next.result().toCompletableFuture().join()).isEqualTo("next");
        }
    }

    @Test void queuedCancellationImmediatelyReturnsQueueCapacity() {
        try (var f = new Fixture(policy(1, 1))) {
            f.dispatcher.submit(context(), () -> null);
            var cancelled = f.dispatcher.submit(context(), () -> { throw new AssertionError("must not run"); });
            cancelled.cancel();
            cancelled.cancel();
            var replacement = f.dispatcher.submit(context(), () -> "replacement");
            f.timers.fire(1);
            assertThat(f.dispatcher.snapshot().queued()).isEqualTo(1);
            assertThat(f.dispatcher.snapshot().queueTimeouts()).isZero();
            assertThatThrownBy(() -> cancelled.result().toCompletableFuture().join()).hasCauseInstanceOf(CancellationException.class);
            f.workers.run(0);
            f.workers.run(0);
            assertThat(replacement.result().toCompletableFuture().join()).isEqualTo("replacement");
        }
    }

    @Test void promotionSubmissionFailureReleasesCapacityAndContinuesDraining() {
        try (var f = new Fixture(policy(1, 2))) {
            f.dispatcher.submit(context(), () -> null);
            var failed = f.dispatcher.submit(context(), () -> "never");
            var next = f.dispatcher.submit(context(), () -> "next");
            f.workers.failures = 1;
            f.workers.run(0);
            assertThatThrownBy(() -> failed.result().toCompletableFuture().join())
                    .hasCauseInstanceOf(RequestDispatcher.DispatchRejectedException.class);
            assertThat(f.dispatcher.snapshot().active()).isEqualTo(1);
            assertThat(f.dispatcher.snapshot().queued()).isZero();
            f.workers.run(0);
            assertThat(next.result().toCompletableFuture().join()).isEqualTo("next");
        }
    }

    @Test void usesTheTighterQueueBudget() {
        try (var f = new Fixture(policy(1, 2))) {
            f.dispatcher.submit("a", policy(1, 2), context(), () -> null);
            f.dispatcher.submit("b", new AdmissionPolicy(1, 1, Duration.ofSeconds(2)), context(), () -> null);
            assertThat(f.timers.delays.get(1)).isEqualTo(Duration.ofSeconds(2).toNanos());
        }
    }

    @Test void globalQueueTimeoutIsTheTighterBudgetWhenItIsSmaller() {
        var global = new AdmissionPolicy(1, 2, Duration.ofSeconds(1));
        try (var f = new Fixture(global)) {
            f.dispatcher.submit("a", global, context(), () -> null);
            f.dispatcher.submit("b", policy(1, 2), context(), () -> null);
            assertThat(f.timers.delays.get(1)).isEqualTo(Duration.ofSeconds(1).toNanos());
        }
    }

    @Test void executorRejectionAtSubmissionIsCountedAsRejected() {
        try (var f = new Fixture(policy(1, 1))) {
            f.workers.failures = 1;
            assertThatThrownBy(() -> f.dispatcher.submit(context(), () -> null))
                    .isInstanceOf(RejectedExecutionException.class);
            assertThat(f.dispatcher.snapshot().rejected()).isEqualTo(1);
            assertThat(f.dispatcher.snapshot().accepted()).isZero();
            assertThat(f.dispatcher.snapshot().active()).isZero();
        }
    }

    @Test void conflictingEndpointPolicyIsACallerErrorNotARejection() {
        try (var f = new Fixture(policy(2, 2))) {
            f.dispatcher.submit("a", policy(1, 1), context(), () -> null);
            assertThatThrownBy(() -> f.dispatcher.submit("a", policy(2, 1), context(), () -> null))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThat(f.dispatcher.snapshot().rejected()).isZero();
        }
    }

    @Test void promotionDoesNotScanBlockedWorkAndKeepsArrivalOrderAcrossRoutes() {
        try (var f = new Fixture(policy(2, 100))) {
            var route = policy(1, 50);
            var order = new ArrayList<String>();
            f.dispatcher.submit("a", route, context(), () -> order.add("a0"));
            f.dispatcher.submit("b", route, context(), () -> order.add("b0"));
            for (int i = 1; i <= 20; i++) {
                int n = i;
                f.dispatcher.submit("a", route, context(), () -> order.add("a" + n));
            }
            f.dispatcher.submit("b", route, context(), () -> order.add("b1"));
            f.workers.run(1); // b0 done: a is blocked, so b1 jumps the older a-work.
            assertThat(f.dispatcher.snapshot().queued()).isEqualTo(20);
            f.workers.run(1);
            assertThat(order).containsExactly("b0", "b1");
        }
    }

    @Test void callbacksCanReadAdmissionFromAnotherThreadWithoutDeadlock() {
        try (var f = new Fixture(policy(1, 1))) {
            f.dispatcher.submit(context(), () -> null);
            var queued = f.dispatcher.submit(context(), () -> null);
            var callback = queued.result().handle((value, failure) -> {
                try (var observer = Executors.newVirtualThreadPerTaskExecutor()) {
                    return observer.submit(f.dispatcher::snapshot).get(5, TimeUnit.SECONDS);
                } catch (Exception problem) { throw new AssertionError(problem); }
            });
            queued.cancel();
            assertThat(callback.toCompletableFuture().join().queued()).isZero();
        }
    }

    @Test void shutdownCancelsQueuedWorkAndPreventsPromotion() {
        try (var f = new Fixture(policy(1, 1))) {
            var ran = new AtomicBoolean();
            f.dispatcher.submit(context(), () -> { ran.set(true); return null; });
            var queued = f.dispatcher.submit(context(), () -> { ran.set(true); return null; });
            f.dispatcher.close();
            assertThat(f.dispatcher.snapshot().queued()).isZero();
            assertThatThrownBy(() -> queued.result().toCompletableFuture().join()).hasCauseInstanceOf(CancellationException.class);
            f.workers.run(0);
            assertThat(f.dispatcher.snapshot().active()).isZero();
            assertThat(f.workers.tasks).isEmpty();
            assertThat(ran).isFalse();
        }
    }

    @Test void stopAdmissionFailsQueuedWorkAndLetsActiveWorkFinish() {
        try (var f = new Fixture(policy(1, 2))) {
            var ran = new AtomicBoolean();
            var active = f.dispatcher.submit(context(), () -> "active");
            var queued = f.dispatcher.submit(context(), () -> { ran.set(true); return null; });
            f.dispatcher.stopAdmission();
            assertThat(f.dispatcher.snapshot().queued()).isZero();
            assertThatThrownBy(() -> queued.result().toCompletableFuture().join())
                    .hasCauseInstanceOf(RequestDispatcher.DispatchRejectedException.class);
            assertThatThrownBy(() -> f.dispatcher.submit(context(), () -> null))
                    .isInstanceOf(RejectedExecutionException.class);
            assertThat(f.dispatcher.snapshot().rejected()).isEqualTo(1);
            f.workers.run(0);
            assertThat(active.result().toCompletableFuture().join()).isEqualTo("active");
            assertThat(f.dispatcher.snapshot().active()).isZero();
            assertThat(f.workers.tasks).isEmpty();
            assertThat(ran).isFalse();
        }
    }

    @Test void ignoredInterruptCannotPromoteQueuedWorkPrematurely() throws Exception {
        var entered = new CountDownLatch(1);
        var interrupted = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var dispatcher = new RequestDispatcher(policy(1, 1));
        try {
            var active = dispatcher.submit(context(), () -> {
                entered.countDown();
                for (;;) {
                    try { release.await(); return "late"; }
                    catch (InterruptedException ignored) { interrupted.countDown(); }
                }
            });
            assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
            var next = dispatcher.submit(context(), () -> "next");
            active.cancel();
            assertThat(interrupted.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(dispatcher.snapshot().active()).isEqualTo(1);
            assertThat(dispatcher.snapshot().queued()).isEqualTo(1);
            assertThat(next.result().toCompletableFuture()).isNotDone();
            release.countDown();
            assertThat(next.result().toCompletableFuture().get(5, TimeUnit.SECONDS)).isEqualTo("next");
        } finally {
            release.countDown();
            dispatcher.close();
            dispatcher.termination().toCompletableFuture().get(5, TimeUnit.SECONDS);
        }
    }

    @Test void schedulerRejectionOnPromotionCleansQueueAndContinues() {
        try (var f = new Fixture(policy(1, 2))) {
            f.dispatcher.submit(context(), () -> null);
            var failed = f.dispatcher.submit(context(), () -> "never");
            var next = f.dispatcher.submit(context(), () -> "next");
            f.timers.failures = 1;
            f.workers.run(0);
            assertThatThrownBy(() -> failed.result().toCompletableFuture().join())
                    .hasCauseInstanceOf(RequestDispatcher.DispatchRejectedException.class);
            f.workers.run(0);
            assertThat(next.result().toCompletableFuture().join()).isEqualTo("next");
            assertThat(f.dispatcher.snapshot().active()).isZero();
            assertThat(f.dispatcher.snapshot().queued()).isZero();
        }
    }

    @Test void fatalSubmissionFailureReturnsTheReservedSlot() {
        try (var f = new Fixture(policy(1, 1))) {
            f.workers.fatal = true;
            assertThatThrownBy(() -> f.dispatcher.submit(context(), () -> null)).isInstanceOf(AssertionError.class);
            assertThat(f.dispatcher.snapshot().active()).isZero();
            assertThat(f.dispatcher.snapshot().queued()).isZero();
            var next = f.dispatcher.submit(context(), () -> "recovered");
            f.workers.run(0);
            assertThat(next.result().toCompletableFuture().join()).isEqualTo("recovered");
        }
    }
    private static AdmissionPolicy policy(int active, int queued) {
        return new AdmissionPolicy(active, queued, Duration.ofSeconds(5));
    }
    private static ExecutionContext context() { return ExecutionContext.create(Duration.ofHours(1)); }

    private static final class Fixture implements AutoCloseable {
        final ManualWorkers workers = new ManualWorkers();
        final ManualTimers timers = new ManualTimers();
        final AtomicLong clock = new AtomicLong();
        final RequestDispatcher dispatcher;
        Fixture(AdmissionPolicy policy) { dispatcher = new RequestDispatcher(policy, workers, timers, clock::get); }
        @Override public void close() {
            dispatcher.close();
            while (!workers.tasks.isEmpty()) { workers.run(0); }
            dispatcher.termination().toCompletableFuture().orTimeout(5, TimeUnit.SECONDS).join();
        }
    }

    private static final class ManualWorkers extends AbstractExecutorService {
        final List<Runnable> tasks = new ArrayList<>();
        volatile boolean closed;
        int failures;
        boolean fatal;
        @Override public void execute(Runnable command) {
            if (fatal) { fatal = false; throw new AssertionError("submission failed"); }
            if (closed || failures-- > 0) { throw new RejectedExecutionException(); }
            tasks.add(command);
        }
        void run(int index) { tasks.remove(index).run(); }
        @Override public void shutdown() { closed = true; }
        @Override public List<Runnable> shutdownNow() { closed = true; return List.of(); }
        @Override public boolean isShutdown() { return closed; }
        @Override public boolean isTerminated() { return closed; }
        @Override public boolean awaitTermination(long timeout, TimeUnit unit) { return closed; }
    }

    private static final class ManualTimers extends ScheduledThreadPoolExecutor {
        final List<Runnable> callbacks = new ArrayList<>();
        final List<Long> delays = new ArrayList<>();
        int failures;
        ManualTimers() { super(1); }
        @Override public ScheduledFuture<?> schedule(Runnable command, long delay, TimeUnit unit) {
            if (failures-- > 0) { throw new RejectedExecutionException(); }
            callbacks.add(command);
            delays.add(unit.toNanos(delay));
            return new Timer();
        }
        void fire(int index) { callbacks.get(index).run(); }
    }
    private static final class Timer extends FutureTask<Void> implements ScheduledFuture<Void> {
        Timer() { super(() -> null); }
        @Override public long getDelay(TimeUnit unit) { return 0; }
        @Override public int compareTo(Delayed other) { return 0; }
    }
}
