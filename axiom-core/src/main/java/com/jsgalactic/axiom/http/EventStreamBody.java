package com.jsgalactic.axiom.http;

/**
 * Produces a server-sent event stream (see {@link Response#sse}). It runs like a
 * {@link StreamBody}: once, after the head was sent, on the handler's virtual thread, holding the
 * admission slot and the request deadline. Returning ends the stream; an exception abandons it.
 */
@FunctionalInterface
public interface EventStreamBody {
    /**
     * Sends the events.
     *
     * @param events destination, valid until this method returns
     * @throws Exception to abandon the response
     */
    void run(EventSink events) throws Exception;
}
