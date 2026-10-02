package io.axiom.error;

import java.io.Serial;

/** A request target longer than the server accepts (414). */
public class UriTooLongException extends AxiomException {
    @Serial private static final long serialVersionUID = 1L;

    /** Creates the exception with code {@code uri_too_long}. */
    public UriTooLongException() {
        super(414);
    }

    /**
     * Creates the exception with a specific safe code.
     *
     * @param code machine-readable code
     */
    public UriTooLongException(String code) {
        super(414, code);
    }
}
