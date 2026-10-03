package com.jsgalactic.axiom.error;

import java.io.Serial;

/** An invalid response from an upstream server (502). */
public class BadGatewayException extends AxiomException {
    @Serial private static final long serialVersionUID = 1L;

    /** Creates the exception with code {@code bad_gateway}. */
    public BadGatewayException() {
        super(502);
    }

    /**
     * Creates the exception with a specific safe code.
     *
     * @param code machine-readable code
     */
    public BadGatewayException(String code) {
        super(502, code);
    }
}
