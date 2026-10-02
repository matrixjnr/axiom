package io.axiom.error;

import java.io.Serial;

/** A request body sent without a declared length (411). */
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
