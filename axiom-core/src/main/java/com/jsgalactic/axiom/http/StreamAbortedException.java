package com.jsgalactic.axiom.http;

import java.io.IOException;
import java.util.Objects;

/**
 * Thrown by a {@link BodyWriter} when a streamed response can no longer be written. The
 * {@linkplain #reason() reason} says why. After this exception the writer stays aborted: every
 * further write throws it again, so swallowing it does not revive the stream.
 */
public final class StreamAbortedException extends IOException {
    private static final long serialVersionUID = 1L;

    /** Why a stream was aborted. */
    public enum Reason {
        /** The client closed the connection or stopped reading for too long. */
        CLIENT_DISCONNECTED,
        /** The response would have exceeded its byte cap. */
        LIMIT_EXCEEDED,
        /** The request deadline passed. */
        TIMEOUT,
        /** The server is shutting down or the request was cancelled. */
        SHUTDOWN
    }

    private final Reason reason;

    /**
     * Creates the exception.
     *
     * @param reason why the stream was aborted
     */
    public StreamAbortedException(Reason reason) {
        super("Response stream aborted: " + Objects.requireNonNull(reason, "reason"));
        this.reason = reason;
    }

    /**
     * Returns why the stream was aborted.
     *
     * @return the reason
     */
    public Reason reason() { return reason; }
}
