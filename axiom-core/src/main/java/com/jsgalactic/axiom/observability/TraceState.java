package com.jsgalactic.axiom.observability;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * The W3C Trace Context {@code tracestate} of a request: an ordered list of vendor-specific
 * {@code key=value} members that travels with a {@link TraceContext}. Axiom does not interpret the
 * members; it validates them and passes them on, so a service that sits in the middle of a trace
 * does not drop what other vendors recorded.
 *
 * <p>Parsing is strict, and a value that breaks any rule is treated as absent, never partly kept:
 * the request simply has no trace state, as the specification allows for an invalid header. The
 * rules are the specification's grammar plus a fixed size limit.
 * <ul>
 *   <li>At most {@value #MAX_MEMBERS} members and {@value #MAX_LENGTH} characters in all (the
 *       smallest size every implementation must propagate). A longer header is ignored whole.
 *   <li>A key is a lowercase simple key ({@code [a-z][a-z0-9_\-*&#47;]*}, up to 256 characters) or a
 *       multi-tenant key {@code tenant@system} (tenant up to 241, system up to 14 characters).
 *   <li>A value is up to 256 printable ASCII characters other than {@code ,} and {@code =},
 *       not ending in a space.
 *   <li>Keys are unique; empty list elements are skipped; spaces and tabs may surround members.
 * </ul>
 * Several {@code tracestate} headers, joined by the HTTP layer with commas, are one list. The
 * values are chosen by the caller: they are for propagation, never for authorization or metric tags.
 *
 * @param members the members, most recently updated first; at most {@value #MAX_MEMBERS}
 */
public record TraceState(List<Member> members) {
    /** The request header that carries the trace state. */
    public static final String HEADER = "tracestate";
    /** Most members in a trace state. */
    public static final int MAX_MEMBERS = 32;
    /** Longest header value that is accepted and produced. */
    public static final int MAX_LENGTH = 512;
    /** A trace state without members. */
    public static final TraceState EMPTY = new TraceState(List.of());

    private static final Pattern KEY = Pattern.compile(
            "[a-z][a-z0-9_\\-*/]{0,255}|[a-z0-9][a-z0-9_\\-*/]{0,240}@[a-z][a-z0-9_\\-*/]{0,13}");

    /** One {@code key=value} pair of a trace state. */
    public record Member(String key, String value) {
        /**
         * Validates the pair.
         * @throws IllegalArgumentException if the key or value breaks the grammar
         */
        public Member {
            Objects.requireNonNull(key, "key");
            Objects.requireNonNull(value, "value");
            if (!KEY.matcher(key).matches()) { throw new IllegalArgumentException("Invalid tracestate key"); }
            if (!validValue(value)) { throw new IllegalArgumentException("Invalid tracestate value"); }
        }
    }

    /**
     * Copies and validates the members.
     * @throws IllegalArgumentException for more than {@value #MAX_MEMBERS} members, duplicate keys
     *         or a header form longer than {@value #MAX_LENGTH} characters
     */
    public TraceState {
        members = List.copyOf(members);
        if (members.size() > MAX_MEMBERS) { throw new IllegalArgumentException("Too many tracestate members"); }
        var keys = new java.util.HashSet<String>();
        for (var member : members) {
            if (!keys.add(member.key())) { throw new IllegalArgumentException("Duplicate tracestate key"); }
        }
        if (header(members).length() > MAX_LENGTH) { throw new IllegalArgumentException("tracestate is too long"); }
    }

    /**
     * Parses a {@code tracestate} header value strictly.
     * @param header the header value, or null if the request had none
     * @return the trace state, or empty if the value is absent, has no members or is not valid
     */
    public static Optional<TraceState> parse(String header) {
        if (header == null || header.length() > MAX_LENGTH) { return Optional.empty(); }
        var members = new ArrayList<Member>();
        int start = 0;
        while (start <= header.length()) {
            int end = header.indexOf(',', start);
            if (end < 0) { end = header.length(); }
            var element = trim(header, start, end);
            if (!element.isEmpty()) {
                int equals = element.indexOf('=');
                if (equals <= 0) { return Optional.empty(); }
                var key = element.substring(0, equals);
                var value = element.substring(equals + 1);
                if (!KEY.matcher(key).matches() || !validValue(value)) { return Optional.empty(); }
                for (var existing : members) {
                    if (existing.key().equals(key)) { return Optional.empty(); }
                }
                members.add(new Member(key, value));
                if (members.size() > MAX_MEMBERS) { return Optional.empty(); }
            }
            start = end + 1;
        }
        return members.isEmpty() ? Optional.empty() : Optional.of(new TraceState(members));
    }

    /**
     * Returns the value of a member.
     * @param key the member's key
     * @return its value, if present
     */
    public Optional<String> get(String key) {
        for (var member : members) {
            if (member.key().equals(key)) { return Optional.of(member.value()); }
        }
        return Optional.empty();
    }

    /**
     * Returns a state in which a member is set and moved to the front, as the specification asks of
     * a vendor that changes its entry. If that makes the list longer than {@value #MAX_MEMBERS}
     * members, the rightmost ones are dropped.
     * @param key the member's key
     * @param value the new value
     * @return the updated trace state
     * @throws IllegalArgumentException if the key or value breaks the grammar, or the result would be
     *         longer than {@value #MAX_LENGTH} characters
     */
    public TraceState with(String key, String value) {
        var updated = new ArrayList<Member>(members.size() + 1);
        updated.add(new Member(key, value));
        for (var member : members) {
            if (!member.key().equals(key)) { updated.add(member); }
        }
        while (updated.size() > MAX_MEMBERS) { updated.removeLast(); }
        return new TraceState(updated);
    }

    /**
     * Reports whether there are no members.
     * @return true for {@link #EMPTY}
     */
    public boolean isEmpty() { return members.isEmpty(); }

    /**
     * Formats the state as a {@code tracestate} header value.
     * @return the members joined with commas; empty for no members
     */
    public String header() { return header(members); }

    private static String header(List<Member> members) {
        var text = new StringBuilder();
        for (var member : members) {
            if (!text.isEmpty()) { text.append(','); }
            text.append(member.key()).append('=').append(member.value());
        }
        return text.toString();
    }

    private static String trim(String text, int from, int to) {
        while (from < to && (text.charAt(from) == ' ' || text.charAt(from) == '\t')) { from++; }
        while (to > from && (text.charAt(to - 1) == ' ' || text.charAt(to - 1) == '\t')) { to--; }
        return text.substring(from, to);
    }

    private static boolean validValue(String value) {
        if (value.length() > 256) { return false; }
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c < 0x20 || c > 0x7e || c == ',' || c == '=') { return false; }
        }
        return value.isEmpty() || value.charAt(value.length() - 1) != ' ';
    }
}
