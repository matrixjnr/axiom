package com.jsgalactic.axiom.error;

import java.io.Serial;

/** An unexpected server failure reported deliberately (500). */
public class InternalServerErrorException extends AxiomException {
    @Serial private static final long serialVersionUID = 1L;

    /** Creates the exception with code {@code internal_server_error}. */
    public InternalServerErrorException() {
        super(500);
    }

    /**
     * Creates the exception with a specific safe code.
     *
     * @param code machine-readable code
     */
    public InternalServerErrorException(String code) {
        super(500, code);
    }
}
