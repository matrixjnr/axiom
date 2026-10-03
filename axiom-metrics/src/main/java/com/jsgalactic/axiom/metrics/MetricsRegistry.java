package com.jsgalactic.axiom.metrics;

import com.jsgalactic.axiom.observability.Metrics;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;
import java.util.regex.Pattern;

/**
 * An in-memory {@link Metrics} implementation with a bounded number of series. Use it to assert
 * on measurements in tests, or render it with {@link PrometheusText} for a scrape endpoint.
 *
 * <p>Recording is lock-free and allocation-free: counters and gauges are {@link LongAdder}s and a
 * timer adds to one of a fixed set of bucket adders. Looking an instrument up takes a lock the
 * first time a series is created. The registry is thread-safe; readers see each value as it is
 * updated, so a read taken while requests are in flight is not an atomic snapshot across series.
 *
 * <p><strong>Cardinality.</strong> The registry keeps at most {@code maxSeries} distinct
 * name-and-tag combinations. Once the limit is reached, a request for a further new series gets a
 * working instrument that is not stored, and {@link #droppedSeries()} counts the request, so an
 * application bug that puts user input into a tag cannot exhaust memory. Existing series keep
 * recording. Tag values longer than {@value #MAX_VALUE_LENGTH} characters are truncated.
 *
 * <p><strong>Consistency.</strong> A metric name always has one kind (counter, gauge or timer) and
 * one set of tag keys; using it differently fails with {@link IllegalArgumentException}.
 */
public final class MetricsRegistry implements Metrics {
    /** Series limit of {@link #create()}. */
    public static final int DEFAULT_MAX_SERIES = 4096;
    /** Longest stored tag value; longer values are truncated. */
    public static final int MAX_VALUE_LENGTH = 256;
    /** Longest metric name. */
    public static final int MAX_NAME_LENGTH = 100;
    /** Timer bucket upper bounds in seconds; an implicit final bucket holds everything larger. */
    static final double[] BUCKETS_SECONDS =
            {0.001, 0.005, 0.01, 0.025, 0.05, 0.1, 0.25, 0.5, 1, 2.5, 5, 10};
    private static final long[] BUCKETS_NANOS = new long[BUCKETS_SECONDS.length];
    private static final Pattern NAME = Pattern.compile("[a-z][a-z0-9_]*(\\.[a-z][a-z0-9_]*)*");
    private static final Pattern KEY = Pattern.compile("[a-z][a-z0-9_]*");

    static {
        for (int i = 0; i < BUCKETS_NANOS.length; i++) { BUCKETS_NANOS[i] = Math.round(BUCKETS_SECONDS[i] * 1e9); }
    }

    /** What a metric name measures. */
    enum Kind { COUNTER, GAUGE, TIMER }

    /** The kind and tag keys every series of one name shares. */
    private record Family(Kind kind, List<String> tagKeys) { }

    private record Key(String name, List<String> tags) { }

    private final int maxSeries;
    private final ConcurrentHashMap<Key, Series> series = new ConcurrentHashMap<>();
    private final Map<String, Family> families = new HashMap<>();
    private final Map<String, String> exposedNames = new HashMap<>();
    private final LongAdder dropped = new LongAdder();

    private MetricsRegistry(int maxSeries) { this.maxSeries = maxSeries; }

    /**
     * Creates a registry that keeps up to {@link #DEFAULT_MAX_SERIES} series.
     * @return an empty registry
     */
    public static MetricsRegistry create() { return new MetricsRegistry(DEFAULT_MAX_SERIES); }

    /**
     * Creates a registry with an explicit series limit.
     * @param maxSeries most distinct name-and-tag combinations to keep, at least one
     * @return an empty registry
     * @throws IllegalArgumentException if the limit is below one
     */
    public static MetricsRegistry create(int maxSeries) {
        if (maxSeries < 1) { throw new IllegalArgumentException("maxSeries must be positive"); }
        return new MetricsRegistry(maxSeries);
    }

    @Override public Counter counter(String name, String... tags) {
        return lookup(Kind.COUNTER, name, tags) instanceof Counter counter ? counter : Metrics.NOOP.counter(name);
    }

    @Override public Gauge gauge(String name, String... tags) {
        return lookup(Kind.GAUGE, name, tags) instanceof Gauge gauge ? gauge : Metrics.NOOP.gauge(name);
    }

    @Override public Timer timer(String name, String... tags) {
        return lookup(Kind.TIMER, name, tags) instanceof Timer timer ? timer : Metrics.NOOP.timer(name);
    }

    /**
     * Reads a counter.
     * @param name metric name
     * @param tags alternating tag keys and values
     * @return its value, or zero if the series does not exist
     */
    public long counterValue(String name, String... tags) {
        return find(Kind.COUNTER, name, tags) instanceof CounterSeries counter ? counter.value.sum() : 0;
    }

    /**
     * Reads a gauge.
     * @param name metric name
     * @param tags alternating tag keys and values
     * @return its value, or zero if the series does not exist
     */
    public long gaugeValue(String name, String... tags) {
        return find(Kind.GAUGE, name, tags) instanceof GaugeSeries gauge ? gauge.value.sum() : 0;
    }

    /**
     * Reads a timer.
     * @param name metric name
     * @param tags alternating tag keys and values
     * @return its observation count and total, or zeros if the series does not exist
     */
    public TimerSnapshot timerSnapshot(String name, String... tags) {
        if (find(Kind.TIMER, name, tags) instanceof TimerSeries timer) {
            long count = 0;
            for (var bucket : timer.buckets) { count += bucket.sum(); }
            return new TimerSnapshot(count, timer.totalNanos.sum());
        }
        return new TimerSnapshot(0, 0);
    }

    /**
     * Returns how many distinct series are stored.
     * @return series count, at most the limit
     */
    public int seriesCount() { return series.size(); }

    /**
     * Returns how many lookups asked for a new series after the limit was reached.
     * @return number of refused series creations
     */
    public long droppedSeries() { return dropped.sum(); }

    /**
     * A timer's totals.
     * @param count number of observations
     * @param totalNanos sum of the observed durations in nanoseconds
     */
    public record TimerSnapshot(long count, long totalNanos) { }

    private Series find(Kind kind, String name, String[] tags) {
        var found = series.get(new Key(name, copy(tags)));
        return found != null && found.kind() == kind ? found : null;
    }

    private Series lookup(Kind kind, String name, String[] tags) {
        var key = new Key(name, copy(tags));
        var existing = series.get(key);
        if (existing != null) {
            if (existing.kind() != kind) { throw new IllegalArgumentException(conflict(name, existing.kind(), kind)); }
            return existing;
        }
        validate(name, key.tags());
        synchronized (this) {
            existing = series.get(key);
            if (existing != null) { return existing; }
            var keys = tagKeys(key.tags());
            var family = families.get(name);
            if (family != null) {
                if (family.kind() != kind) { throw new IllegalArgumentException(conflict(name, family.kind(), kind)); }
                if (!family.tagKeys().equals(keys)) {
                    throw new IllegalArgumentException("Metric " + name + " is used with tag keys " + family.tagKeys()
                            + " and cannot also use " + keys);
                }
            } else {
                var exposed = name.replace('.', '_');
                var owner = exposedNames.get(exposed);
                if (owner != null) {
                    throw new IllegalArgumentException("Metric " + name + " would be exposed under the same name as " + owner);
                }
            }
            if (series.size() >= maxSeries) {
                dropped.increment();
                return null;
            }
            if (family == null) {
                families.put(name, new Family(kind, keys));
                exposedNames.put(name.replace('.', '_'), name);
            }
            var created = switch (kind) {
                case COUNTER -> new CounterSeries(key);
                case GAUGE -> new GaugeSeries(key);
                case TIMER -> new TimerSeries(key);
            };
            series.put(key, created);
            return created;
        }
    }

    private static String conflict(String name, Kind existing, Kind requested) {
        return "Metric " + name + " is a " + existing.name().toLowerCase(java.util.Locale.ROOT)
                + " and cannot also be a " + requested.name().toLowerCase(java.util.Locale.ROOT);
    }

    private static List<String> copy(String[] tags) {
        if (tags == null || tags.length % 2 != 0) {
            throw new IllegalArgumentException("Tags are alternating keys and values");
        }
        var copy = new ArrayList<String>(tags.length);
        for (int i = 0; i < tags.length; i++) {
            var tag = tags[i];
            if (tag == null) { throw new IllegalArgumentException("Tags must not contain null"); }
            copy.add(i % 2 == 1 && tag.length() > MAX_VALUE_LENGTH ? tag.substring(0, MAX_VALUE_LENGTH) : tag);
        }
        return List.copyOf(copy);
    }

    private static List<String> tagKeys(List<String> tags) {
        var keys = new ArrayList<String>();
        for (int i = 0; i < tags.size(); i += 2) { keys.add(tags.get(i)); }
        return List.copyOf(keys);
    }

    private static void validate(String name, List<String> tags) {
        if (name == null || name.length() > MAX_NAME_LENGTH || !NAME.matcher(name).matches()) {
            throw new IllegalArgumentException("Metric names are lowercase words separated by dots, at most "
                    + MAX_NAME_LENGTH + " characters");
        }
        var seen = new HashSet<String>();
        for (int i = 0; i < tags.size(); i += 2) {
            var key = tags.get(i);
            if (!KEY.matcher(key).matches() || key.equals("le") || key.startsWith("__")) {
                throw new IllegalArgumentException("Invalid tag key: " + key);
            }
            if (!seen.add(key)) { throw new IllegalArgumentException("Duplicate tag key: " + key); }
        }
    }

    /** Series in a stable order: by name, then tags. */
    List<Series> sortedSeries() {
        var all = new ArrayList<>(series.values());
        all.sort(Comparator.comparing((Series each) -> each.key.name())
                .thenComparing(each -> String.join("\u0000", each.key.tags())));
        return all;
    }

    /** A stored series. */
    abstract static class Series {
        private final Key key;
        private Series(Key key) { this.key = key; }
        abstract Kind kind();
        String name() { return key.name(); }
        List<String> tags() { return key.tags(); }
    }

    static final class CounterSeries extends Series implements Counter {
        final LongAdder value = new LongAdder();
        private CounterSeries(Key key) { super(key); }
        @Override Kind kind() { return Kind.COUNTER; }
        @Override public void increment() { value.increment(); }
        @Override public void add(long amount) { if (amount > 0) { value.add(amount); } }
    }

    static final class GaugeSeries extends Series implements Gauge {
        final LongAdder value = new LongAdder();
        private GaugeSeries(Key key) { super(key); }
        @Override Kind kind() { return Kind.GAUGE; }
        @Override public void add(long delta) { value.add(delta); }
    }

    static final class TimerSeries extends Series implements Timer {
        /** Per-bucket counts (not cumulative); the last entry counts observations above every bound. */
        final LongAdder[] buckets = new LongAdder[BUCKETS_NANOS.length + 1];
        final LongAdder totalNanos = new LongAdder();
        private TimerSeries(Key key) {
            super(key);
            for (int i = 0; i < buckets.length; i++) { buckets[i] = new LongAdder(); }
        }
        @Override Kind kind() { return Kind.TIMER; }
        @Override public void record(long nanos) {
            if (nanos < 0) { return; }
            int bucket = 0;
            while (bucket < BUCKETS_NANOS.length && nanos > BUCKETS_NANOS[bucket]) { bucket++; }
            buckets[bucket].increment();
            totalNanos.add(nanos);
        }
    }

}
