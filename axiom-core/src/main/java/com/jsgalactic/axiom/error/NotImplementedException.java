package com.jsgalactic.axiom.error;

import java.io.Serial;

/** Functionality the server does not support (501). */
public class NotImplementedException extends AxiomException {
    @Serial private static final long serialVersionUID = 1L;

    /** Creates the exception with code {@code not_implemented}. */
    public NotImplementedException() {
        super(501);
    }

    /**
     * Creates the exception with a specific safe code.
     *
     * @param code machine-readable code
     */
    public NotImplementedException(String code) {
        super(501, code);
    }
}
