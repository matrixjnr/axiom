package com.jsgalactic.axiom.error;

import java.io.Serial;

/** A request body larger than the application's configured limit (413). */
public class PayloadTooLargeException extends AxiomException {
    @Serial private static final long serialVersionUID = 1L;

    /** Creates the exception with code {@code content_too_large}. */
    public PayloadTooLargeException() {
        this("content_too_large");
    }

    /**
     * Creates the exception with a specific safe code.
     *
     * @param code machine-readable code
     */
    public PayloadTooLargeException(String code) {
        super(413, code);
    }
}
