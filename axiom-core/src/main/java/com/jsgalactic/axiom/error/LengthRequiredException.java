package com.jsgalactic.axiom.error;

import java.io.Serial;

/**
 * A request the application requires to declare its length (411). The HTTP listener does not
 * use this status: a request without Content-Length or Transfer-Encoding has an empty body.
 */
public class LengthRequiredException extends AxiomException {
    @Serial private static final long serialVersionUID = 1L;

    /** Creates the exception with code {@code length_required}. */
    public LengthRequiredException() {
        super(411);
    }

    /**
     * Creates the exception with a specific safe code.
     *
     * @param code machine-readable code
     */
    public LengthRequiredException(String code) {
        super(411, code);
    }
}
