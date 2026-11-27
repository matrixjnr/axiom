package com.jsgalactic.axiom.context;

/**
 * Marks a handler that reads its request body incrementally through
 * {@link Context#bodyReader()}. Registration with this type opts the route into the streaming
 * body path: the runtime hands the handler the reader as soon as the request head is accepted,
 * without buffering the body first, and pauses reading the connection when the handler falls
 * behind. A handler that does not implement this interface keeps the buffered body path, where
 * {@link com.jsgalactic.axiom.http.Request#body()} is complete before the handler runs.
 *
 * <p>A streaming handler's request has no buffered body: it must read the reader instead of
 * calling {@link Context#body(Class)} or {@link com.jsgalactic.axiom.http.Request#body()}. It must
 * read to the end of the body or call {@link com.jsgalactic.axiom.http.BodyReader#close()} when
 * it stops early; the runtime closes any reader the handler leaves open when the exchange ends.
 * Middleware on a streaming route sees the same restriction, including error handlers.
 */
@FunctionalInterface
public interface StreamingHandler extends Handler {
    // Marker only: the single abstract method is Handler.handle(Context).
}
