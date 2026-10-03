package com.jsgalactic.axiom.server.internal.execution;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.jsgalactic.axiom.execution.AdmissionPolicy;
import com.jsgalactic.axiom.execution.ExecutionContext;
import com.jsgalactic.axiom.observability.Metrics;
import com.jsgalactic.axiom.routing.Route;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Delayed;
import java.util.concurrent.FutureTask;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

class DispatchMetricsTest {
    private static final Route USERS = new Route("GET", "/users/:id");
    private static final String[] USERS_TAGS = {"method", "GET", "route", "/users/:id"};

    @Test void countsRequestsByTemplateAndStatusClassAndTimesThemWithTheDispatcherClock() {
        try (var f = new Fixture(AdmissionPolicy.reject(4))) {
            var ok = f.dispatcher.submit(USERS, AdmissionPolicy.reject(4), context(), () -> 200, status -> status);
            f.clock.set(Duration.ofMillis(30).toNanos());
            f.workers.run(0);
            f.dispatcher.submit(USERS, AdmissionPolicy.reject(4), context(), () -> 404, status -> status);
            f.workers.run(0);
            f.dispatcher.submit(USERS, AdmissionPolicy.reject(4), context(), () -> 301, status -> status);
            f.workers.run(0);
            f.dispatcher.submit(USERS, AdmissionPolicy.reject(4), context(), () -> 200, null);
            f.workers.run(0);
            f.dispatcher.<Integer>submit(USERS, AdmissionPolicy.reject(4), context(), () -> { throw new IllegalStateException(); },
                    status -> 200);
            f.workers.run(0);

            assertThat(ok.result().toCompletableFuture()).isCompletedWithValue(200);
            assertThat(f.metrics.count("axiom.http.requests", tags("2xx"))).isEqualTo(1);
            assertThat(f.metrics.count("axiom.http.requests", tags("4xx"))).isEqualTo(1);
            assertThat(f.metrics.count("axiom.http.requests", tags("3xx"))).isEqualTo(1);
            assertThat(f.metrics.count("axiom.http.requests", tags("unknown"))).isEqualTo(1);
            assertThat(f.metrics.count("axiom.http.requests", tags("5xx"))).isEqualTo(1);
            assertThat(f.metrics.observations("axiom.http.request.duration", USERS_TAGS))
                    .hasSize(5).first().isEqualTo(Duration.ofMillis(30).toNanos());
        }
    }

    @Test void routeKeysAreTemplatesAndOtherKeysNeverBecomeTags() {
        try (var f = new Fixture(AdmissionPolicy.reject(4))) {
            f.dispatcher.submit("/users/12345?token=secret", AdmissionPolicy.reject(4), context(), () -> 200, s -> s);
            f.workers.run(0);
            assertThat(f.metrics.counters.keySet()).containsExactly(
                    "axiom.http.requests{method=none,route=unmatched,status_class=2xx}");
        }
    }

    @Test void endpointSeriesAreBoundedAndLaterEndpointsShareOneOtherSeries() {
        try (var f = new Fixture(AdmissionPolicy.reject(1))) {
            int endpoints = DispatchMetrics.MAX_ENDPOINTS + 25;
            for (int i = 0; i < endpoints; i++) {
                f.dispatcher.submit(new Route("GET", "/r" + i), AdmissionPolicy.reject(1), context(), () -> 200, s -> s);
                f.workers.run(0);
            }
            assertThat(f.metrics.counters).hasSize(DispatchMetrics.MAX_ENDPOINTS + 1);
            assertThat(f.metrics.count("axiom.http.requests", "method", "other", "route", "other",
                    "status_class", "2xx")).isEqualTo(25);
        }
    }

    @Test void capacityRefusalsCountAsRejectedAndAsServiceUnavailable() {
        try (var f = new Fixture(AdmissionPolicy.reject(1))) {
            f.dispatcher.submit(USERS, AdmissionPolicy.reject(1), context(), () -> 200, s -> s);
            assertThatThrownBy(() -> f.dispatcher.submit(USERS, AdmissionPolicy.reject(1), context(), () -> 200, s -> s))
                    .isInstanceOf(RejectedExecutionException.class);
            assertThat(f.metrics.count("axiom.admission.rejected", "method", "GET", "route", "/users/:id",
                    "reason", "capacity")).isEqualTo(1);
            assertThat(f.metrics.count("axiom.http.requests", tags("5xx"))).isEqualTo(1);
            assertThat(f.metrics.level("axiom.admission.active")).isEqualTo(1);
            assertThat(f.dispatcher.snapshot().rejected()).isEqualTo(1);
            f.workers.run(0);
            assertThat(f.metrics.level("axiom.admission.active")).isZero();
        }
    }

    @Test void stoppedAdmissionRefusesNewWorkAndFailsQueuedWorkAsShutdown() {
        var policy = new AdmissionPolicy(1, 2, Duration.ofSeconds(5));
        try (var f = new Fixture(policy)) {
            f.dispatcher.submit(USERS, policy, context(), () -> 200, s -> s);
            var queued = f.dispatcher.submit(USERS, policy, context(), () -> 200, s -> s);
            assertThat(f.metrics.level("axiom.admission.queued")).isEqualTo(1);
            f.dispatcher.stopAdmission();
            assertThatThrownBy(() -> queued.result().toCompletableFuture().join()).hasCauseInstanceOf(RejectedExecutionException.class);
            assertThat(f.metrics.level("axiom.admission.queued")).isZero();
            assertThatThrownBy(() -> f.dispatcher.submit(USERS, policy, context(), () -> 200, s -> s))
                    .isInstanceOf(RejectedExecutionException.class);
            assertThat(f.metrics.count("axiom.admission.rejected", "method", "GET", "route", "/users/:id",
                    "reason", "shutdown")).isEqualTo(2);
            assertThat(f.metrics.count("axiom.http.requests", tags("5xx"))).isEqualTo(2);
        }
    }

    @Test void queueDepthAndWaitAreRecordedOnPromotionAndOnTimeout() {
        var policy = new AdmissionPolicy(1, 2, Duration.ofSeconds(5));
        try (var f = new Fixture(policy)) {
            f.dispatcher.submit(USERS, policy, context(), () -> 200, s -> s);
            var promoted = f.dispatcher.submit(USERS, policy, context(), () -> 200, s -> s);
            var expired = f.dispatcher.submit(USERS, policy, context(), () -> 200, s -> s);
            assertThat(f.metrics.level("axiom.admission.queued")).isEqualTo(2);
            f.clock.set(Duration.ofSeconds(2).toNanos());
            f.workers.run(0);
            assertThat(f.metrics.level("axiom.admission.queued")).isEqualTo(1);
            assertThat(f.metrics.observations("axiom.admission.queue.wait", USERS_TAGS))
                    .containsExactly(Duration.ofSeconds(2).toNanos());
            f.clock.set(Duration.ofSeconds(6).toNanos());
            f.timers.fire(2);
            assertThatThrownBy(() -> expired.result().toCompletableFuture().join())
                    .hasCauseInstanceOf(RequestDispatcher.QueueTimeoutException.class);
            assertThat(f.metrics.level("axiom.admission.queued")).isZero();
            assertThat(f.metrics.observations("axiom.admission.queue.wait", USERS_TAGS))
                    .containsExactly(Duration.ofSeconds(2).toNanos(), Duration.ofSeconds(6).toNanos());
            assertThat(f.metrics.count("axiom.admission.rejected", "method", "GET", "route", "/users/:id",
                    "reason", "queue_timeout")).isEqualTo(1);
            assertThat(f.metrics.count("axiom.http.requests", tags("5xx"))).isEqualTo(1);
            f.workers.run(0);
            assertThat(promoted.result().toCompletableFuture()).isCompletedWithValue(200);
            assertThat(f.metrics.level("axiom.admission.active")).isZero();
        }
    }

    @Test void executionDeadlineAndCancellationHaveTheirOwnOutcomes() {
        try (var f = new Fixture(AdmissionPolicy.reject(2))) {
            var late = f.dispatcher.submit(USERS, AdmissionPolicy.reject(2), context(), () -> 200, s -> s);
            f.timers.fire(0);
            assertThatThrownBy(() -> late.result().toCompletableFuture().join())
                    .hasCauseInstanceOf(RequestDispatcher.DeadlineExceededException.class);
            var cancelled = f.dispatcher.submit(USERS, AdmissionPolicy.reject(2), context(), () -> 200, s -> s);
            cancelled.cancel();
            assertThatThrownBy(() -> cancelled.result().toCompletableFuture().join())
                    .hasCauseInstanceOf(CancellationException.class);
            // Only the expired request is a 504; a request nobody waits for is not any status.
            assertThat(f.metrics.count("axiom.http.requests", tags("5xx"))).isEqualTo(1);
            assertThat(f.metrics.count("axiom.http.requests", tags("cancelled"))).isEqualTo(1);
        }
    }

    @Test void aFailingMetricsImplementationNeverFailsARequest() {
        var calls = new AtomicInteger();
        Metrics broken = new Metrics() {
            @Override public Counter counter(String name, String... tags) { calls.incrementAndGet(); throw new IllegalStateException("boom"); }
            @Override public Gauge gauge(String name, String... tags) { calls.incrementAndGet(); throw new IllegalStateException("boom"); }
            @Override public Timer timer(String name, String... tags) { calls.incrementAndGet(); throw new IllegalStateException("boom"); }
        };
        var workers = new ManualWorkers();
        var timers = new ManualTimers();
        try (var dispatcher = new RequestDispatcher(AdmissionPolicy.reject(2), workers, timers, new AtomicLong()::get, broken)) {
            var task = dispatcher.submit(USERS, AdmissionPolicy.reject(2), context(), () -> 200, s -> s);
            workers.run(0);
            assertThat(task.result().toCompletableFuture()).isCompletedWithValue(200);
            assertThat(dispatcher.snapshot().accepted()).isEqualTo(1);
            assertThat(calls).hasValueGreaterThan(0);
            while (!workers.tasks.isEmpty()) { workers.run(0); }
        }
    }

    @Test void aStatusFunctionThatFailsCountsTheRequestAsUnknown() {
        try (var f = new Fixture(AdmissionPolicy.reject(1))) {
            f.dispatcher.submit(USERS, AdmissionPolicy.reject(1), context(), () -> "x", result -> { throw new IllegalStateException(); });
            f.workers.run(0);
            assertThat(f.metrics.count("axiom.http.requests", tags("unknown"))).isEqualTo(1);
        }
    }

    @Test void disabledMetricsRecordNothingAndKeepTheSnapshot() {
        var workers = new ManualWorkers();
        var timers = new ManualTimers();
        try (var dispatcher = new RequestDispatcher(AdmissionPolicy.reject(1), workers, timers, System::nanoTime)) {
            var task = dispatcher.submit(USERS, AdmissionPolicy.reject(1), context(), () -> 200, s -> s);
            workers.run(0);
            assertThat(task.result().toCompletableFuture()).isCompletedWithValue(200);
            assertThat(dispatcher.snapshot().accepted()).isEqualTo(1);
        }
    }

    @Test void concurrentSubmissionsKeepEveryCounterAndReturnGaugesToZero() throws Exception {
        var metrics = new RecordingMetrics();
        int threads = 8;
        int perThread = 250;
        var policy = new AdmissionPolicy(4, 64, Duration.ofSeconds(30));
        var dispatcher = new RequestDispatcher(policy, metrics);
        try {
            var start = new CountDownLatch(1);
            var results = java.util.Collections.synchronizedList(new ArrayList<java.util.concurrent.CompletableFuture<Integer>>());
            var refused = new AtomicInteger();
            var workers = new ArrayList<Thread>();
            for (int t = 0; t < threads; t++) {
                workers.add(Thread.ofPlatform().start(() -> {
                    try { start.await(); } catch (InterruptedException interrupted) { return; }
                    for (int i = 0; i < perThread; i++) {
                        try {
                            results.add(dispatcher.submit(USERS, policy, context(), () -> 200, s -> s)
                                    .result().toCompletableFuture());
                        } catch (RejectedExecutionException full) { refused.incrementAndGet(); }
                    }
                }));
            }
            start.countDown();
            for (var worker : workers) { worker.join(); }
            for (var result : results) { result.get(30, TimeUnit.SECONDS); }

            long total = threads * perThread;
            long ok = metrics.count("axiom.http.requests", tags("2xx"));
            long unavailable = metrics.count("axiom.http.requests", tags("5xx"));
            assertThat(ok).isEqualTo(results.size());
            assertThat(unavailable).isEqualTo(refused.get());
            assertThat(ok + unavailable).isEqualTo(total);
            assertThat(metrics.count("axiom.admission.rejected", "method", "GET", "route", "/users/:id",
                    "reason", "capacity")).isEqualTo(refused.get());
            assertThat(metrics.observations("axiom.http.request.duration", USERS_TAGS)).hasSize(results.size());
            assertThat(metrics.level("axiom.admission.active")).isZero();
            assertThat(metrics.level("axiom.admission.queued")).isZero();
        } finally {
            dispatcher.close();
            dispatcher.termination().toCompletableFuture().get(10, TimeUnit.SECONDS);
        }
    }

    private static String[] tags(String statusClass) {
        return new String[] {"method", "GET", "route", "/users/:id", "status_class", statusClass};
    }

    private static ExecutionContext context() { return ExecutionContext.create(Duration.ofHours(1)); }

    private static final class Fixture implements AutoCloseable {
        final ManualWorkers workers = new ManualWorkers();
        final ManualTimers timers = new ManualTimers();
        final AtomicLong clock = new AtomicLong();
        final RecordingMetrics metrics = new RecordingMetrics();
        final RequestDispatcher dispatcher;
        Fixture(AdmissionPolicy policy) {
            dispatcher = new RequestDispatcher(policy, workers, timers, clock::get, metrics);
        }
        @Override public void close() {
            dispatcher.close();
            while (!workers.tasks.isEmpty()) { workers.run(0); }
            dispatcher.termination().toCompletableFuture().orTimeout(5, TimeUnit.SECONDS).join();
        }
    }

    private static final class ManualWorkers extends AbstractExecutorService {
        final List<Runnable> tasks = new ArrayList<>();
        volatile boolean closed;
        @Override public void execute(Runnable command) {
            if (closed) { throw new RejectedExecutionException(); }
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
        ManualTimers() { super(1); }
        @Override public ScheduledFuture<?> schedule(Runnable command, long delay, TimeUnit unit) {
            callbacks.add(command);
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
