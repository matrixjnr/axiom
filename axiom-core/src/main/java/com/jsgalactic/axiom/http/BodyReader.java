package com.jsgalactic.axiom.http;

import java.io.IOException;
import java.util.Objects;

/**
 * A source of request body bytes that a handler reads incrementally. A reader is valid while the
 * handler that obtained it from {@link com.jsgalactic.axiom.context.Context#bodyReader()} runs; it
 * is confined to that handler's thread, except for {@link #close()}, which a transport may call
 * from another thread to cancel a request.
 *
 * <p>A read blocks until at least one byte is available, returns the number of bytes copied, or
 * returns -1 once the body has ended. A zero-length request ends at once. Reads honour
 * interruption: an interrupted handler gets an {@link IOException} with its interrupt flag set,
 * as it does for a timed-out request. Every byte is delivered at most once and in wire order.
 *
 * <p>{@link #pause()} and {@link #resume()} control delivery from the transport. Pausing asks it
 * to stop reading bytes from the connection; resuming asks it to continue. A transport also pauses
 * and resumes on its own at buffer water marks, so a handler reading slowly cannot make it buffer
 * an unbounded body. Pausing never discards bytes that were already accepted by a read.
 */
public interface BodyReader extends AutoCloseable {
    /**
     * Copies up to {@code length} bytes into the array, blocking until a byte is available or the
     * body ends.
     *
     * @param bytes destination array
     * @param offset first index in the destination
     * @param length maximum number of bytes; zero returns zero
     * @return bytes copied, or -1 at the end of the body
     * @throws IOException if the reader was closed, the request was cancelled or the thread was interrupted
     * @throws IndexOutOfBoundsException if the slice is outside the array
     */
    int read(byte[] bytes, int offset, int length) throws IOException;

    /**
     * Copies up to the array's length into it.
     *
     * @param bytes destination array
     * @return bytes copied, or -1 at the end of the body
     * @throws IOException if {@link #read(byte[], int, int)} fails
     */
    default int read(byte[] bytes) throws IOException {
        Objects.requireNonNull(bytes, "bytes");
        return read(bytes, 0, bytes.length);
    }

    /**
     * Asks the transport to stop taking bytes from the connection. Idempotent. Buffered readers
     * block subsequent reads until {@link #resume()} is called, so tests can hold a hand-off
     * deterministically.
     */
    void pause();

    /**
     * Asks the transport to take bytes again. Idempotent, and wakes a read waiting on a paused
     * buffered reader.
     */
    void resume();

    /**
     * Returns whether delivery is currently paused, whether by the handler or by the transport's
     * water marks.
     *
     * @return true while reads from the connection are suspended
     */
    boolean paused();

    /**
     * Returns the number of bytes this reader has handed to the handler.
     *
     * @return bytes read
     */
    long bytesRead();

    /**
     * Cancels the reader: any buffered bytes are dropped, reads blocked on it fail, and any
     * temporary storage it owns is released. Idempotent, callable from any thread, and never
     * throws. A handler should call it when it stops reading a body before its end.
     */
    @Override
    void close();

    /**
     * Creates a reader over a copy of {@code bytes}. Reads hand over the whole array as soon as
     * it is available; {@link #pause()} and {@link #resume()} gate those reads so that a test can
     * model a slow handler without timing dependence. In-memory execution and the test client use
     * this hand-off; no temporary storage is involved.
     *
     * @param bytes body content; copied
     * @return a reader positioned at the first byte
     */
    static BodyReader of(byte[] bytes) {
        return new BufferedBodyReader(Objects.requireNonNull(bytes, "bytes").clone());
    }
}
