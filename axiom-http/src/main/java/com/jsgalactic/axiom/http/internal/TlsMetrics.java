package com.jsgalactic.axiom.http.internal;

import com.jsgalactic.axiom.observability.Metrics;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReferenceArray;

/**
 * The listener's measurements of TLS. The only tag is {@code outcome} and it takes one of the fixed
 * values of {@link Handshake} or {@link Reload}; nothing a client sends (protocol, cipher, server
 * name, certificate subject, address) is ever a tag. A failing {@link Metrics} implementation never
 * affects a connection: the first failure is logged and the measurement dropped.
 */
final class TlsMetrics {
    static final String HANDSHAKES = "axiom.http.tls.handshakes";
    static final String RELOADS = "axiom.http.tls.reloads";

    /** How a connection's handshake ended. */
    enum Handshake {
        /** The handshake finished and the connection carries HTTP. */
        COMPLETED("completed"),
        /** The peer failed validation, offered nothing in common or broke the protocol. */
        FAILED("failed"),
        /** The handshake did not finish within the handshake timeout. */
        TIMEOUT("timeout"),
        /** The peer sent something that is not TLS, such as plain HTTP. */
        PLAINTEXT("plaintext"),
        /** The connection closed before the handshake finished. */
        CLOSED("closed");

        private final String tag;
        Handshake(String tag) { this.tag = tag; }
    }

    /** How a key material reload ended. */
    enum Reload {
        /** New material is in use. */
        COMPLETED("completed"),
        /** The new material was rejected and the previous material stays in use. */
        FAILED("failed");

        private final String tag;
        Reload(String tag) { this.tag = tag; }
    }

    private static final System.Logger LOG = System.getLogger(TlsMetrics.class.getName());

    private final Metrics metrics;
    private final boolean enabled;
    private final AtomicBoolean reported = new AtomicBoolean();
    private final AtomicReferenceArray<Metrics.Counter> handshakes =
            new AtomicReferenceArray<>(Handshake.values().length);
    private final AtomicReferenceArray<Metrics.Counter> reloads = new AtomicReferenceArray<>(Reload.values().length);

    TlsMetrics(Metrics metrics) {
        this.metrics = metrics;
        this.enabled = metrics != Metrics.NOOP;
    }

    void handshake(Handshake outcome) {
        count(handshakes, outcome.ordinal(), HANDSHAKES, outcome.tag);
    }

    void reload(Reload outcome) {
        count(reloads, outcome.ordinal(), RELOADS, outcome.tag);
    }

    private void count(AtomicReferenceArray<Metrics.Counter> cache, int slot, String name, String outcome) {
        if (!enabled) { return; }
        try {
            var counter = cache.get(slot);
            if (counter == null) {
                counter = metrics.counter(name, "outcome", outcome);
                cache.set(slot, counter);
            }
            counter.increment();
        } catch (RuntimeException failure) {
            if (reported.compareAndSet(false, true)) {
                LOG.log(System.Logger.Level.WARNING,
                        "The metrics implementation failed; this and later failing measurements are dropped", failure);
            }
        }
    }
}
