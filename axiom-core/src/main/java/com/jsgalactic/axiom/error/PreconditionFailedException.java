package com.jsgalactic.axiom.error;

import java.io.Serial;

/** A conditional request whose precondition evaluated to false (412). */
public class PreconditionFailedException extends AxiomException {
    @Serial private static final long serialVersionUID = 1L;

    /** Creates the exception with code {@code precondition_failed}. */
    public PreconditionFailedException() {
        super(412);
    }

    /**
     * Creates the exception with a specific safe code.
     *
     * @param code machine-readable code
     */
    public PreconditionFailedException(String code) {
        super(412, code);
    }
}
