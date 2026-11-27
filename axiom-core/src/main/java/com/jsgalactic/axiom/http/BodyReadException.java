package com.jsgalactic.axiom.http;

import java.io.IOException;
import java.io.Serial;
import java.util.Objects;

/**
 * A read from a {@link BodyReader} that could not complete because its request ended early.
 * Callers that keep reading after one of these get the same reason until the reader is closed.
 */
public final class BodyReadException extends IOException {
    @Serial private static final long serialVersionUID = 1L;

    /** Why a read stopped. */
    public enum Reason {
        /** The request or the reader was cancelled; its bytes were released. */
        CANCELLED,
        /** The request's deadline expired or its thread was interrupted. */
        INTERRUPTED,
        /** More bytes were delivered than the body's configured limit. */
        LIMIT_EXCEEDED
    }

    private final Reason reason;

    /**
     * Creates a failure with the given reason.
     *
     * @param reason why the read stopped
     */
    public BodyReadException(Reason reason) {
        super("Body read failed: " + (reason == null ? "unknown" : reason.name()));
        this.reason = Objects.requireNonNull(reason, "reason");
    }

    /**
     * Returns the reason.
     *
     * @return why the read stopped
     */
    public Reason reason() { return reason; }

}
