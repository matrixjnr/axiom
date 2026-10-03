package com.jsgalactic.axiom.error;

import java.io.Serial;

/** A request that conflicts with the current resource state (409). */
public class ConflictException extends AxiomException {
    @Serial private static final long serialVersionUID = 1L;

    /** Creates the exception with code {@code conflict}. */
    public ConflictException() {
        super(409);
    }

    /**
     * Creates the exception with a specific safe code.
     *
     * @param code machine-readable code
     */
    public ConflictException(String code) {
        super(409, code);
    }
}
