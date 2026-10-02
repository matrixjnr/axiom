package io.axiom.benchmarks;

import io.axiom.error.DecodeException;
import io.axiom.execution.ExecutionContext;
import io.axiom.http.Response;
import io.axiom.server.internal.Problems;
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
import org.openjdk.jmh.annotations.Warmup;

/**
 * Measures building {@code application/problem+json} responses, as the runtime, listener and
 * test client do for every error. The exception is created once; its construction is not measured.
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(2)
public class ProblemBenchmark {
    private DecodeException decodeFailure;
    private String requestId;

    @Setup
    public void setup() {
        decodeFailure = new DecodeException("type_mismatch", "lines[0].quantity");
        requestId = ExecutionContext.create(Duration.ofSeconds(10)).requestId();
    }

    /** A status-only problem, such as 404 or 503. */
    @Benchmark
    public Response forStatus() { return Problems.response(404, requestId); }

    /** A problem with one field violation, such as a body decoding failure. */
    @Benchmark
    public Response forException() { return Problems.response(decodeFailure, requestId); }
}
