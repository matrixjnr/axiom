package io.axiom.benchmarks;

import io.axiom.codec.spi.BodyCodec;
import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.ServiceLoader;
import java.util.UUID;
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
 * Measures the installed {@code application/json} codec (the Jackson codec from axiom-json,
 * discovered as a service like the runtime does) decoding and encoding a small and a medium
 * record. Decoding reads from a read-only view of the content, as {@code ctx.body(...)} does.
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(2)
public class JsonCodecBenchmark {
    /** Two scalar properties; about 30 bytes of JSON. */
    public record Item(String name, int quantity) { }

    /** One order line. */
    public record Line(String sku, String description, int quantity, BigDecimal price) { }

    /** Nested records, a list of 20 lines, java.time values, a UUID and a map; about 2 KiB of JSON. */
    public record Order(UUID id, String customer, Instant placedAt, LocalDate deliverOn, List<Line> lines,
                        Map<String, String> tags, boolean paid) { }

    private BodyCodec codec;
    private Item small;
    private Order medium;
    private ByteBuffer smallJson;
    private ByteBuffer mediumJson;

    @Setup
    public void setup() {
        codec = ServiceLoader.load(BodyCodec.class).stream().map(ServiceLoader.Provider::get)
                .filter(candidate -> candidate.supports("application/json")).findFirst()
                .orElseThrow(() -> new IllegalStateException("No JSON codec on the benchmark classpath"));
        small = new Item("pen", 2);
        var lines = new ArrayList<Line>();
        for (int i = 0; i < 20; i++) {
            lines.add(new Line("sku-" + i, "Item number " + i + " with a short description", i + 1,
                    new BigDecimal("12.50").add(BigDecimal.valueOf(i))));
        }
        medium = new Order(UUID.fromString("123e4567-e89b-12d3-a456-426614174000"), "Ada Lovelace",
                Instant.parse("2024-02-29T10:15:30Z"), LocalDate.of(2024, 3, 4), List.copyOf(lines),
                Map.of("channel", "web", "priority", "standard"), true);
        smallJson = ByteBuffer.wrap(codec.encode(small)).asReadOnlyBuffer();
        mediumJson = ByteBuffer.wrap(codec.encode(medium)).asReadOnlyBuffer();
        if (!codec.decode(mediumJson.duplicate(), Order.class).equals(medium)) {
            throw new IllegalStateException("Medium record does not round-trip");
        }
    }

    @Benchmark
    public Item decodeSmall() { return codec.decode(smallJson.duplicate(), Item.class); }

    @Benchmark
    public Order decodeMedium() { return codec.decode(mediumJson.duplicate(), Order.class); }

    @Benchmark
    public byte[] encodeSmall() { return codec.encode(small); }

    @Benchmark
    public byte[] encodeMedium() { return codec.encode(medium); }
}
