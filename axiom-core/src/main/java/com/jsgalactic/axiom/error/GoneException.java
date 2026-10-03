package com.jsgalactic.axiom.error;

import java.io.Serial;

/** A resource that existed but was permanently removed (410). */
public class GoneException extends AxiomException {
    @Serial private static final long serialVersionUID = 1L;

    /** Creates the exception with code {@code gone}. */
    public GoneException() {
        super(410);
    }

    /**
     * Creates the exception with a specific safe code.
     *
     * @param code machine-readable code
     */
    public GoneException(String code) {
        super(410, code);
    }
}
