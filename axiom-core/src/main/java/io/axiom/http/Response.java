package io.axiom.http;

import java.util.Arrays;
import java.util.Collections;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.regex.Pattern;

/**
 * A response value before wire encoding. Header maps and byte arrays are defensively
 * copied; arbitrary body objects remain owned by the caller and are not deep-copied.
 * Header names are case-insensitive. One value per header is supported at this stage.
 * <p>
 * Bodies may be {@code null}, a {@link String}, a {@code byte[]}, or any other object.
 * Other objects are retained without copying. When the response is prepared, the runtime
 * encodes them with the installed codec for the response's Content-Type (see
 * {@code Context.json}); without such a codec the HTTP transport answers 500. Two responses are equal when their statuses,
 * headers (names compared case-insensitively), and bodies are equal, comparing byte arrays
 * by content and other bodies with {@link Object#equals(Object)}.
 */
public final class Response {
    private static final Pattern TOKEN = Pattern.compile("[!#$%&'*+.^_`|~0-9A-Za-z-]+");
    private final int status;
    private final Object body;
    private final Map<String, String> headers;

    private Response(int status, Object body, Map<String, String> headers) {
        validateStatus(status);
        if ((status == 204 || status == 205 || status == 304) && body != null) {
            throw new IllegalArgumentException("Status " + status + " cannot carry a body");
        }
        this.status = status;
        this.body = body instanceof byte[] bytes ? bytes.clone() : body;
        var copy = new TreeMap<String, String>(String.CASE_INSENSITIVE_ORDER);
        copy.putAll(headers);
        this.headers = Collections.unmodifiableMap(copy);
    }

    /**
     * Creates a response with the supplied status and body.
     *
     * @param status final HTTP status (200-599)
     * @param body body value or null
     * @return a response with a content type for String or byte[] bodies
     */
    public static Response of(int status, Object body) {
        Map<String, String> headers = body instanceof String
                ? Map.of("Content-Type", "text/plain; charset=utf-8")
                : body instanceof byte[] ? Map.of("Content-Type", "application/octet-stream") : Map.of();
        return new Response(status, body, headers);
    }

    /**
     * Creates a redirect without a body.
     *
     * @param status 301, 302, 303, 307 or 308
     * @param location target URI reference; validated as by {@link #withLocation(String)}
     * @return redirect response
     * @throws IllegalArgumentException for another status or an invalid location
     */
    public static Response redirect(int status, String location) {
        if (status != 301 && status != 302 && status != 303 && status != 307 && status != 308) {
            throw new IllegalArgumentException("Redirect status must be 301, 302, 303, 307 or 308: " + status);
        }
        return of(status, null).withLocation(location);
    }

    /**
     * Returns a copy with a Location header, for example after 201 Created.
     * The location must be a URI reference (absolute URI, absolute path or relative reference)
     * of at most 2048 characters using only visible ASCII characters allowed by RFC 3986, with
     * well-formed percent-escapes; spaces, quotes, angle brackets, backslashes, control
     * characters and therefore CR/LF header injection are rejected. Validation does not judge
     * whether the target is safe to redirect to: never redirect to an unchecked client-supplied URL.
     *
     * @param location URI reference
     * @return a response with the Location header
     * @throws IllegalArgumentException for an invalid location
     */
    public Response withLocation(String location) {
        Objects.requireNonNull(location, "location");
        if (location.isEmpty() || location.length() > 2048) {
            throw new IllegalArgumentException("Location must contain 1 to 2048 characters");
        }
        for (int i = 0; i < location.length(); i++) {
            char c = location.charAt(i);
            if (c <= 0x20 || c >= 0x7f || "\"<>\\^`{|}".indexOf(c) >= 0) {
                throw new IllegalArgumentException("Location contains a character outside URI syntax");
            }
        }
        try {
            new java.net.URI(location);
        } catch (java.net.URISyntaxException invalid) {
            throw new IllegalArgumentException("Location is not a valid URI reference");
        }
        return withHeader("Location", location);
    }

    /**
     * Validates a final response status.
     *
     * @param status final HTTP status
     * @throws IllegalArgumentException if invalid
     */
    public static void validateStatus(int status) {
        if (status < 200 || status > 599) {
            throw new IllegalArgumentException("Expected a final HTTP status between 200 and 599: " + status);
        }
    }

    /**
     * Returns final HTTP status.
     *
     * @return final HTTP status
     */
    public int status() {
        return status;
    }

    /**
     * Returns body value, with a defensive copy for byte arrays.
     *
     * @return body value, with a defensive copy for byte arrays
     */
    public Object body() {
        return body instanceof byte[] bytes ? bytes.clone() : body;
    }

    /**
     * Returns immutable, case-insensitive header map.
     *
     * @return immutable, case-insensitive header map
     */
    public Map<String, String> headers() {
        return headers;
    }

    /**
     * Returns a copy, replacing any existing value for the header regardless of case.
     * @param name HTTP header token
     * @param value header value without control characters (horizontal tabs are allowed)
     * @return a response with the header
     */
    public Response withHeader(String name, String value) {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(value, "value");
        if (!TOKEN.matcher(name).matches()
                || value.chars().anyMatch(c -> (c < 32 && c != '\t') || c == 127)) {
            throw new IllegalArgumentException("Invalid response header");
        }
        var copy = new TreeMap<String, String>(String.CASE_INSENSITIVE_ORDER);
        copy.putAll(headers);
        copy.put(name, value);
        return new Response(status, body, copy);
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof Response that && status == that.status && headers.equals(that.headers)
                && (body instanceof byte[] bytes && that.body instanceof byte[] thatBytes
                        ? Arrays.equals(bytes, thatBytes) : Objects.equals(body, that.body));
    }

    @Override
    public int hashCode() {
        int headerHash = 0;
        for (var header : headers.entrySet()) {
            // Header names compare case-insensitively, so they must hash that way too.
            headerHash += header.getKey().toLowerCase(Locale.ROOT).hashCode() ^ header.getValue().hashCode();
        }
        int bodyHash = body instanceof byte[] bytes ? Arrays.hashCode(bytes) : Objects.hashCode(body);
        return 31 * (31 * status + headerHash) + bodyHash;
    }

    /**
     * Describes the response for diagnostics. Text bodies longer than 80 characters are
     * truncated, byte arrays are shown by length, and other bodies by their class name.
     *
     * @return a diagnostic description
     */
    @Override
    public String toString() {
        String description;
        if (body == null) {
            description = "null";
        } else if (body instanceof String text) {
            description = text.length() <= 80 ? '"' + text + '"'
                    : '"' + text.substring(0, 80) + "\"... (" + text.length() + " chars)";
        } else if (body instanceof byte[] bytes) {
            description = "byte[" + bytes.length + "]";
        } else {
            description = body.getClass().getName();
        }
        return "Response[status=" + status + ", headers=" + headers + ", body=" + description + "]";
    }

    /**
     * Returns a copy without a body, preserving status and headers (for HEAD).
     *
     * @return a copy without a body, preserving status and headers (for HEAD)
     */
    public Response withoutBody() {
        return new Response(status, null, headers);
    }
}
