package com.jsgalactic.axiom.http.internal;

import com.jsgalactic.axiom.http.StreamAbortedException;
import com.jsgalactic.axiom.observability.Metrics;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReferenceArray;

/**
 * The listener's measurements of streamed responses. Only one tag exists, {@code outcome}, and it
 * takes one of the fixed values of {@link Outcome}; nothing a request carries (path, query,
 * headers, identity) is ever a tag. Instruments are looked up once and kept. A failing
 * {@link Metrics} implementation never fails a stream: the first failure is logged and the
 * measurement dropped.
 */
final class StreamMetrics {
    static final String STREAMS = "axiom.http.streams";
    static final String BYTES = "axiom.http.stream.bytes";
    static final String ACTIVE = "axiom.http.streams.active";
    static final String BACKPRESSURE = "axiom.http.stream.backpressure";

    /** How a stream ended. */
    enum Outcome {
        COMPLETED("completed"), CLIENT_DISCONNECTED("client_disconnected"), LIMIT_EXCEEDED("limit_exceeded"),
        TIMEOUT("timeout"), SHUTDOWN("shutdown"), FAILED("failed");

        private final String tag;
        Outcome(String tag) { this.tag = tag; }

        static Outcome of(StreamAbortedException.Reason reason) {
            return switch (reason) {
                case CLIENT_DISCONNECTED -> CLIENT_DISCONNECTED;
                case LIMIT_EXCEEDED -> LIMIT_EXCEEDED;
                case TIMEOUT -> TIMEOUT;
                case SHUTDOWN -> SHUTDOWN;
            };
        }
    }

    private static final System.Logger LOG = System.getLogger(StreamMetrics.class.getName());
    private static final Outcome[] OUTCOMES = Outcome.values();

    private final Metrics metrics;
    private final boolean enabled;
    private final AtomicBoolean reported = new AtomicBoolean();
    private final AtomicReferenceArray<Metrics.Counter> streams = new AtomicReferenceArray<>(OUTCOMES.length);
    private volatile Metrics.Counter bytes;
    private volatile Metrics.Counter backpressure;
    private volatile Metrics.Gauge active;

    StreamMetrics(Metrics metrics) {
        this.metrics = metrics;
        this.enabled = metrics != Metrics.NOOP;
    }

    /** Counts a stream whose head was sent. */
    void started() {
        if (!enabled) { return; }
        try {
            var gauge = active;
            if (gauge == null) { gauge = metrics.gauge(ACTIVE); active = gauge; }
            gauge.add(1);
        } catch (RuntimeException failure) { dropped(failure); }
    }

    /** Counts a stream's end by outcome and the body bytes it wrote. */
    void finished(Outcome outcome, long written) {
        if (!enabled) { return; }
        try {
            var counter = streams.get(outcome.ordinal());
            if (counter == null) {
                counter = metrics.counter(STREAMS, "outcome", outcome.tag);
                streams.set(outcome.ordinal(), counter);
            }
            counter.increment();
            var total = bytes;
            if (total == null) { total = metrics.counter(BYTES); bytes = total; }
            total.add(written);
            active.add(-1);
        } catch (RuntimeException failure) { dropped(failure); }
    }

    /** Counts a write that had to wait because the client was not reading fast enough. */
    void blocked() {
        if (!enabled) { return; }
        try {
            var counter = backpressure;
            if (counter == null) { counter = metrics.counter(BACKPRESSURE); backpressure = counter; }
            counter.increment();
        } catch (RuntimeException failure) { dropped(failure); }
    }

    private void dropped(RuntimeException failure) {
        if (reported.compareAndSet(false, true)) {
            LOG.log(System.Logger.Level.WARNING,
                    "The metrics implementation failed; this and later failing measurements are dropped", failure);
        }
    }
}
