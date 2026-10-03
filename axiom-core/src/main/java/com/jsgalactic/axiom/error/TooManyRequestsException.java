package com.jsgalactic.axiom.error;

import java.io.Serial;
import java.time.Duration;
import java.util.Map;

/**
 * A caller that sent too many requests in a given time (429).
 * An optional delay is sent as {@code Retry-After} in whole seconds, rounded up.
 */
public class TooManyRequestsException extends AxiomException {
    @Serial private static final long serialVersionUID = 1L;

    /** Creates the exception with code {@code too_many_requests} and no Retry-After header. */
    public TooManyRequestsException() {
        super(429);
    }

    /**
     * Creates the exception with a Retry-After delay.
     *
     * @param retryAfter non-negative delay of at most one day
     * @throws IllegalArgumentException for a negative or longer delay
     */
    public TooManyRequestsException(Duration retryAfter) {
        this(retryAfter, "too_many_requests");
    }

    /**
     * Creates the exception with a Retry-After delay and a specific safe code.
     *
     * @param retryAfter non-negative delay of at most one day
     * @param code machine-readable code
     * @throws IllegalArgumentException for a negative or longer delay
     */
    public TooManyRequestsException(Duration retryAfter, String code) {
        super(429, code, Map.of("Retry-After", RetryAfter.seconds(retryAfter)));
    }
}
