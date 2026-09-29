package io.axiom.context;

import io.axiom.http.Request;
import io.axiom.http.Response;

/** Request-scoped response settings. A context must not be shared across threads. */
public interface Context {
    /**
     * Returns the immutable request metadata.
     *
     * @return the immutable request metadata
     */
    Request request();
    /**
     * Returns the case-sensitive HTTP method.
     *
     * @return the case-sensitive HTTP method
     */
    default String method() {
        return request().method();
    }

    /**
     * Returns the raw path, without decoding or normalization.
     *
     * @return the raw path, without decoding or normalization
     */
    default String path() {
        return request().path();
    }

    /**
     * Sets the status for subsequent response mapping.
     *
     * @param status final HTTP status (200-599)
     * @return this context
     */
    Context status(int status);

    /**
     * Maps a body using the current status (200 by default).
     * Strings use UTF-8 text and byte arrays use application/octet-stream.
     * Other objects are retained for a future codec layer; no serialization occurs.
     * Null without an explicit status produces 204.
     * @param body returned body, or null
     * @return a response snapshot
     */
    Response response(Object body);

    /**
     * Creates a text response using the current settings.
     *
     * @param text non-null text
     * @return a response using the current status
     */
    default Response text(String text) {
        return response(java.util.Objects.requireNonNull(text, "text"));
    }

    /**
     * Returns a 204 response with no body.
     *
     * @return a 204 response with no body
     */
    default Response noContent() {
        return Response.of(204, null);
    }
}
