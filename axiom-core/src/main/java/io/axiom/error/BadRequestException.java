package io.axiom.error;

import java.io.Serial;

/** A request the server cannot process because it is malformed (400). */
public class BadRequestException extends AxiomException {
    @Serial private static final long serialVersionUID = 1L;

    /** Creates the exception with code {@code bad_request}. */
    public BadRequestException() {
        super(400);
    }

    /**
     * Creates the exception with a specific safe code.
     *
     * @param code machine-readable code
     */
    public BadRequestException(String code) {
        super(400, code);
    }
}
