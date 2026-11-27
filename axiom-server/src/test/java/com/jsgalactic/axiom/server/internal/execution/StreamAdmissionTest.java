package com.jsgalactic.axiom.server.internal.execution;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.jsgalactic.axiom.execution.AdmissionPolicy;
import com.jsgalactic.axiom.execution.ExecutionContext;
import com.jsgalactic.axiom.execution.StreamPolicy;
import com.jsgalactic.axiom.routing.Route;
import com.jsgalactic.axiom.server.internal.execution.RequestDispatcher.DeadlineExceededException;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Delayed;
import java.util.concurrent.Executors;
import java.util.concurrent.FutureTask;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/** Streams use their own aggregate and per-route slots, without occupying request capacity. */
class StreamAdmissionTest {
    private static final Route EVENTS = new Route("GET", "/events");
    private static final Route OTHER = new Route("GET", "/other");

    private static ExecutionContext context() { return ExecutionContext.create(Duration.ofHours(1)); }

    private static void await(CountDownLatch latch) throws InterruptedException {
        assertThat(latch.await(30, TimeUnit.SECONDS)).isTrue();
    }

    private static void stop(RequestDispatcher dispatcher) throws Exception {
        dispatcher.close();
        dispatcher.termination().toCompletableFuture().get(30, TimeUnit.SECONDS);
    }

    @Test void theAggregateBoundRejectsAnotherStreamButLeavesRequestCapacityFree() throws Exception {
        var dispatcher = new RequestDispatcher(AdmissionPolicy.reject(1), StreamPolicy.reject(1));
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        try {
            var held = dispatcher.submit(context(), () -> {
                var admitted = dispatcher.admitStream(EVENTS, StreamPolicy.reject(1));
                entered.countDown();
                release.await();
                return admitted;
            });
            await(entered);
            assertThat(dispatcher.activeStreams()).isEqualTo(1);
            // The request slot moved into the stream lane, so another request can be admitted.
            var refused = dispatcher.submit(context(), () -> dispatcher.admitStream(OTHER, StreamPolicy.reject(1)));
            assertThat(refused.result().toCompletableFuture().get(30, TimeUnit.SECONDS)).isFalse();
            assertThat(dispatcher.activeStreams()).isEqualTo(1);
            release.countDown();
            assertThat(held.result().toCompletableFuture().get(30, TimeUnit.SECONDS)).isTrue();
            var next = dispatcher.submit(context(), () -> dispatcher.admitStream(OTHER, StreamPolicy.reject(1)));
            assertThat(next.result().toCompletableFuture().get(30, TimeUnit.SECONDS)).isTrue();
        } finally {
            release.countDown();
            stop(dispatcher);
        }
    }

    @Test void aRouteBoundRefusesItsOwnStreamButNotAnotherRoutes() throws Exception {
        var dispatcher = new RequestDispatcher(AdmissionPolicy.reject(36), StreamPolicy.reject(36));
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        try {
            var held = dispatcher.submit(EVENTS, AdmissionPolicy.reject(36), context(), () -> {
                var admitted = dispatcher.admitStream(EVENTS, StreamPolicy.reject(1));
                entered.countDown();
                release.await();
                return admitted;
            });
            await(entered);
            assertThat(dispatcher.activeStreams(EVENTS)).isEqualTo(1);
            var sameRoute = dispatcher.submit(context(), () -> dispatcher.admitStream(EVENTS, StreamPolicy.reject(1)));
            assertThat(sameRoute.result().toCompletableFuture().get(30, TimeUnit.SECONDS)).isFalse();
            var otherEntered = new CountDownLatch(1);
            var otherRoute = dispatcher.submit(context(), () -> {
                var admitted = dispatcher.admitStream(OTHER, StreamPolicy.reject(1));
                otherEntered.countDown();
                release.await();
                return admitted;
            });
            await(otherEntered);
            assertThat(dispatcher.activeStreams(EVENTS)).isEqualTo(1);
            assertThat(dispatcher.activeStreams(OTHER)).isEqualTo(1);
            release.countDown();
            assertThat(otherRoute.result().toCompletableFuture().get(30, TimeUnit.SECONDS)).isTrue();
            assertThat(held.result().toCompletableFuture().get(30, TimeUnit.SECONDS)).isTrue();
            assertThat(dispatcher.activeStreams()).isZero();
        } finally {
            release.countDown();
            stop(dispatcher);
        }
    }

    @Test void aRefusalRecordsTheBoundedStreamCapacityReason() throws Exception {
        var metrics = new RecordingMetrics();
        var dispatcher = new RequestDispatcher(AdmissionPolicy.reject(36), StreamPolicy.reject(1), metrics);
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        try {
            dispatcher.submit(context(), () -> {
                dispatcher.admitStream(EVENTS, StreamPolicy.reject(1));
                entered.countDown();
                release.await();
                return null;
            });
            await(entered);
            dispatcher.submit(context(), () -> dispatcher.admitStream(OTHER, StreamPolicy.reject(1)))
                    .result().toCompletableFuture().get(30, TimeUnit.SECONDS);
            assertThat(metrics.count("axiom.admission.rejected", "method", "none", "route", "unmatched",
                    "reason", "stream_capacity")).isEqualTo(1);
        } finally {
            release.countDown();
            stop(dispatcher);
        }
    }

    @Test void cancellationReleasesTheStreamSlotWhenTheBodyExits() throws Exception {
        var dispatcher = new RequestDispatcher(AdmissionPolicy.reject(36), StreamPolicy.reject(1));
        var entered = new CountDownLatch(1);
        var interrupted = new CountDownLatch(1);
        try {
            var task = dispatcher.submit(context(), () -> {
                var admitted = dispatcher.admitStream(EVENTS, StreamPolicy.reject(1));
                entered.countDown();
                try { new CountDownLatch(1).await(); }
                catch (InterruptedException expected) { interrupted.countDown(); }
                return admitted;
            });
            await(entered);
            assertThat(dispatcher.activeStreams()).isEqualTo(1);
            task.cancel();
            await(interrupted);
            assertThatThrownBy(() -> task.result().toCompletableFuture().join())
                    .isInstanceOf(CompletionException.class).hasCauseInstanceOf(CancellationException.class);
            assertThat(dispatcher.activeStreams()).isZero();
            var next = dispatcher.submit(context(), () -> dispatcher.admitStream(EVENTS, StreamPolicy.reject(1)));
            assertThat(next.result().toCompletableFuture().get(30, TimeUnit.SECONDS)).isTrue();
        } finally {
            stop(dispatcher);
        }
    }

    @Test void closingTheDispatcherCutsAndReleasesOpenStreams() throws Exception {
        var dispatcher = new RequestDispatcher(AdmissionPolicy.reject(36), StreamPolicy.reject(1));
        var entered = new CountDownLatch(1);
        var interrupted = new CountDownLatch(1);
        try {
            var task = dispatcher.submit(context(), () -> {
                dispatcher.admitStream(EVENTS, StreamPolicy.reject(1));
                entered.countDown();
                try { new CountDownLatch(1).await(); }
                catch (InterruptedException expected) { interrupted.countDown(); throw expected; }
                return null;
            });
            await(entered);
            assertThat(dispatcher.activeStreams()).isEqualTo(1);
            dispatcher.close();
            await(interrupted);
            assertThatThrownBy(() -> task.result().toCompletableFuture().join())
                    .isInstanceOf(CompletionException.class).hasCauseInstanceOf(CancellationException.class);
            assertThat(dispatcher.activeStreams()).isZero();
            dispatcher.termination().toCompletableFuture().get(30, TimeUnit.SECONDS);
        } finally {
            stop(dispatcher);
        }
    }

    @Test void anExpiredStreamKeepsItsDeadlineAndReleasesItsSlot() throws Exception {
        var timers = new ManualTimers();
        var dispatcher = new RequestDispatcher(AdmissionPolicy.reject(36), StreamPolicy.reject(1),
                Executors.newVirtualThreadPerTaskExecutor(), timers, System::nanoTime);
        var entered = new CountDownLatch(1);
        var interrupted = new CountDownLatch(1);
        try {
            var task = dispatcher.submit(context(), () -> {
                dispatcher.admitStream(EVENTS, StreamPolicy.reject(1));
                entered.countDown();
                try { new CountDownLatch(1).await(); }
                catch (InterruptedException expected) { interrupted.countDown(); throw expected; }
                return null;
            });
            await(entered);
            timers.fire();
            await(interrupted);
            assertThatThrownBy(() -> task.result().toCompletableFuture().join())
                    .hasCauseInstanceOf(DeadlineExceededException.class);
            assertThat(dispatcher.activeStreams()).isZero();
        } finally {
            stop(dispatcher);
        }
    }

    private static final class ManualTimers extends ScheduledThreadPoolExecutor {
        private final ArrayDeque<Runnable> callbacks = new ArrayDeque<>();
        ManualTimers() { super(1); }
        @Override public ScheduledFuture<?> schedule(Runnable command, long delay, TimeUnit unit) {
            callbacks.addLast(command);
            return new ManualTimer();
        }
        void fire() { callbacks.removeFirst().run(); }
    }

    private static final class ManualTimer extends FutureTask<Void> implements ScheduledFuture<Void> {
        ManualTimer() { super(() -> null); }
        @Override public long getDelay(TimeUnit unit) { return 0; }
        @Override public int compareTo(Delayed other) { return 0; }
    }
}
