package com.jsgalactic.axiom.http;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

/**
 * The destination of a streamed response body (see {@link Response#stream}). A writer is valid
 * only while the {@link StreamBody} that received it runs, on the virtual thread that runs the
 * handler; it is not thread-safe and must not be kept or used afterwards.
 *
 * <p>Every write hands its bytes to the connection at once, so a write is also a flush: each call
 * becomes one chunk on the wire (large arrays are split into bounded pieces). A write blocks while
 * the client is not reading fast enough, and for at most the remaining request deadline. When the
 * stream can no longer be written, whether because the client disconnected, the byte cap was
 * exceeded, the deadline passed or the server is shutting down, the write throws a
 * {@link StreamAbortedException}. The body should let it propagate or return: the response is then
 * abandoned, resources are released and nothing further is sent to the client.
 */
public interface BodyWriter {
    /**
     * Writes a slice of an array, copying it before returning.
     *
     * @param bytes source array
     * @param offset index of the first byte
     * @param length number of bytes; zero writes nothing
     * @throws StreamAbortedException if the stream was aborted
     * @throws IOException if the stream cannot be written for another reason
     * @throws IndexOutOfBoundsException if the slice is outside the array
     */
    void write(byte[] bytes, int offset, int length) throws IOException;

    /**
     * Writes a whole array.
     *
     * @param bytes content
     * @throws StreamAbortedException if the stream was aborted
     * @throws IOException if the stream cannot be written for another reason
     */
    default void write(byte[] bytes) throws IOException {
        Objects.requireNonNull(bytes, "bytes");
        write(bytes, 0, bytes.length);
    }

    /**
     * Writes text encoded as UTF-8.
     *
     * @param text content
     * @throws StreamAbortedException if the stream was aborted
     * @throws IOException if the stream cannot be written for another reason
     */
    default void write(String text) throws IOException {
        write(Objects.requireNonNull(text, "text").getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Returns the number of body bytes accepted so far, counted against the stream's byte cap.
     *
     * @return bytes written
     */
    long bytesWritten();

    /**
     * Reports whether the listener has started to shut down. A stream is not cut off at once
     * then: it keeps the shutdown grace period, during which writes still work, and is aborted
     * with {@link StreamAbortedException.Reason#SHUTDOWN} only if it is still running afterwards.
     * A body that sees shutdown should send what completes it, such as a final event, and
     * return, which ends the response with a final chunk and closes the connection. A body that
     * ignores it is cut when the grace period ends.
     *
     * @return true once shutdown has begun; always false for a writer that is not connected to a listener
     */
    default boolean shutdownRequested() { return false; }

    /**
     * Runs an action once when shutdown begins, or at once on the calling thread if it already
     * has. Use it to wake a body that is blocked on something other than a write, for example to
     * complete a future or unsubscribe so that a blocking poll returns. The action runs on a
     * thread of the server (often its event loop) when shutdown begins, so it must be quick and
     * must not block; an exception it throws is logged and ignored. Actions never run for a
     * writer that is not connected to a listener.
     *
     * @param action what to run
     */
    default void onShutdown(Runnable action) { Objects.requireNonNull(action, "action"); }
}
