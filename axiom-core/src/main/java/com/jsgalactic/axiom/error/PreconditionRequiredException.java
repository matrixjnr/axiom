package com.jsgalactic.axiom.error;

import java.io.Serial;

/** A request that must be conditional, for example carry If-Match (428). */
public class PreconditionRequiredException extends AxiomException {
    @Serial private static final long serialVersionUID = 1L;

    /** Creates the exception with code {@code precondition_required}. */
    public PreconditionRequiredException() {
        super(428);
    }

    /**
     * Creates the exception with a specific safe code.
     *
     * @param code machine-readable code
     */
    public PreconditionRequiredException(String code) {
        super(428, code);
    }
}
