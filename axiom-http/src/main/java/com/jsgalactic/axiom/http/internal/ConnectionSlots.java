package com.jsgalactic.axiom.http.internal;

import com.jsgalactic.axiom.observability.Metrics;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Connection accounting for one listener. A connection holds a regular slot while it can carry
 * requests. Once its last response has been written and it only lingers (reading and discarding
 * input until the client finishes; see {@link HttpConnection}), it moves to a separate, smaller
 * pool when that pool has room, so lingering connections do not keep new clients out. Lingering is
 * bounded in time and in discarded bytes, so a lingering slot is always returned soon. When the
 * lingering pool is full, the connection lingers on its regular slot. At most
 * {@code maxOpen + maxLingering} connections are therefore open at once. Thread-safe.
 *
 * <p>The gauge {@value #CONNECTIONS}, tagged {@code state=open} or {@code state=lingering}, follows
 * the two pools, so an operator can compare the open sockets with the descriptor limit. The tag takes
 * only these two fixed values.
 */
final class ConnectionSlots {
    static final String CONNECTIONS = "axiom.http.connections";
    private static final System.Logger LOG = System.getLogger(ConnectionSlots.class.getName());
    private static final int OPEN = 0;
    private static final int LINGERING = 1;
    private static final int RELEASED = 2;
    private final int maxOpen;
    private final int maxLingering;
    private final AtomicInteger open = new AtomicInteger();
    private final AtomicInteger lingering = new AtomicInteger();
    private final Metrics.Gauge openGauge;
    private final Metrics.Gauge lingeringGauge;
    private final AtomicBoolean reported = new AtomicBoolean();

    ConnectionSlots(int maxOpen, int maxLingering) { this(maxOpen, maxLingering, Metrics.NOOP); }

    ConnectionSlots(int maxOpen, int maxLingering, Metrics metrics) {
        this.maxOpen = maxOpen;
        this.maxLingering = maxLingering;
        if (metrics == Metrics.NOOP) {
            openGauge = null;
            lingeringGauge = null;
        } else {
            openGauge = gauge(metrics, "open");
            lingeringGauge = gauge(metrics, "lingering");
        }
    }

    // A failing Metrics implementation never fails a connection: the first failure is logged.
    private Metrics.Gauge gauge(Metrics metrics, String state) {
        try { return metrics.gauge(CONNECTIONS, "state", state); }
        catch (RuntimeException failure) { dropped(failure); return null; }
    }

    private void add(Metrics.Gauge gauge, long delta) {
        if (gauge == null) { return; }
        try { gauge.add(delta); }
        catch (RuntimeException failure) { dropped(failure); }
    }

    private void dropped(RuntimeException failure) {
        if (reported.compareAndSet(false, true)) {
            LOG.log(System.Logger.Level.WARNING,
                    "The metrics implementation failed; this and later failing measurements are dropped", failure);
        }
    }

    /** Takes a regular slot for a new connection, or returns null when all are taken. */
    Slot acquire() {
        if (open.incrementAndGet() > maxOpen) {
            open.decrementAndGet();
            return null;
        }
        add(openGauge, 1);
        return new Slot();
    }

    /** Connections holding a regular slot. */
    int open() { return open.get(); }

    /** Connections holding a lingering slot. */
    int lingering() { return lingering.get(); }

    /** One connection's slot; released exactly once, whichever pool it is in. */
    final class Slot {
        private final AtomicInteger state = new AtomicInteger(OPEN);

        private Slot() { }

        /**
         * Moves the connection to the lingering pool and frees its regular slot. Returns false, and
         * keeps the regular slot, when the lingering pool is full or the slot is not open.
         */
        boolean linger() {
            if (lingering.incrementAndGet() > maxLingering || !state.compareAndSet(OPEN, LINGERING)) {
                lingering.decrementAndGet();
                return false;
            }
            open.decrementAndGet();
            add(openGauge, -1);
            add(lingeringGauge, 1);
            return true;
        }

        /** Returns the slot to the pool it is in; later calls do nothing. */
        void release() {
            switch (state.getAndSet(RELEASED)) {
                case OPEN -> { open.decrementAndGet(); add(openGauge, -1); }
                case LINGERING -> { lingering.decrementAndGet(); add(lingeringGauge, -1); }
                default -> { }
            }
        }
    }
}
