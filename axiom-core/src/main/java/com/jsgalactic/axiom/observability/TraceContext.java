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
 * <p>The caller's {@code tracestate} (see {@link TraceState}) is carried along and passed on by
 * {@link #child()}; it is read only together with a valid {@code traceparent}, as the specification
 * requires, and an invalid one is dropped without failing the request.
 *
 * <p><strong>In logs.</strong> The framework adds the trace id next to the request ID in its own log
 * messages about a request, when the caller sent a valid {@code traceparent}. The id passed
 * validation (32 lowercase hex digits), so it cannot inject log lines, but it is still the caller's
 * choice. Applications get the same text without a thread-local map from
 * {@code Context.correlation()}. <strong>In responses.</strong> {@link #responseHeader()} is an
 * opt-in middleware that returns the trace id and this service's span in a {@code traceresponse}
 * header.
 *
 * @param traceId 32 lowercase hexadecimal digits, not all zero
 * @param parentId 16 lowercase hexadecimal digits, not all zero: the caller's span
 * @param flags trace flags, 0 to 255; bit 0 is "sampled"
 * @param traceState vendor-specific members to propagate; {@link TraceState#EMPTY} when the caller sent none
 */
public record TraceContext(String traceId, String parentId, int flags, TraceState traceState) {
    /** The request header that carries the trace context. */
    public static final String HEADER = "traceparent";
    /** The response header written by {@link #responseHeader()}. */
    public static final String RESPONSE_HEADER = "traceresponse";
    private static final int LENGTH = 55;

    /**
     * Creates a context without trace state.
     * @param traceId 32 lowercase hexadecimal digits, not all zero
     * @param parentId 16 lowercase hexadecimal digits, not all zero
     * @param flags trace flags, 0 to 255
     * @throws IllegalArgumentException as for the canonical constructor
     */
    public TraceContext(String traceId, String parentId, int flags) { this(traceId, parentId, flags, TraceState.EMPTY); }

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
        Objects.requireNonNull(traceState, "traceState");
    }

    /**
     * Parses a {@code traceparent} header and the accompanying {@code tracestate}. An invalid or
     * absent {@code tracestate} leaves the trace state empty; an invalid {@code traceparent}
     * discards both.
     * @param traceparent the {@code traceparent} value, or null
     * @param tracestate the {@code tracestate} value, or null
     * @return the trace context, or empty if {@code traceparent} is absent or not valid
     */
    public static Optional<TraceContext> parse(String traceparent, String tracestate) {
        return parse(traceparent).map(parsed -> new TraceContext(parsed.traceId, parsed.parentId, parsed.flags,
                TraceState.parse(tracestate).orElse(TraceState.EMPTY)));
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
     * Continues the trace for a downstream call: the same trace id, flags and trace state with a new
     * random parent id standing for this service's span. Send {@link #headers()} of the result.
     * @return a child context
     */
    public TraceContext child() {
        long id;
        do { id = ThreadLocalRandom.current().nextLong(); } while (id == 0);
        return new TraceContext(traceId, String.format("%016x", id), flags, traceState);
    }

    /**
     * The headers that continue this trace on an outgoing call: {@code traceparent}, and
     * {@code tracestate} when there are members. Usually called on {@link #child()}.
     * @return the header names and values
     */
    public java.util.Map<String, String> headers() {
        return traceState.isEmpty() ? java.util.Map.of(HEADER, traceparent())
                : java.util.Map.of(HEADER, traceparent(), TraceState.HEADER, traceState.header());
    }

    /**
     * Returns a middleware that, for requests with a valid {@code traceparent}, adds a
     * {@code traceresponse} header to the response: the same format as {@code traceparent}, with
     * the caller's trace id and flags and a new span id for this service, so a client can find its
     * request in the server's logs. Requests without a valid {@code traceparent} get no header, since
     * Axiom starts no traces of its own. Register it with {@code app.use(...)}. Like all middleware it
     * does not decorate responses made from exceptions (see {@code Middleware}).
     * @return the opt-in middleware
     */
    public static com.jsgalactic.axiom.context.Middleware responseHeader() {
        return (context, next) -> {
            var response = next.run();
            var trace = context.traceContext();
            return trace.isPresent() ? response.withHeader(RESPONSE_HEADER, trace.get().child().traceparent()) : response;
        };
    }

    /**
     * Describes a request for a log line: its framework ID, followed by {@code trace=<id>} when the
     * caller sent a valid {@code traceparent}.
     * @param requestId the framework's request ID
     * @param trace the caller's trace context, if any
     * @return {@code requestId} or {@code requestId trace=traceId}
     */
    public static String correlation(String requestId, Optional<TraceContext> trace) {
        return trace.isPresent() ? requestId + " trace=" + trace.get().traceId() : requestId;
    }

    /**
     * Like {@link #correlation(String, Optional)}, reading the trace headers of a request.
     * @param requestId the framework's request ID
     * @param request the request
     * @return the description
     */
    public static String correlation(String requestId, com.jsgalactic.axiom.http.Request request) {
        return correlation(requestId, parse(request.header(HEADER).orElse(null)));
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
