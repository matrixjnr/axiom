package com.jsgalactic.axiom.benchmarks;

import com.jsgalactic.axiom.Axiom;
import com.jsgalactic.axiom.application.Application;
import com.jsgalactic.axiom.execution.ExecutionContext;
import com.jsgalactic.axiom.http.Request;
import com.jsgalactic.axiom.http.Response;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;
import org.openjdk.jmh.annotations.Threads;
import org.openjdk.jmh.annotations.Warmup;

/**
 * Measures request dispatch of one running application driven by several threads at once. The
 * request path reads an immutable runtime snapshot without taking a lock, so the cost per
 * operation under contention can be compared with the single-thread cost (run with {@code -t 1}
 * to override the default of one JMH thread per available processor). {@code handle} includes
 * creating the request's execution context and identity; {@code handleSharedExecution} reuses
 * one context and so excludes identity generation, which {@link RequestIdBenchmark} measures
 * alone.
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(2)
@Threads(Threads.MAX)
public class RequestPathBenchmark {
    private Application app;
    private Request request;
    private ExecutionContext shared;

    @Setup
    public void setup() {
        app = Axiom.create();
        app.get("/items/:id", ctx -> ctx.path("id"));
        app.start();
        request = Request.get("/items/42");
        shared = ExecutionContext.create(Duration.ofHours(1));
    }

    @TearDown
    public void teardown() { app.close(); }

    @Benchmark
    public Response handle() throws Exception { return app.handle(request); }

    @Benchmark
    public Response handleSharedExecution() throws Exception { return app.handle(request, shared); }
}
