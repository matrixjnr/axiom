package com.jsgalactic.axiom.benchmarks;

import com.jsgalactic.axiom.execution.AdmissionPolicy;
import com.jsgalactic.axiom.execution.ExecutionContext;
import com.jsgalactic.axiom.server.internal.execution.RequestDispatcher;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;
import org.openjdk.jmh.annotations.Warmup;

/**
 * Measures the request dispatcher that listeners and the test client share. {@code uncontended}
 * admits a trivial task with free capacity and waits for its result, so it includes starting a
 * virtual thread and completing the outcome. {@code fullQueue} submits while every active slot
 * and queue slot is held, measuring the rejection that becomes a 503.
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(2)
public class AdmissionBenchmark {
    private static final Duration DEADLINE = Duration.ofHours(1);

    /** A dispatcher with capacity to spare. */
    @State(Scope.Benchmark)
    public static class Uncontended {
        RequestDispatcher dispatcher;

        @Setup(Level.Trial)
        public void setup() { dispatcher = new RequestDispatcher(AdmissionPolicy.reject(64)); }

        @TearDown(Level.Trial)
        public void teardown() { dispatcher.close(); }
    }

    /** A dispatcher whose single active slot and single queue slot are held for the whole trial. */
    @State(Scope.Benchmark)
    public static class Full {
        RequestDispatcher dispatcher;
        final CountDownLatch release = new CountDownLatch(1);

        @Setup(Level.Trial)
        public void setup() {
            dispatcher = new RequestDispatcher(new AdmissionPolicy(1, 1, DEADLINE));
            for (int i = 0; i < 2; i++) {
                dispatcher.submit(ExecutionContext.create(DEADLINE), () -> {
                    release.await();
                    return null;
                });
            }
            var snapshot = dispatcher.snapshot();
            if (snapshot.active() != 1 || snapshot.queued() != 1) {
                throw new IllegalStateException("Dispatcher is not full: " + snapshot);
            }
        }

        @TearDown(Level.Trial)
        public void teardown() {
            release.countDown();
            dispatcher.close();
        }
    }

    @Benchmark
    public Object uncontended(Uncontended state) throws Exception {
        return state.dispatcher.submit(ExecutionContext.create(DEADLINE), () -> Boolean.TRUE)
                .result().toCompletableFuture().get();
    }

    @Benchmark
    public Object fullQueue(Full state) {
        try {
            return state.dispatcher.submit(ExecutionContext.create(DEADLINE), () -> Boolean.TRUE);
        } catch (RejectedExecutionException rejected) {
            return rejected;
        }
    }
}
