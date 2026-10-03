package com.jsgalactic.axiom.benchmarks;

import com.jsgalactic.axiom.execution.ExecutionContext;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Threads;
import org.openjdk.jmh.annotations.Warmup;

/**
 * Measures request identity generation. Every {@link ExecutionContext} takes the next value of a
 * process-wide counter, so {@code createContext} (the context and its identity) shows the cost of
 * that shared counter when several threads create contexts at once, and {@code requestId} adds
 * reading the identity string. {@code uuid} is the {@link UUID#randomUUID()} call the counter
 * replaced, kept as a reference point on the same machine. Runs with one JMH thread per
 * available processor; override with {@code -t 1} for the uncontended cost.
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(2)
@Threads(Threads.MAX)
public class RequestIdBenchmark {
    private static final Duration TIMEOUT = Duration.ofSeconds(10);

    @Benchmark
    public ExecutionContext createContext() { return ExecutionContext.create(TIMEOUT); }

    @Benchmark
    public String requestId() { return ExecutionContext.create(TIMEOUT).requestId(); }

    @Benchmark
    public String uuid() { return UUID.randomUUID().toString(); }
}
