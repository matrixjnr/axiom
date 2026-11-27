package com.jsgalactic.axiom.otel;

import com.jsgalactic.axiom.observability.Metrics;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.metrics.DoubleHistogram;
import io.opentelemetry.api.metrics.LongCounter;
import io.opentelemetry.api.metrics.LongUpDownCounter;
import io.opentelemetry.api.metrics.Meter;
import io.opentelemetry.api.metrics.MeterProvider;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * Maps the dependency-free {@link Metrics} SPI to OpenTelemetry API instruments. Counters become
 * dimensionless {@link LongCounter}s, gauges become {@link LongUpDownCounter}s and timers become
 * {@link DoubleHistogram}s in seconds with the explicit bucket boundaries of
 * {@link #DEFAULT_BUCKETS}, which match the bounds of the in-memory registry in {@code axiom-metrics}.
 *
 * <p>Instruments come from the instrumentation scope {@value Tracing#SCOPE_NAME}. A lookup resolves
 * and caches one instrument per name and tag set; recording methods are thread-safe and use the
 * attributes captured at lookup. A name always has one kind and one set of tag keys: using it
 * otherwise throws {@link IllegalArgumentException}, as the registry does. Tag values remain the
 * caller's responsibility: keep them to route templates, methods and other fixed sets, because
 * neither this adapter nor the OTel API bounds cardinality.
 *
 * <p>The adapter owns no SDK, reader or exporter. It is safe to share one instance across
 * requests, and several adapters can write to the same {@link MeterProvider}.
 */
public final class OtelMetrics implements Metrics {
    /**
     * Histogram bounds in seconds: 1 ms, 5 ms, 10 ms, 25 ms, 50 ms, 100 ms, 250 ms, 500 ms, 1 s,
     * 2.5 s, 5 s and 10 s. The last bucket is open-ended.
     */
    public static final List<Double> DEFAULT_BUCKETS = List.of(
            0.001, 0.005, 0.01, 0.025, 0.05, 0.1, 0.25, 0.5, 1.0, 2.5, 5.0, 10.0);
    private static final Pattern NAME = Pattern.compile("[a-z][a-z0-9_]*(\\.[a-z][a-z0-9_]*)*");
    private static final Pattern KEY = Pattern.compile("[a-z][a-z0-9_]*");

    private enum Kind { COUNTER, GAUGE, TIMER }

    private record Family(Kind kind, List<String> keys) { }

    private record Key(String name, List<String> tags) { }

    private final Meter meter;
    private final Map<String, Family> families = new HashMap<>();
    private final ConcurrentHashMap<Key, Counter> counters = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Key, Gauge> gauges = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Key, Timer> timers = new ConcurrentHashMap<>();

    private OtelMetrics(Meter meter) { this.meter = meter; }

    /**
     * Creates an adapter using the meter of {@link Tracing#SCOPE_NAME} from the supplied instance.
     * The caller owns the SDK and any metric readers or exporters.
     *
     * @param openTelemetry meter source, or {@link OpenTelemetry#noop()} to discard measurements
     * @return an empty adapter
     */
    public static OtelMetrics create(OpenTelemetry openTelemetry) {
        Objects.requireNonNull(openTelemetry, "openTelemetry");
        return create(openTelemetry.getMeterProvider());
    }

    /**
     * Creates an adapter using the meter of {@link Tracing#SCOPE_NAME} from the supplied provider.
     *
     * @param meterProvider provider that creates the instruments
     * @return an empty adapter
     */
    public static OtelMetrics create(MeterProvider meterProvider) {
        Objects.requireNonNull(meterProvider, "meterProvider");
        var version = OtelMetrics.class.getPackage().getImplementationVersion();
        var builder = meterProvider.meterBuilder(Tracing.SCOPE_NAME);
        if (version != null) { builder.setInstrumentationVersion(version); }
        return new OtelMetrics(builder.build());
    }

    @Override
    public Counter counter(String name, String... tags) {
        var key = key(Kind.COUNTER, name, tags);
        return counters.computeIfAbsent(key, k -> {
            var counter = meter.counterBuilder(k.name()).setUnit("1").build();
            return (Counter) new Count(counter, attributes(k.tags()));
        });
    }

    @Override
    public Gauge gauge(String name, String... tags) {
        var key = key(Kind.GAUGE, name, tags);
        return gauges.computeIfAbsent(key, k -> {
            var counter = meter.upDownCounterBuilder(k.name()).setUnit("1").build();
            return (Gauge) new Level(counter, attributes(k.tags()));
        });
    }

    @Override
    public Timer timer(String name, String... tags) {
        var key = key(Kind.TIMER, name, tags);
        return timers.computeIfAbsent(key, k -> {
            var histogram = meter.histogramBuilder(k.name())
                    .setUnit("s")
                    .setExplicitBucketBoundariesAdvice(DEFAULT_BUCKETS)
                    .build();
            return (Timer) new Duration(histogram, attributes(k.tags()));
        });
    }

    private Key key(Kind kind, String name, String[] tags) {
        Objects.requireNonNull(name, "name");
        if (!NAME.matcher(name).matches()) { throw new IllegalArgumentException("Invalid metric name: " + name); }
        if ((tags.length & 1) != 0) { throw new IllegalArgumentException("Tags must be alternating keys and values"); }
        var keys = new ArrayList<String>();
        for (int i = 0; i < tags.length; i += 2) {
            var tagKey = Objects.requireNonNull(tags[i], "tag key");
            if (!KEY.matcher(tagKey).matches()) { throw new IllegalArgumentException("Invalid tag key: " + tagKey); }
            Objects.requireNonNull(tags[i + 1], "tag value");
            if (keys.contains(tagKey)) { throw new IllegalArgumentException("Duplicate tag key: " + tagKey); }
            keys.add(tagKey);
        }
        var family = new Family(kind, List.copyOf(keys));
        synchronized (families) {
            var previous = families.putIfAbsent(name, family);
            if (previous != null && !previous.equals(family)) {
                throw new IllegalArgumentException("Metric name '" + name + "' is already used with a different kind or tag keys");
            }
        }
        return new Key(name, List.of(tags));
    }

    private static Attributes attributes(List<String> tags) {
        var builder = Attributes.builder();
        for (int i = 0; i < tags.size(); i += 2) { builder.put(tags.get(i), tags.get(i + 1)); }
        return builder.build();
    }

    private record Count(LongCounter counter, Attributes attributes) implements Counter {
        @Override public void increment() { counter.add(1, attributes); }

        @Override public void add(long amount) {
            if (amount > 0) { counter.add(amount, attributes); }
        }
    }

    private record Level(LongUpDownCounter counter, Attributes attributes) implements Gauge {
        @Override public void add(long delta) { counter.add(delta, attributes); }
    }

    private record Duration(DoubleHistogram histogram, Attributes attributes) implements Timer {
        @Override public void record(long nanos) {
            if (nanos > 0) { histogram.record(nanos / 1_000_000_000.0, attributes); }
        }
    }
}
