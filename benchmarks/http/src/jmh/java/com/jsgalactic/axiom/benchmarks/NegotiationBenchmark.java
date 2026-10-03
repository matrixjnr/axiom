package com.jsgalactic.axiom.benchmarks;

import com.jsgalactic.axiom.Axiom;
import com.jsgalactic.axiom.application.Application;
import com.jsgalactic.axiom.http.Request;
import com.jsgalactic.axiom.http.Response;
import java.util.Map;
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
 * Measures Accept negotiation of a JSON response through the public in-memory dispatcher. Every
 * case routes, runs the handler and encodes a small record; comparing {@code none} (no Accept
 * header) with the other cases shows what parsing and matching the header adds. {@code
 * unacceptable} measures the 406 path, which builds a problem response instead.
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(2)
public class NegotiationBenchmark {
    /** Response value. */
    public record Item(String name, int quantity) { }

    private static final Map<String, String> ACCEPT = Map.of(
            "none", "",
            "exact", "application/json",
            "browser", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
            "ranked", "text/plain;q=0.2, application/*;q=0.5, application/json;charset=utf-8;q=0.9, */*;q=0.1",
            "unacceptable", "text/html, application/xml, application/json;q=0");

    @Param({"none", "exact", "browser", "ranked", "unacceptable"})
    public String accept;

    private Application app;
    private Request request;

    @Setup
    public void setup() {
        app = Axiom.create();
        var item = new Item("pen", 2);
        app.get("/item", ctx -> ctx.json(item));
        app.start();
        var value = ACCEPT.get(accept);
        request = new Request("GET", "/item").withHeaders(value.isEmpty() ? Map.of() : Map.of("Accept", value));
    }

    @TearDown
    public void teardown() { app.close(); }

    @Benchmark
    public Response negotiate() throws Exception { return app.handle(request); }
}
