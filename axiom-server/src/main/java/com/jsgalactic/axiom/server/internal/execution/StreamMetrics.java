package com.jsgalactic.axiom.server.internal.execution;

import com.jsgalactic.axiom.http.StreamOutcome;
import com.jsgalactic.axiom.observability.Metrics;
import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReferenceArray;

/**
 * Measurements of streamed responses, shared by the HTTP listener and the test client. Series are
 * tagged by the endpoint's HTTP method and route template (the same bounded set the dispatcher
 * uses: at most {@value DispatchMetrics#MAX_ENDPOINTS} endpoints, then {@code other}) and, for the
 * stream counter, by {@code outcome}, one of the fixed values of {@link StreamOutcome.Kind};
 * nothing a request carries (path, query, headers, identity) is ever a tag. Instruments are looked
 * up once per endpoint and kept. A failing {@link Metrics} implementation never fails a stream:
 * the first failure is logged and the measurement dropped.
 */
public final class StreamMetrics {
    /** Finished streams by outcome. */
    public static final String STREAMS = "axiom.http.streams";
    /** Body bytes written. */
    public static final String BYTES = "axiom.http.stream.bytes";
    /** Streams whose body is running. */
    public static final String ACTIVE = "axiom.http.streams.active";
    /** Writes that waited for a slow client. */
    public static final String BACKPRESSURE = "axiom.http.stream.backpressure";

    private static final System.Logger LOG = System.getLogger(StreamMetrics.class.getName());
    private static final StreamOutcome.Kind[] KINDS = StreamOutcome.Kind.values();
    private static final String[] TAGS = new String[KINDS.length];

    static {
        for (var kind : KINDS) { TAGS[kind.ordinal()] = kind.name().toLowerCase(Locale.ROOT); }
    }

    private final Metrics metrics;
    private final boolean enabled;
    private final AtomicBoolean reported = new AtomicBoolean();
    private final ConcurrentHashMap<EndpointTag, Scope> scopes = new ConcurrentHashMap<>();
    private final Scope disabled = new Scope(null);

    /**
     * Creates the measurements of one listener or client.
     * @param metrics receiver of measurements; {@link Metrics#NOOP} disables recording
     */
    public StreamMetrics(Metrics metrics) {
        this.metrics = metrics;
        this.enabled = metrics != Metrics.NOOP;
    }

    /**
     * Returns the recorder for the streams of one endpoint.
     * @param tag the endpoint's bounded tags from {@link RequestDispatcher#endpointTag}, or null
     * @return a recorder; one that does nothing when metrics are disabled or the tag is null
     */
    public Scope scope(EndpointTag tag) {
        if (!enabled || tag == null) { return disabled; }
        var scope = scopes.get(tag);
        return scope != null ? scope : scopes.computeIfAbsent(tag, Scope::new);
    }

    private void dropped(RuntimeException failure) {
        if (reported.compareAndSet(false, true)) {
            LOG.log(System.Logger.Level.WARNING,
                    "The metrics implementation failed; this and later failing measurements are dropped", failure);
        }
    }

    /** The cached instruments of one endpoint's streams. */
    public final class Scope {
        private final EndpointTag tag;
        private final AtomicReferenceArray<Metrics.Counter> streams = new AtomicReferenceArray<>(KINDS.length);
        private volatile Metrics.Counter bytes;
        private volatile Metrics.Counter backpressure;
        private volatile Metrics.Gauge active;

        private Scope(EndpointTag tag) { this.tag = tag; }

        /** Counts a stream whose head was sent. */
        public void started() {
            if (tag == null) { return; }
            try {
                var gauge = active;
                if (gauge == null) { gauge = metrics.gauge(ACTIVE, "method", tag.method(), "route", tag.route()); active = gauge; }
                gauge.add(1);
            } catch (RuntimeException failure) { dropped(failure); }
        }

        /**
         * Counts a stream's end by outcome and the body bytes it wrote.
         * @param kind how it ended
         * @param written body bytes accepted
         */
        public void finished(StreamOutcome.Kind kind, long written) {
            if (tag == null) { return; }
            try {
                var counter = streams.get(kind.ordinal());
                if (counter == null) {
                    counter = metrics.counter(STREAMS, "method", tag.method(), "route", tag.route(),
                            "outcome", TAGS[kind.ordinal()]);
                    streams.set(kind.ordinal(), counter);
                }
                counter.increment();
                var total = bytes;
                if (total == null) { total = metrics.counter(BYTES, "method", tag.method(), "route", tag.route()); bytes = total; }
                total.add(written);
                var gauge = active;
                if (gauge != null) { gauge.add(-1); }
            } catch (RuntimeException failure) { dropped(failure); }
        }

        /** Counts a write that had to wait because the client was not reading fast enough. */
        public void blocked() {
            if (tag == null) { return; }
            try {
                var counter = backpressure;
                if (counter == null) {
                    counter = metrics.counter(BACKPRESSURE, "method", tag.method(), "route", tag.route());
                    backpressure = counter;
                }
                counter.increment();
            } catch (RuntimeException failure) { dropped(failure); }
        }
    }
}
