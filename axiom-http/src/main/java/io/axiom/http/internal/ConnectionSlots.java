package io.axiom.http.internal;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * Connection accounting for one listener. A connection holds a regular slot while it can carry
 * requests. Once its last response has been written and it only lingers (reading and discarding
 * input until the client finishes; see {@link HttpConnection}), it moves to a separate, smaller
 * pool when that pool has room, so lingering connections do not keep new clients out. Lingering is
 * bounded in time and in discarded bytes, so a lingering slot is always returned soon. When the
 * lingering pool is full, the connection lingers on its regular slot. At most
 * {@code maxOpen + maxLingering} connections are therefore open at once. Thread-safe.
 */
final class ConnectionSlots {
    private static final int OPEN = 0;
    private static final int LINGERING = 1;
    private static final int RELEASED = 2;
    private final int maxOpen;
    private final int maxLingering;
    private final AtomicInteger open = new AtomicInteger();
    private final AtomicInteger lingering = new AtomicInteger();

    ConnectionSlots(int maxOpen, int maxLingering) {
        this.maxOpen = maxOpen;
        this.maxLingering = maxLingering;
    }

    /** Takes a regular slot for a new connection, or returns null when all are taken. */
    Slot acquire() {
        if (open.incrementAndGet() > maxOpen) {
            open.decrementAndGet();
            return null;
        }
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
            return true;
        }

        /** Returns the slot to the pool it is in; later calls do nothing. */
        void release() {
            switch (state.getAndSet(RELEASED)) {
                case OPEN -> open.decrementAndGet();
                case LINGERING -> lingering.decrementAndGet();
                default -> { }
            }
        }
    }
}
