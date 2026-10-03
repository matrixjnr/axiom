package com.jsgalactic.axiom.http;

import java.time.Duration;
import java.util.Objects;

/**
 * One event of a server-sent event stream (see {@link Response#sse}). Immutable.
 *
 * <p>An event is framed on the wire as {@code event:}, {@code id:}, {@code retry:} and one
 * {@code data:} line per line of the data, followed by a blank line. Line breaks are what separate
 * fields in this format, so the text of a field must never be able to start a new one:
 * <ul>
 * <li>the data may contain line breaks (CR, LF or CRLF); each line is sent as its own
 *     {@code data:} line, and the client joins them again with LF, so no input can add a field;</li>
 * <li>the event name and the id may not contain CR or LF (nor NUL, for the id), and such a value is
 *     rejected with {@link IllegalArgumentException} rather than altered.</li>
 * </ul>
 */
public final class ServerSentEvent {
    private final String name;
    private final String id;
    private final Duration retry;
    private final String data;

    private ServerSentEvent(String name, String id, Duration retry, String data) {
        this.name = name;
        this.id = id;
        this.retry = retry;
        this.data = data;
    }

    /**
     * Creates an event with data and the default event name {@code message}.
     *
     * @param data text of the event; may be empty and may contain line breaks
     * @return the event
     */
    public static ServerSentEvent data(String data) {
        return new ServerSentEvent(null, null, null, Objects.requireNonNull(data, "data"));
    }

    /**
     * Creates an event with a name, which clients select with {@code addEventListener}.
     *
     * @param name event name; not empty, without CR or LF
     * @param data text of the event; may be empty and may contain line breaks
     * @return the event
     * @throws IllegalArgumentException if the name is empty or contains CR or LF
     */
    public static ServerSentEvent named(String name, String data) {
        Objects.requireNonNull(name, "name");
        if (name.isEmpty() || name.indexOf('\r') >= 0 || name.indexOf('\n') >= 0) {
            throw new IllegalArgumentException("Event name must be non-empty and contain no CR or LF");
        }
        return new ServerSentEvent(name, null, null, Objects.requireNonNull(data, "data"));
    }

    /**
     * Returns a copy with an event ID, which a reconnecting client returns in {@code Last-Event-ID}.
     *
     * @param id event ID; without CR, LF or NUL; empty resets the client's last ID
     * @return the event
     * @throws IllegalArgumentException if the ID contains CR, LF or NUL
     */
    public ServerSentEvent withId(String id) {
        Objects.requireNonNull(id, "id");
        if (id.indexOf('\r') >= 0 || id.indexOf('\n') >= 0 || id.indexOf('\0') >= 0) {
            throw new IllegalArgumentException("Event id must not contain CR, LF or NUL");
        }
        return new ServerSentEvent(name, id, retry, data);
    }

    /**
     * Returns a copy that tells the client how long to wait before reconnecting.
     *
     * @param retry reconnection delay, from one millisecond to {@link Integer#MAX_VALUE} milliseconds
     * @return the event
     * @throws IllegalArgumentException if the delay is outside that range
     */
    public ServerSentEvent withRetry(Duration retry) {
        Objects.requireNonNull(retry, "retry");
        if (retry.isNegative() || retry.isZero() || retry.compareTo(Duration.ofMillis(Integer.MAX_VALUE)) > 0
                || retry.toMillis() == 0) {
            throw new IllegalArgumentException("Retry must be between 1 millisecond and " + Integer.MAX_VALUE + " milliseconds");
        }
        return new ServerSentEvent(name, id, retry, data);
    }

    /**
     * Returns the event name.
     *
     * @return the name, or null for the default event
     */
    public String name() { return name; }

    /**
     * Returns the event ID.
     *
     * @return the ID, or null when none is sent
     */
    public String id() { return id; }

    /**
     * Returns the reconnection delay.
     *
     * @return the delay, or null when none is sent
     */
    public Duration retry() { return retry; }

    /**
     * Returns the data as given.
     *
     * @return the data
     */
    public String data() { return data; }

    /** The wire form of this event: its fields, one per line, and the blank line that ends it. */
    String encode() {
        var text = new StringBuilder(data.length() + 32);
        if (name != null) { text.append("event: ").append(name).append('\n'); }
        if (id != null) { text.append("id: ").append(id).append('\n'); }
        if (retry != null) { text.append("retry: ").append(retry.toMillis()).append('\n'); }
        lines(text, "data: ", data);
        return text.append('\n').toString();
    }

    /** Appends each line of the text, where CR, LF and CRLF all end a line, behind a prefix. */
    static void lines(StringBuilder out, String prefix, String text) {
        int start = 0;
        for (int i = 0; i <= text.length(); i++) {
            boolean end = i == text.length();
            char c = end ? '\n' : text.charAt(i);
            if (end || c == '\n' || c == '\r') {
                out.append(prefix).append(text, start, i).append('\n');
                if (c == '\r' && i + 1 < text.length() && text.charAt(i + 1) == '\n') { i++; }
                start = i + 1;
            }
        }
    }

    @Override public boolean equals(Object other) {
        return other instanceof ServerSentEvent that && Objects.equals(name, that.name) && Objects.equals(id, that.id)
                && Objects.equals(retry, that.retry) && data.equals(that.data);
    }

    @Override public int hashCode() { return Objects.hash(name, id, retry, data); }

    @Override public String toString() {
        return "ServerSentEvent[name=" + name + ", id=" + id + ", retry=" + retry + ", data=" + data.length() + " chars]";
    }
}
