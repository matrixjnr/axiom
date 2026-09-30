package io.axiom.benchmarks;

import io.axiom.Axiom;
import io.axiom.application.Application;
import io.axiom.http.Request;
import io.axiom.http.Response;
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

/** Measures routing through the public in-memory dispatcher, including context and response work. */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(2)
public class RoutingBenchmark {
    @Param({"100", "10000"})
    public int routeCount;

    private Application app;
    private Request literal;
    private Request parameter;
    private Request wildcard;
    private Request backtracking;
    private Request methodMismatch;
    private Request missing;

    @Setup
    public void setup() {
        app = Axiom.create();
        for (int i = 0; i < routeCount; i++) {
            app.get("/groups/g" + i + "/items/:id", ctx -> ctx.path("id"));
        }
        app.get("/health", ctx -> "ok");
        app.get("/files/*path", ctx -> ctx.path("path"));
        app.get("/fallback/static/dead", ctx -> "dead");
        app.get("/fallback/:id/live", ctx -> ctx.path("id"));
        app.start();
        literal = Request.get("/health");
        parameter = Request.get("/groups/g" + (routeCount - 1) + "/items/42");
        wildcard = Request.get("/files/a/b/c.txt");
        backtracking = Request.get("/fallback/static/live");
        methodMismatch = new Request("POST", parameter.path());
        missing = Request.get("/not-registered");
    }

    @TearDown
    public void teardown() { app.close(); }

    @Benchmark
    public Response literal() throws Exception { return app.handle(literal); }
    @Benchmark
    public Response parameter() throws Exception { return app.handle(parameter); }
    @Benchmark
    public Response wildcard() throws Exception { return app.handle(wildcard); }
    @Benchmark
    public Response backtracking() throws Exception { return app.handle(backtracking); }
    @Benchmark
    public Response methodMismatch() throws Exception { return app.handle(methodMismatch); }
    @Benchmark
    public Response missing() throws Exception { return app.handle(missing); }
}
