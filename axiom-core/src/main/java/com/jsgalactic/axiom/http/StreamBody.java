package com.jsgalactic.axiom.http;

/**
 * Produces a streamed response body (see {@link Response#stream}). The runtime calls
 * {@link #writeTo} once, on the virtual thread that ran the handler and after the response head
 * was sent, so the request keeps its admission slot and deadline until the body returns. The
 * response ends when the method returns normally. An exception, including a
 * {@link StreamAbortedException} from a write, abandons it: the connection is closed without a
 * second response, because the head was already sent.
 */
@FunctionalInterface
public interface StreamBody {
    /**
     * Writes the body.
     *
     * @param out destination, valid until this method returns
     * @throws Exception to abandon the response
     */
    void writeTo(BodyWriter out) throws Exception;
}
