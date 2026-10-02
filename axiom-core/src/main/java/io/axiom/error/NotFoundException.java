package io.axiom.error;

import java.io.Serial;

/** A resource that does not exist (404). The runtime also uses this status for unmatched routes. */
public class NotFoundException extends AxiomException {
    @Serial private static final long serialVersionUID = 1L;

    /** Creates the exception with code {@code not_found}. */
    public NotFoundException() {
        super(404);
    }

    /**
     * Creates the exception with a specific safe code.
     *
     * @param code machine-readable code
     */
    public NotFoundException(String code) {
        super(404, code);
    }
}
