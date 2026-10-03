package com.jsgalactic.axiom.metrics;

import com.jsgalactic.axiom.observability.Metrics;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
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
 * <p><strong>Histograms and help.</strong> Timers are histograms with {@link #DEFAULT_BUCKETS} unless
 * {@link Builder#timerBuckets} sets other bounds, for every timer or for one name. The registry keeps
 * no quantiles and no exemplars: a histogram lets the monitoring system derive quantiles across
 * instances, and an exemplar would need a per-request identifier that must never be a tag.
 * {@link Builder#help} gives a metric text for the exposition's {@code # HELP} line.
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
    /** Most upper bounds one timer may have, not counting the implicit final bucket. */
    public static final int MAX_BUCKETS = 64;
    /** Longest help text. */
    public static final int MAX_HELP_LENGTH = 512;
    /**
     * Timer bucket upper bounds used unless a timer is configured otherwise: 1 ms, 5 ms, 10 ms,
     * 25 ms, 50 ms, 100 ms, 250 ms, 500 ms, 1 s, 2.5 s, 5 s and 10 s. An implicit final bucket
     * holds everything larger.
     */
    public static final List<Duration> DEFAULT_BUCKETS = List.of(
            Duration.ofMillis(1), Duration.ofMillis(5), Duration.ofMillis(10), Duration.ofMillis(25),
            Duration.ofMillis(50), Duration.ofMillis(100), Duration.ofMillis(250), Duration.ofMillis(500),
            Duration.ofSeconds(1), Duration.ofMillis(2500), Duration.ofSeconds(5), Duration.ofSeconds(10));
    private static final long[] DEFAULT_NANOS = nanos(DEFAULT_BUCKETS);
    private static final Pattern NAME = Pattern.compile("[a-z][a-z0-9_]*(\\.[a-z][a-z0-9_]*)*");
    private static final Pattern KEY = Pattern.compile("[a-z][a-z0-9_]*");

    /** What a metric name measures. */
    enum Kind { COUNTER, GAUGE, TIMER }

    /** The kind and tag keys every series of one name shares. */
    private record Family(Kind kind, List<String> tagKeys) { }

    private record Key(String name, List<String> tags) { }

    private final int maxSeries;
    private final long[] defaultBuckets;
    private final Map<String, long[]> timerBuckets;
    private final Map<String, String> help;
    private final ConcurrentHashMap<Key, Series> series = new ConcurrentHashMap<>();
    private final Map<String, Family> families = new HashMap<>();
    private final Map<String, String> exposedNames = new HashMap<>();
    private final LongAdder dropped = new LongAdder();

    private MetricsRegistry(Builder builder) {
        this.maxSeries = builder.maxSeries;
        this.defaultBuckets = builder.defaultBuckets;
        this.timerBuckets = Map.copyOf(builder.timerBuckets);
        this.help = Map.copyOf(builder.help);
    }

    /**
     * Starts a registry with explicit settings: the series limit, the histogram buckets of all
     * timers or of one timer, and help text.
     * @return a new builder with the defaults of {@link #create()}
     */
    public static Builder builder() { return new Builder(); }

    /**
     * Creates a registry that keeps up to {@link #DEFAULT_MAX_SERIES} series.
     * @return an empty registry
     */
    public static MetricsRegistry create() { return builder().build(); }

    /**
     * Creates a registry with an explicit series limit.
     * @param maxSeries most distinct name-and-tag combinations to keep, at least one
     * @return an empty registry
     * @throws IllegalArgumentException if the limit is below one
     */
    public static MetricsRegistry create(int maxSeries) {
        return builder().maxSeries(maxSeries).build();
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
     * Returns the help text of a metric.
     * @param name metric name
     * @return the text given to {@link Builder#help}, or empty
     */
    public Optional<String> help(String name) { return Optional.ofNullable(help.get(name)); }

    /**
     * Returns a timer's bucket upper bounds.
     * @param name metric name
     * @return the configured bounds in ascending order, or the registry default
     */
    public List<Duration> timerBuckets(String name) {
        var configured = timerBuckets.getOrDefault(name, defaultBuckets);
        var bounds = new ArrayList<Duration>(configured.length);
        for (var nanos : configured) { bounds.add(Duration.ofNanos(nanos)); }
        return List.copyOf(bounds);
    }

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
                case TIMER -> new TimerSeries(key, timerBuckets.getOrDefault(name, defaultBuckets));
            };
            series.put(key, created);
            return created;
        }
    }

    private static long[] nanos(List<Duration> bounds) {
        if (bounds.size() > MAX_BUCKETS) {
            throw new IllegalArgumentException("A timer has at most " + MAX_BUCKETS + " buckets");
        }
        var nanos = new long[bounds.size()];
        long previous = 0;
        for (int i = 0; i < nanos.length; i++) {
            long each;
            try {
                each = Objects.requireNonNull(bounds.get(i), "bucket").toNanos();
            } catch (ArithmeticException tooLong) {
                throw new IllegalArgumentException("A bucket bound is too long", tooLong);
            }
            if (each <= previous) { throw new IllegalArgumentException("Bucket bounds are positive and strictly ascending"); }
            nanos[i] = each;
            previous = each;
        }
        return nanos;
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
        /** Upper bounds in nanoseconds, ascending. */
        final long[] bounds;
        /** Per-bucket counts (not cumulative); the last entry counts observations above every bound. */
        final LongAdder[] buckets;
        final LongAdder totalNanos = new LongAdder();
        private TimerSeries(Key key, long[] bounds) {
            super(key);
            this.bounds = bounds;
            this.buckets = new LongAdder[bounds.length + 1];
            for (int i = 0; i < buckets.length; i++) { buckets[i] = new LongAdder(); }
        }
        @Override Kind kind() { return Kind.TIMER; }
        @Override public void record(long nanos) {
            if (nanos < 0) { return; }
            int bucket = 0;
            while (bucket < bounds.length && nanos > bounds[bucket]) { bucket++; }
            buckets[bucket].increment();
            totalNanos.add(nanos);
        }
    }

    /** Settings of a {@link MetricsRegistry}. Not thread-safe; build once at startup. */
    public static final class Builder {
        private int maxSeries = DEFAULT_MAX_SERIES;
        private long[] defaultBuckets = DEFAULT_NANOS;
        private final Map<String, long[]> timerBuckets = new HashMap<>();
        private final Map<String, String> help = new HashMap<>();

        private Builder() { }

        /**
         * Sets the series limit.
         * @param maxSeries most distinct name-and-tag combinations to keep, at least one
         * @return this builder
         * @throws IllegalArgumentException if the limit is below one
         */
        public Builder maxSeries(int maxSeries) {
            if (maxSeries < 1) { throw new IllegalArgumentException("maxSeries must be positive"); }
            this.maxSeries = maxSeries;
            return this;
        }

        /**
         * Sets the histogram bounds of every timer that has none of its own.
         * @param bounds positive, strictly ascending upper bounds, at most {@value #MAX_BUCKETS}
         * @return this builder
         * @throws IllegalArgumentException if the bounds are empty, unordered or too many
         */
        public Builder timerBuckets(List<Duration> bounds) {
            this.defaultBuckets = checked(bounds);
            return this;
        }

        /**
         * Sets the histogram bounds of one timer, whatever its tags. Applies to series created
         * afterwards, so configure before the application starts.
         * @param name timer name
         * @param bounds positive, strictly ascending upper bounds, at most {@value #MAX_BUCKETS}
         * @return this builder
         * @throws IllegalArgumentException if the name is invalid or the bounds are empty, unordered or too many
         */
        public Builder timerBuckets(String name, List<Duration> bounds) {
            validate(name, List.of());
            timerBuckets.put(name, checked(bounds));
            return this;
        }

        /**
         * Sets the text of the {@code # HELP} line of a metric.
         * @param name metric name
         * @param text one line of plain text, at most {@value #MAX_HELP_LENGTH} characters
         * @return this builder
         * @throws IllegalArgumentException if the name is invalid or the text is empty, too long
         *         or contains a line break
         */
        public Builder help(String name, String text) {
            validate(name, List.of());
            if (text == null || text.isBlank() || text.length() > MAX_HELP_LENGTH
                    || text.indexOf('\n') >= 0 || text.indexOf('\r') >= 0) {
                throw new IllegalArgumentException("Help is one non-empty line of at most " + MAX_HELP_LENGTH + " characters");
            }
            help.put(name, text);
            return this;
        }

        /**
         * Creates the registry.
         * @return an empty registry
         */
        public MetricsRegistry build() { return new MetricsRegistry(this); }

        private static long[] checked(List<Duration> bounds) {
            if (bounds == null || bounds.isEmpty()) { throw new IllegalArgumentException("A timer needs at least one bucket"); }
            return nanos(bounds);
        }
    }
}
