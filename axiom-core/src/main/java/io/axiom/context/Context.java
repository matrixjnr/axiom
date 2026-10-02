package io.axiom.context;

import io.axiom.execution.ExecutionContext;
import io.axiom.http.Request;
import io.axiom.http.Response;
import io.axiom.routing.Route;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/** Request-scoped response settings. A context must not be shared across threads. */
public interface Context {
    /**
     * Returns the immutable request metadata.
     *
     * @return the immutable request metadata
     */
    Request request();

    /**
     * Returns immutable identity and deadline metadata for this invocation.
     * @return execution context, safe to share with application tasks
     */
    ExecutionContext execution();
    /**
     * Returns the case-sensitive HTTP method.
     *
     * @return the case-sensitive HTTP method
     */
    default String method() {
        return request().method();
    }

    /**
     * Returns the raw path, without decoding or normalization.
     *
     * @return the raw path, without decoding or normalization
     */
    default String path() {
        return request().path();
    }

    /**
     * Sets the status for subsequent response mapping. Statuses 204, 205, and 304 cannot carry
     * a body: after setting one, mapping a non-null body (returning it from the handler or
     * calling {@link #response(Object)} or {@link #text(String)}) fails with an
     * {@link IllegalStateException} that names the route and status. Over HTTP that failure
     * is a 500 like any other handler failure.
     *
     * @param status final HTTP status (200-599)
     * @return this context
     * @throws IllegalArgumentException if the status is not a final status
     */
    Context status(int status);

    /**
     * Returns the matched route identity, including its template rather than request values.
     * @return matched route
     */
    Route route();

    /**
     * Reads a raw path capture without percent-decoding or normalization.
     * Captures are untrusted client input. A wildcard remainder may contain {@code /} and
     * must not be used as a file system path without the application's own containment checks.
     * @param name capture name declared in the route template
     * @return captured segment or wildcard remainder (which may be empty)
     * @throws IllegalArgumentException if the name is not declared by the matched route
     */
    String path(String name);

    /**
     * Reads a path capture with strict, single-pass UTF-8 percent-decoding. Each segment is
     * decoded once; a decoded value is never decoded again. Wildcard remainders keep their
     * raw {@code /} separators. Decoding fails rather than producing a {@code /}, backslash,
     * or NUL inside a segment, a {@code .} or {@code ..} segment, or malformed UTF-8.
     * The result is still untrusted client input.
     * @param name capture name declared in the route template
     * @return decoded capture
     * @throws IllegalArgumentException if the name is not declared or the value cannot be
     *         decoded safely
     */
    default String pathDecoded(String name) {
        var raw = path(name);
        var decoded = new StringBuilder(raw.length());
        int start = 0;
        while (true) {
            int end = raw.indexOf('/', start);
            decoded.append(decodeSegment(raw, start, end < 0 ? raw.length() : end));
            if (end < 0) { return decoded.toString(); }
            decoded.append('/');
            start = end + 1;
        }
    }

    private static String decodeSegment(String raw, int start, int end) {
        var bytes = new ByteArrayOutputStream(end - start);
        for (int i = start; i < end; i++) {
            char c = raw.charAt(i);
            if (c == '%') {
                if (i + 2 >= end) { throw new IllegalArgumentException("Malformed percent-escape in path capture"); }
                int high = Character.digit(raw.charAt(i + 1), 16);
                int low = Character.digit(raw.charAt(i + 2), 16);
                if (high < 0 || low < 0) {
                    throw new IllegalArgumentException("Malformed percent-escape in path capture");
                }
                bytes.write(high * 16 + low);
                i += 2;
            } else if (c < 0x80) {
                bytes.write(c);
            } else {
                int next = Character.isHighSurrogate(c) && i + 1 < end ? i + 2 : i + 1;
                bytes.writeBytes(raw.substring(i, next).getBytes(StandardCharsets.UTF_8));
                i = next - 1;
            }
        }
        String segment;
        try {
            segment = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes.toByteArray())).toString();
        } catch (CharacterCodingException malformed) {
            throw new IllegalArgumentException("Path capture is not valid percent-encoded UTF-8", malformed);
        }
        if (segment.equals(".") || segment.equals("..") || segment.indexOf('/') >= 0
                || segment.indexOf('\\') >= 0 || segment.indexOf('\0') >= 0) {
            throw new IllegalArgumentException("Path capture decodes to a separator, NUL, or dot segment");
        }
        return segment;
    }

    /**
     * Returns all captures in template order, materializing them on first access.
     * @return immutable parameter map; empty for a static route
     */
    Map<String, String> pathParameters();

    /**
     * Maps a body using the current status (200 by default).
     * Strings use UTF-8 text and byte arrays use application/octet-stream.
     * Other objects are retained for a future codec layer; no serialization occurs.
     * Null without an explicit status produces 204.
     * @param body returned body, or null
     * @return a response snapshot
     * @throws IllegalStateException if the status set on this context cannot carry a body
     */
    Response response(Object body);

    /**
     * Creates a text response using the current settings.
     *
     * @param text non-null text
     * @return a response using the current status
     */
    default Response text(String text) {
        return response(java.util.Objects.requireNonNull(text, "text"));
    }

    /**
     * Returns a 204 response with no body.
     *
     * @return a 204 response with no body
     */
    default Response noContent() {
        return Response.of(204, null);
    }
}
