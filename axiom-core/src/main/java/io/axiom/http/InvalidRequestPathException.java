package io.axiom.http;

import java.io.Serial;

/**
 * Reports a request path that {@link Request} refuses to represent. HTTP listeners answer
 * such requests with 400 Bad Request without routing them or invoking a handler.
 */
public final class InvalidRequestPathException extends IllegalArgumentException {
    @Serial private static final long serialVersionUID = 1L;

    /**
     * Creates the exception.
     *
     * @param message description of the rejected path
     */
    public InvalidRequestPathException(String message) {
        super(message);
    }
}
