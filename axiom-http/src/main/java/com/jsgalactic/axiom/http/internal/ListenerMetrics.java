package com.jsgalactic.axiom.http.internal;

import com.jsgalactic.axiom.observability.Metrics;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Measurements of one listener that exist before, or without, admission: connections accepted and
 * turned away, bytes on the wire and the answers the listener gave itself (for example 400, 413 or
 * 431) without running a handler. Every series carries the listener's configured name, so listeners
 * that share an application can be told apart. Other tags are {@code direction}, {@code reason} and
 * {@code status}, which take values from fixed sets in the code, never anything a client sent.
 * Instruments are looked up once and kept; a failing {@link Metrics} implementation never fails a
 * connection: the first failure is logged and the measurement dropped.
 */
final class ListenerMetrics {
    static final String ACCEPTED = "axiom.http.connections.accepted";
    static final String REJECTED = "axiom.http.connections.rejected";
    static final String BYTES = "axiom.http.listener.bytes";
    static final String ANSWERS = "axiom.http.listener.answers";

    /** Why an accepted socket was closed at once. */
    enum Refusal {
        LIMIT("limit"), SHUTDOWN("shutdown");
        private final String tag;
        Refusal(String tag) { this.tag = tag; }
    }

    /** Records nothing. */
    static final ListenerMetrics DISABLED = new ListenerMetrics(Metrics.NOOP, "default");

    private static final System.Logger LOG = System.getLogger(ListenerMetrics.class.getName());

    private final Metrics metrics;
    private final String listener;
    private final boolean enabled;
    private final AtomicBoolean reported = new AtomicBoolean();
    private final ConcurrentHashMap<Integer, Metrics.Counter> answers = new ConcurrentHashMap<>();
    private final Metrics.Counter accepted;
    private final Metrics.Counter[] refused = new Metrics.Counter[Refusal.values().length];
    private final Metrics.Counter in;
    private final Metrics.Counter out;

    ListenerMetrics(Metrics metrics, String listener) {
        this.metrics = metrics;
        this.listener = listener;
        this.enabled = metrics != Metrics.NOOP;
        if (enabled) {
            accepted = counter(ACCEPTED, "listener", listener);
            for (var refusal : Refusal.values()) {
                refused[refusal.ordinal()] = counter(REJECTED, "listener", listener, "reason", refusal.tag);
            }
            in = counter(BYTES, "listener", listener, "direction", "in");
            out = counter(BYTES, "listener", listener, "direction", "out");
        } else {
            accepted = null;
            in = null;
            out = null;
        }
    }

    boolean enabled() { return enabled; }

    /** Counts a connection that got a slot. */
    void accepted() { add(accepted, 1); }

    /** Counts a connection closed at once instead of served. */
    void refused(Refusal refusal) { add(refused[refusal.ordinal()], 1); }

    /** Counts bytes read from the socket (encrypted bytes on a TLS listener). */
    void bytesIn(long bytes) { add(in, bytes); }

    /** Counts bytes written to the socket (encrypted bytes on a TLS listener). */
    void bytesOut(long bytes) { add(out, bytes); }

    /** Counts a response the listener gave without admission; the status comes from the transport's own set. */
    void answered(int status) {
        if (!enabled) { return; }
        try {
            answers.computeIfAbsent(status, code -> metrics.counter(ANSWERS, "listener", listener,
                    "status", Integer.toString(code))).increment();
        } catch (RuntimeException failure) { dropped(failure); }
    }

    private Metrics.Counter counter(String name, String... tags) {
        try { return metrics.counter(name, tags); }
        catch (RuntimeException failure) { dropped(failure); return null; }
    }

    private void add(Metrics.Counter counter, long amount) {
        if (counter == null) { return; }
        try { counter.add(amount); }
        catch (RuntimeException failure) { dropped(failure); }
    }

    private void dropped(RuntimeException failure) {
        if (reported.compareAndSet(false, true)) {
            LOG.log(System.Logger.Level.WARNING,
                    "The metrics implementation failed; this and later failing measurements are dropped", failure);
        }
    }
}
