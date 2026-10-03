package com.jsgalactic.axiom.benchmarks;

import com.jsgalactic.axiom.Axiom;
import com.jsgalactic.axiom.application.Application;
import com.jsgalactic.axiom.context.Middleware;
import com.jsgalactic.axiom.http.Request;
import com.jsgalactic.axiom.http.Response;
import java.util.concurrent.TimeUnit;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;
import org.openjdk.jmh.annotations.Warmup;

/**
 * Measures the per-request cost of a compiled chain of pass-through middleware in front of a
 * trivial handler, on one thread. {@code depth} is the number of middleware; {@code scope} says
 * whether they are registered globally with {@code use} or as arguments of the route. Chains are
 * composed once at startup, so the difference to {@code depth=0} is the dispatch overhead of the
 * chain. Append {@code -prof gc} to see allocation per operation.
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(2)
public class MiddlewareChainBenchmark {
    @Param({"0", "1", "5", "20"})
    public int depth;

    @Param({"global", "route"})
    public String scope;

    private Application app;
    private Request request;

    @Setup
    public void setup() {
        app = Axiom.create();
        var middleware = new Middleware[depth];
        for (int i = 0; i < depth; i++) { middleware[i] = (ctx, next) -> next.run(); }
        if (scope.equals("global")) {
            for (var each : middleware) { app.use(each); }
            app.get("/ping", ctx -> "ok");
        } else {
            app.get("/ping", ctx -> "ok", middleware);
        }
        app.start();
        request = Request.get("/ping");
    }

    @TearDown
    public void teardown() { app.close(); }

    @Benchmark
    public Response handle() throws Exception { return app.handle(request); }
}
