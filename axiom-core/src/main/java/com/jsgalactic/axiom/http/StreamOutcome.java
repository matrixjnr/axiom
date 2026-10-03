package com.jsgalactic.axiom.http;

import java.time.Duration;
import java.util.Objects;

/**
 * How a streamed response body ended, given to the observers registered with
 * {@link Response#onStreamEnd}. It describes the body only: the head was sent long before and the
 * status cannot change.
 *
 * @param kind how the body ended
 * @param bytesWritten body bytes accepted by the writer
 * @param elapsed time from the moment the head was sent to the end of the body
 * @param failure what the body threw (including a {@link StreamAbortedException}), or null when it
 *        returned; a body that swallowed an abort has no failure, but its {@code kind} is not
 *        {@link Kind#COMPLETED}
 */
public record StreamOutcome(Kind kind, long bytesWritten, Duration elapsed, Throwable failure) {
    /** How a stream ended. */
    public enum Kind {
        /** The body returned and the stream was not aborted; the client receives a final chunk. */
        COMPLETED,
        /** The client left or stopped reading. */
        CLIENT_DISCONNECTED,
        /** The body tried to write more than its byte cap. */
        LIMIT_EXCEEDED,
        /** The request deadline, or the stream's own lifetime, passed. */
        TIMEOUT,
        /** The listener was closing and the grace period ran out, or the request was cancelled. */
        SHUTDOWN,
        /** The body threw an exception that was not an abort. */
        FAILED
    }

    /**
     * Creates an outcome.
     *
     * @param kind how the body ended
     * @param bytesWritten body bytes accepted by the writer, not negative
     * @param elapsed time since the head was sent
     * @param failure what the body threw, or null
     */
    public StreamOutcome {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(elapsed, "elapsed");
        if (bytesWritten < 0) { throw new IllegalArgumentException("bytesWritten must not be negative"); }
    }

    /**
     * Reports whether the body ended normally and the client received a final chunk.
     *
     * @return true for {@link Kind#COMPLETED}
     */
    public boolean completed() { return kind == Kind.COMPLETED; }
}
