package com.jsgalactic.axiom.error;

import java.io.Serial;

/** An authenticated caller lacks permission (403). */
public class ForbiddenException extends AxiomException {
    @Serial private static final long serialVersionUID = 1L;

    /** Creates the exception with code {@code forbidden}. */
    public ForbiddenException() {
        super(403);
    }

    /**
     * Creates the exception with a specific safe code.
     *
     * @param code machine-readable code
     */
    public ForbiddenException(String code) {
        super(403, code);
    }
}
