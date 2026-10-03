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
}
