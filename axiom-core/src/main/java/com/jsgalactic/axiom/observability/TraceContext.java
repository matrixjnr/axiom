package com.jsgalactic.axiom.observability;

import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ThreadLocalRandom;

/**
 * A W3C Trace Context identity, the {@code traceparent} of a request: which distributed trace it
 * belongs to and which caller span started it. It lets logs, metrics exemplars and downstream
 * calls be correlated with the caller's trace. It is propagated information only: Axiom starts no
 * spans and depends on no tracing library.
 *
 * <p>Parsing is strict. Only version {@code 00} is accepted, in exactly the 55-character form
 * {@code 00-<32 lowercase hex>-<16 lowercase hex>-<2 lowercase hex>}, with a nonzero trace id and
 * parent id. Anything else, including uppercase digits, surrounding whitespace, extra fields,
 * future versions and several headers joined into one value, is treated as absent, as the
 * specification allows: the request simply has no trace context. Incoming values are validated
 * but never trusted for anything beyond correlation; a client chooses them.
 *
 * @param traceId 32 lowercase hexadecimal digits, not all zero
 * @param parentId 16 lowercase hexadecimal digits, not all zero: the caller's span
 * @param flags trace flags, 0 to 255; bit 0 is "sampled"
 */
public record TraceContext(String traceId, String parentId, int flags) {
    /** The request header that carries the trace context. */
    public static final String HEADER = "traceparent";
    private static final int LENGTH = 55;

    /**
     * Validates the components.
     * @throws IllegalArgumentException if an id is not lowercase hex of the right length or is all zero,
     *         or the flags are out of range
     */
    public TraceContext {
        Objects.requireNonNull(traceId, "traceId");
        Objects.requireNonNull(parentId, "parentId");
        if (traceId.length() != 32 || !hex(traceId, 0, 32) || zero(traceId)) { throw new IllegalArgumentException("Invalid trace id"); }
        if (parentId.length() != 16 || !hex(parentId, 0, 16) || zero(parentId)) { throw new IllegalArgumentException("Invalid parent id"); }
        if (flags < 0 || flags > 255) { throw new IllegalArgumentException("Invalid trace flags"); }
    }

    /**
     * Parses a {@code traceparent} header value strictly.
     * @param header the header value, or null if the request had none
     * @return the trace context, or empty if the value is absent or not valid
     */
    public static Optional<TraceContext> parse(String header) {
        if (header == null || header.length() != LENGTH || header.charAt(0) != '0' || header.charAt(1) != '0'
                || header.charAt(2) != '-' || header.charAt(35) != '-' || header.charAt(52) != '-') {
            return Optional.empty();
        }
        if (!hex(header, 3, 32) || !hex(header, 36, 16) || !hex(header, 53, 2)) { return Optional.empty(); }
        var traceId = header.substring(3, 35);
        var parentId = header.substring(36, 52);
        if (zero(traceId) || zero(parentId)) { return Optional.empty(); }
        return Optional.of(new TraceContext(traceId, parentId, Integer.parseInt(header, 53, 55, 16)));
    }

    /**
     * Reports whether the caller recorded this trace.
     * @return the sampled flag
     */
    public boolean sampled() { return (flags & 1) != 0; }

    /**
     * Formats the context as a {@code traceparent} value.
     * @return version 00 header value
     */
    public String traceparent() {
        return "00-" + traceId + "-" + parentId + "-" + (flags < 16 ? "0" : "") + Integer.toHexString(flags);
    }

    /**
     * Continues the trace for a downstream call: the same trace id and flags with a new random
     * parent id standing for this service's span. Send {@link #traceparent()} of the result.
     * @return a child context
     */
    public TraceContext child() {
        long id;
        do { id = ThreadLocalRandom.current().nextLong(); } while (id == 0);
        return new TraceContext(traceId, String.format("%016x", id), flags);
    }

    private static boolean hex(String text, int from, int count) {
        if (text.length() < from + count) { return false; }
        for (int i = from; i < from + count; i++) {
            char c = text.charAt(i);
            if ((c < '0' || c > '9') && (c < 'a' || c > 'f')) { return false; }
        }
        return true;
    }

    private static boolean zero(String id) {
        for (int i = 0; i < id.length(); i++) { if (id.charAt(i) != '0') { return false; } }
        return true;
    }
}
