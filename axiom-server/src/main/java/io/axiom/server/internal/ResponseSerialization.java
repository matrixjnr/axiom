package io.axiom.server.internal;

import io.axiom.http.Response;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * The one definition of which prepared responses the HTTP transport can put on the wire.
 * Shared by the transport and the test client so that the test client fails exactly where a
 * listener would answer 500. Not application API.
 * <p>
 * Rules, applied to a response after codec encoding:
 * <table>
 * <caption>Response serialization rules</caption>
 * <tr><th>Part</th><th>Sendable</th><th>Otherwise</th></tr>
 * <tr><td>Body type</td><td>{@code null} (no bytes), {@code byte[]} (verbatim) or {@code String}
 *     (UTF-8)</td><td>any other object</td></tr>
 * <tr><td>Body size</td><td>at most {@link #MAX_BODY_BYTES} bytes; a {@code String} longer than
 *     that many characters is refused before it is encoded</td><td>larger</td></tr>
 * <tr><td>Header size</td><td>sum of name length + value length + {@link #HEADER_OVERHEAD} per
 *     field, in characters, at most {@link #MAX_HEADER_BYTES}</td><td>larger</td></tr>
 * <tr><td>Header values</td><td>Latin-1 characters only (code points up to 255)</td><td>any
 *     character above 255</td></tr>
 * </table>
 * Hop-by-hop headers, {@code Content-Length}, {@code Date} and {@code X-Request-ID} are added or
 * removed by the transport afterwards and are not part of these rules; a {@code Content-Length}
 * entry (for example the one a HEAD response carries) is not counted.
 */
public final class ResponseSerialization {
    /** Largest response body, in bytes after encoding. */
    public static final int MAX_BODY_BYTES = 1024 * 1024;
    /** Largest response header section as counted by {@link #headersSendable(Map)}. */
    public static final int MAX_HEADER_BYTES = 8192;
    /** Characters counted per header field beyond its name and value ({@code ": "} and CRLF). */
    public static final int HEADER_OVERHEAD = 4;

    private ResponseSerialization() {}

    /**
     * Returns the bytes the transport writes for a body.
     *
     * @param body prepared response body
     * @return body bytes (empty for {@code null}; the array itself for {@code byte[]}), or
     *         {@code null} when the body has an unsupported type or is too large
     */
    public static byte[] bodyBytes(Object body) {
        byte[] bytes;
        if (body == null) { return new byte[0]; }
        if (body instanceof byte[] value) { bytes = value; }
        else if (body instanceof String value && value.length() <= MAX_BODY_BYTES) {
            bytes = value.getBytes(StandardCharsets.UTF_8);
        } else { return null; }
        return bytes.length > MAX_BODY_BYTES ? null : bytes;
    }

    /**
     * Checks the header size and character rules, ignoring {@code Content-Length}.
     *
     * @param headers prepared response headers
     * @return whether the transport can write them
     */
    public static boolean headersSendable(Map<String, String> headers) {
        int size = 0;
        for (var header : headers.entrySet()) {
            if (header.getKey().equalsIgnoreCase("Content-Length")) { continue; }
            size += header.getKey().length() + header.getValue().length() + HEADER_OVERHEAD;
            if (size > MAX_HEADER_BYTES || header.getValue().chars().anyMatch(c -> c > 255)) { return false; }
        }
        return true;
    }

    /**
     * Describes why a prepared response cannot be sent, for test diagnostics.
     *
     * @param response prepared response
     * @return a description naming the broken rule, or {@code null} when the response is sendable
     */
    public static String rejection(Response response) {
        var body = response.body();
        if (body != null && !(body instanceof byte[]) && !(body instanceof String)) {
            return "Response body of type " + body.getClass().getName()
                    + " cannot be serialized by the transport (only String and byte[])";
        }
        if (bodyBytes(body) == null) { return "Response body exceeds " + MAX_BODY_BYTES + " bytes"; }
        if (!headersSendable(response.headers())) {
            return "Response headers exceed " + MAX_HEADER_BYTES + " bytes or are not Latin-1";
        }
        return null;
    }
}
