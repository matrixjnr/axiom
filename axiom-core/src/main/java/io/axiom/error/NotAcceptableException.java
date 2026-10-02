package io.axiom.error;

import java.io.Serial;

/** No acceptable representation for the request's Accept header (406). */
public class NotAcceptableException extends AxiomException {
    @Serial private static final long serialVersionUID = 1L;

    /** Creates the exception with code {@code not_acceptable}. */
    public NotAcceptableException() {
        super(406);
    }

    /**
     * Creates the exception with a specific safe code.
     *
     * @param code machine-readable code
     */
    public NotAcceptableException(String code) {
        super(406, code);
    }
}
