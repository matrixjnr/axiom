package com.jsgalactic.axiom.observability;

/**
 * Receives the counters, gauges and timers the runtime records. The default is {@link #NOOP},
 * which records nothing and allocates nothing; a real implementation is installed with
 * {@code Application.metrics(Metrics)} before startup.
 *
 * <p><strong>Lookup versus recording.</strong> {@link #counter}, {@link #gauge} and {@link #timer}
 * look an instrument up, or create it, by name and tags. They may allocate and take a lock, so
 * callers resolve instruments once and keep them; the runtime does. The recording methods of an
 * instrument are the hot path: implementations make them thread-safe, non-blocking and
 * allocation-free. The runtime never lets a failing instrument fail a request: a
 * {@link RuntimeException} from any method is caught, reported once to {@link System.Logger}, and
 * the measurement is dropped.
 *
 * <p><strong>Names and tags.</strong> A name is lowercase words separated by dots, for example
 * {@code axiom.http.requests}. Tags are given as alternating keys and values. Tag values must come
 * from a small, fixed set: route templates, HTTP methods and status classes, never raw request
 * paths, query strings, header values, identities or other user input. The runtime follows this
 * rule itself, and an implementation should bound the number of series it keeps (the registry in
 * {@code axiom-metrics} does) so that an application mistake cannot exhaust memory.
 *
 * <p>The same name and tags always identify the same series. The runtime's own metric names and
 * tags are listed in the observability guide.
 */
public interface Metrics {
    /** Records nothing. Its instruments are shared, immutable and free of allocation. */
    Metrics NOOP = NoopMetrics.INSTANCE;

    /**
     * Returns the monotonically increasing counter for a series, creating it if needed.
     * @param name dotted lowercase metric name
     * @param tags alternating tag keys and values, possibly none
     * @return a thread-safe counter; never null
     * @throws IllegalArgumentException if the name or tags are invalid or conflict with the
     *         metric's earlier use
     */
    Counter counter(String name, String... tags);

    /**
     * Returns the gauge for a series, creating it if needed. A gauge is a value that moves both
     * ways, such as a queue depth. It is changed by deltas, so several components can share one
     * series and the value stays the sum of their contributions.
     * @param name dotted lowercase metric name
     * @param tags alternating tag keys and values, possibly none
     * @return a thread-safe gauge; never null
     * @throws IllegalArgumentException if the name or tags are invalid or conflict with the
     *         metric's earlier use
     */
    Gauge gauge(String name, String... tags);

    /**
     * Returns the timer for a series, creating it if needed. A timer records durations and keeps
     * their distribution, so latency percentiles can be derived from it.
     * @param name dotted lowercase metric name
     * @param tags alternating tag keys and values, possibly none
     * @return a thread-safe timer; never null
     * @throws IllegalArgumentException if the name or tags are invalid or conflict with the
     *         metric's earlier use
     */
    Timer timer(String name, String... tags);

    /** A monotonically increasing count. */
    interface Counter {
        /** Adds one. */
        void increment();

        /**
         * Adds an amount.
         * @param amount increase; negative values are ignored
         */
        void add(long amount);
    }

    /** A value that moves both ways. */
    interface Gauge {
        /**
         * Adds a signed amount.
         * @param delta change in the value
         */
        void add(long delta);
    }

    /** A recorder of durations. */
    interface Timer {
        /**
         * Records one observation.
         * @param nanos elapsed time in nanoseconds; negative values are ignored
         */
        void record(long nanos);
    }
}
