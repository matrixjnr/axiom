package com.jsgalactic.axiom.error;

import java.io.Serial;
import java.time.Duration;
import java.util.Map;

/**
 * A server that is temporarily unable to handle the request (503).
 * An optional delay is sent as {@code Retry-After} in whole seconds, rounded up.
 */
public class ServiceUnavailableException extends AxiomException {
    @Serial private static final long serialVersionUID = 1L;

    /** Creates the exception with code {@code service_unavailable} and no Retry-After header. */
    public ServiceUnavailableException() {
        super(503);
    }

    /**
     * Creates the exception with a Retry-After delay.
     *
     * @param retryAfter non-negative delay of at most one day
     * @throws IllegalArgumentException for a negative or longer delay
     */
    public ServiceUnavailableException(Duration retryAfter) {
        this(retryAfter, "service_unavailable");
    }

    /**
     * Creates the exception with a Retry-After delay and a specific safe code.
     *
     * @param retryAfter non-negative delay of at most one day
     * @param code machine-readable code
     * @throws IllegalArgumentException for a negative or longer delay
     */
    public ServiceUnavailableException(Duration retryAfter, String code) {
        super(503, code, Map.of("Retry-After", RetryAfter.seconds(retryAfter)));
    }
}
