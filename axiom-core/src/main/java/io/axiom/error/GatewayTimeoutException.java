package io.axiom.error;

import java.io.Serial;

/** An upstream server that did not answer in time (504). */
public class GatewayTimeoutException extends AxiomException {
    @Serial private static final long serialVersionUID = 1L;

    /** Creates the exception with code {@code gateway_timeout}. */
    public GatewayTimeoutException() {
        super(504);
    }

    /**
     * Creates the exception with a specific safe code.
     *
     * @param code machine-readable code
     */
    public GatewayTimeoutException(String code) {
        super(504, code);
    }
}
