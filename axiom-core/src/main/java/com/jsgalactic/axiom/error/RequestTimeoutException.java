package com.jsgalactic.axiom.error;

import java.io.Serial;

/** A request that was not received in time (408). */
public class RequestTimeoutException extends AxiomException {
    @Serial private static final long serialVersionUID = 1L;

    /** Creates the exception with code {@code request_timeout}. */
    public RequestTimeoutException() {
        super(408);
    }

    /**
     * Creates the exception with a specific safe code.
     *
     * @param code machine-readable code
     */
    public RequestTimeoutException(String code) {
        super(408, code);
    }
}
