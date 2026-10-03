package com.jsgalactic.axiom.error;

import java.io.Serial;

/** Well-formed content that cannot be processed (422). */
public class UnprocessableContentException extends AxiomException {
    @Serial private static final long serialVersionUID = 1L;

    /** Creates the exception with code {@code unprocessable_content}. */
    public UnprocessableContentException() {
        super(422);
    }

    /**
     * Creates the exception with a specific safe code.
     *
     * @param code machine-readable code
     */
    public UnprocessableContentException(String code) {
        super(422, code);
    }
}
