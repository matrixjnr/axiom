package com.jsgalactic.axiom.test;

import java.time.Duration;
import java.util.Objects;

/**
 * How {@link TestClient#stream} models the buffering between a handler's writes and the client's
 * reads. The queue holds unread chunks. A writer waits before queuing a piece while the channel
 * is <em>unwritable</em>: it becomes unwritable when the unread bytes reach
 * {@code highWaterBytes} and writable again when the reader brings them below
 * {@code lowWaterBytes}. A write larger than {@code pieceBytes} is queued as several chunks of at
 * most that size, which the reader receives one by one. A wait ends with a
 * {@code StreamAbortedException} when the reader takes nothing for {@code stallTimeout}
 * ({@code CLIENT_DISCONNECTED}) or at the request deadline ({@code TIMEOUT}).
 *
 * <p>{@link #HAND_OFF} is the default: a writer is never more than one write ahead of the reader and
 * writes are never split, which makes tests that read chunk by chunk fully deterministic.
 * {@link #listener()} uses the HTTP listener's numbers (32 and 128 KiB water marks, 16 KiB pieces,
 * a 30 second stall bound), so that a test sees the same blocking points as a real socket would
 * give, apart from the socket buffers of the operating system, which hold more.
 *
 * @param highWaterBytes unread bytes at which writers start to wait; positive
 * @param lowWaterBytes unread bytes below which waiting writers continue; positive, at most the high water mark
 * @param pieceBytes largest chunk queued by one step of a write; positive
 * @param stallTimeout how long a writer waits for the reader to take anything before the client counts
 *        as gone; positive, or null for none (only the request deadline then ends the wait)
 */
public record StreamBuffering(int highWaterBytes, int lowWaterBytes, int pieceBytes, Duration stallTimeout) {
    /** One chunk in flight, no splitting and no stall bound. */
    public static final StreamBuffering HAND_OFF = new StreamBuffering(1, 1, Integer.MAX_VALUE, null);

    /**
     * Creates a buffering model.
     *
     * @param highWaterBytes unread bytes at which writers start to wait; positive
     * @param lowWaterBytes unread bytes below which waiting writers continue; positive, at most the high water mark
     * @param pieceBytes largest chunk queued by one step of a write; positive
     * @param stallTimeout how long a writer waits for the reader before the client counts as gone, or null
     */
    public StreamBuffering {
        if (highWaterBytes < 1 || lowWaterBytes < 1 || lowWaterBytes > highWaterBytes || pieceBytes < 1) {
            throw new IllegalArgumentException(
                    "Water marks and piece size must be positive and the low water mark at most the high one");
        }
        if (stallTimeout != null && (stallTimeout.isZero() || stallTimeout.isNegative())) {
            throw new IllegalArgumentException("The stall timeout must be positive");
        }
    }

    /**
     * The numbers of the HTTP listener: 128 KiB high and 32 KiB low water marks, 16 KiB pieces and a
     * 30 second stall bound.
     *
     * @return the listener's buffering
     */
    public static StreamBuffering listener() {
        return new StreamBuffering(128 * 1024, 32 * 1024, 16 * 1024, Duration.ofSeconds(30));
    }

    /**
     * A buffer of the given size: the low water mark is a quarter of it, pieces are at most 16 KiB
     * and there is no stall bound.
     *
     * @param highWaterBytes unread bytes at which writers start to wait; positive
     * @return the buffering
     */
    public static StreamBuffering ofBytes(int highWaterBytes) {
        return new StreamBuffering(highWaterBytes, Math.max(1, highWaterBytes / 4),
                Math.min(16 * 1024, highWaterBytes), null);
    }

    /**
     * Returns a copy with another stall bound.
     *
     * @param timeout how long a writer waits for the reader before the client counts as gone, or null for none
     * @return the buffering
     */
    public StreamBuffering withStallTimeout(Duration timeout) {
        return new StreamBuffering(highWaterBytes, lowWaterBytes, pieceBytes, timeout);
    }

    @Override public String toString() {
        return "StreamBuffering[high=" + highWaterBytes + ", low=" + lowWaterBytes + ", piece=" + pieceBytes
                + ", stall=" + Objects.toString(stallTimeout, "none") + "]";
    }
}
