package com.jsgalactic.axiom.http.internal;

import java.util.concurrent.atomic.AtomicLong;

/**
 * The request body bytes one listener may hold at once, across all its connections. A request takes
 * a {@link Reservation} when its head is accepted (a declared length in full) or as a chunked body
 * grows, and returns it when its response is final or its connection ends, so a listener's body
 * memory is bounded whatever the number of slow uploaders. Thread-safe: reservations are taken on
 * event loops and returned from whichever thread completes the request.
 */
final class BodyBudget {
    private final long limit;
    private final AtomicLong used = new AtomicLong();

    BodyBudget(long limit) { this.limit = limit; }

    /** A budget that never refuses; for connections outside a listener. */
    static BodyBudget unlimited() { return new BodyBudget(Long.MAX_VALUE); }

    /** Bytes currently reserved. */
    long used() { return used.get(); }

    /** The most bytes that can be reserved at once. */
    long limit() { return limit; }

    /** Starts an empty reservation for one request. */
    Reservation reservation() { return new Reservation(); }

    private boolean tryTake(long bytes) {
        for (;;) {
            long current = used.get();
            if (bytes > limit - current) { return false; }
            if (used.compareAndSet(current, current + bytes)) { return true; }
        }
    }

    /** The bytes one request holds. Returned exactly once however often {@link #release} is called. */
    final class Reservation {
        private long held;

        private Reservation() { }

        /** Reserves {@code bytes} more; false, changing nothing, when the budget cannot spare them. */
        synchronized boolean grow(long bytes) {
            if (!tryTake(bytes)) { return false; }
            held += bytes;
            return true;
        }

        /** Gives back everything above {@code keep}, once less is needed than was reserved. */
        synchronized void shrinkTo(long keep) {
            if (held > keep) {
                used.addAndGet(keep - held);
                held = keep;
            }
        }

        /** Returns everything this reservation holds; later calls do nothing. */
        synchronized void release() {
            used.addAndGet(-held);
            held = 0;
        }
    }
}
