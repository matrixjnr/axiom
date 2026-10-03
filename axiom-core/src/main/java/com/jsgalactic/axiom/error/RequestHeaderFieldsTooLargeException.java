package com.jsgalactic.axiom.error;

import java.io.Serial;

/** A request whose header section is too large (431). */
public class RequestHeaderFieldsTooLargeException extends AxiomException {
    @Serial private static final long serialVersionUID = 1L;

    /** Creates the exception with code {@code request_header_fields_too_large}. */
    public RequestHeaderFieldsTooLargeException() {
        super(431);
    }

    /**
     * Creates the exception with a specific safe code.
     *
     * @param code machine-readable code
     */
    public RequestHeaderFieldsTooLargeException(String code) {
        super(431, code);
    }
}
