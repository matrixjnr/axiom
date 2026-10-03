package com.jsgalactic.axiom.http;

import java.io.IOException;
import java.util.Objects;

/**
 * The destination of a server-sent event stream (see {@link Response#sse}). It has the lifecycle
 * and the failure behavior of the {@link BodyWriter} it wraps: valid only while the
 * {@link EventStreamBody} runs, blocking under backpressure, and throwing a
 * {@link StreamAbortedException} once the stream cannot be written. Each call sends one complete
 * event in a single write.
 */
public interface EventSink {
    /**
     * Sends an event.
     *
     * @param event the event
     * @throws StreamAbortedException if the stream was aborted
     * @throws IOException if the stream cannot be written for another reason
     */
    void send(ServerSentEvent event) throws IOException;

    /**
     * Sends an event that has only data.
     *
     * @param data text of the event; line breaks become separate {@code data:} lines
     * @throws StreamAbortedException if the stream was aborted
     * @throws IOException if the stream cannot be written for another reason
     */
    default void send(String data) throws IOException { send(ServerSentEvent.data(data)); }

    /**
     * Sends a comment, which clients ignore. Line breaks in the text become separate comment lines,
     * so the text cannot inject fields.
     *
     * @param text comment text
     * @throws StreamAbortedException if the stream was aborted
     * @throws IOException if the stream cannot be written for another reason
     */
    void comment(String text) throws IOException;

    /**
     * Sends a comment that keeps the connection from looking idle to clients and intermediaries.
     * Call it at an interval shorter than their idle timeouts when events are rare. It also
     * discovers a client that has gone away, by failing with a {@link StreamAbortedException}.
     *
     * @throws StreamAbortedException if the stream was aborted
     * @throws IOException if the stream cannot be written for another reason
     */
    default void keepAlive() throws IOException { comment("keep-alive"); }

    /**
     * Returns the number of bytes sent so far, counted against the stream's byte cap.
     *
     * @return bytes written
     */
    long bytesWritten();

    /**
     * Reports whether the listener has started to shut down; see {@link BodyWriter#shutdownRequested()}.
     * An event stream that sees it should send a last event and return, which ends the stream
     * cleanly; browsers then reconnect after the {@code retry} delay.
     *
     * @return true once shutdown has begun
     */
    default boolean shutdownRequested() { return false; }

    /**
     * Runs an action once when shutdown begins; see {@link BodyWriter#onShutdown(Runnable)}.
     *
     * @param action what to run; quick and non-blocking
     */
    default void onShutdown(Runnable action) { Objects.requireNonNull(action, "action"); }

    /**
     * Wraps a body writer as an event sink.
     *
     * @param out destination of the encoded events
     * @return a sink writing UTF-8 encoded events to it
     */
    static EventSink of(BodyWriter out) {
        Objects.requireNonNull(out, "out");
        return new EventSink() {
            @Override public void send(ServerSentEvent event) throws IOException {
                out.write(Objects.requireNonNull(event, "event").encode());
            }

            @Override public void comment(String text) throws IOException {
                var line = new StringBuilder();
                ServerSentEvent.lines(line, ": ", Objects.requireNonNull(text, "text"));
                out.write(line.append('\n').toString());
            }

            @Override public long bytesWritten() { return out.bytesWritten(); }

            @Override public boolean shutdownRequested() { return out.shutdownRequested(); }

            @Override public void onShutdown(Runnable action) { out.onShutdown(action); }
        };
    }
}
