package io.axiom.http;

import java.util.Collections;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;
import java.util.regex.Pattern;

/**
 * Immutable metadata for an in-memory HTTP request. Accepted paths are compared verbatim:
 * case, trailing slashes, and percent-encoding are preserved and nothing is decoded.
 * Paths that could address a different resource after normalization or decoding are
 * rejected instead of normalized; see {@link #Request(String, String)}.
 * Query strings and fragments are not modeled yet.
 * <p>
 * Headers are an immutable, case-insensitive map with one value per name; transports join
 * repeated fields with {@code ", "}. The {@link Body} carries the content and its Content-Type.
 * {@link #toString()} omits header values and body content, which may hold credentials.
 * @param method case-sensitive HTTP method token
 * @param path absolute raw path without query or fragment
 * @param headers request header fields; copied into an immutable case-insensitive map
 * @param body request content; never null, {@link Body#empty()} when absent
 */
public record Request(String method, String path, Map<String, String> headers, Body body) {
    private static final Pattern TOKEN = Pattern.compile("[!#$%&'*+.^_`|~0-9A-Za-z-]+");

    /**
     * Creates a request without headers or body, validating the path as described by the
     * canonical constructor.
     *
     * @param method HTTP token
     * @param path absolute raw path
     * @throws IllegalArgumentException for an invalid method
     * @throws InvalidRequestPathException for a rejected path
     */
    public Request(String method, String path) {
        this(method, path, Map.of(), Body.empty());
    }

    /**
     * Creates and validates the request.
     * The path must start with {@code /} and contain only RFC 3986 path characters,
     * well-formed percent-escapes, and non-ASCII characters other than controls and spaces.
     * It is rejected when it contains an empty segment ({@code //}; a single trailing slash is
     * allowed), a {@code .} or {@code ..} segment, a backslash, a NUL or other control
     * character, a malformed percent-escape, or an encoded dot, slash, backslash, or NUL
     * ({@code %2E}, {@code %2F}, {@code %5C}, {@code %00} in either case).
     *
     * Header names must be HTTP tokens and values must not contain control characters other
     * than horizontal tab.
     *
     * @param method HTTP token
     * @param path absolute raw path
     * @param headers header fields
     * @param body request content
     * @throws IllegalArgumentException for an invalid method or header
     * @throws InvalidRequestPathException for a rejected path
     */
    public Request {
        Objects.requireNonNull(method, "method");
        Objects.requireNonNull(path, "path");
        Objects.requireNonNull(headers, "headers");
        Objects.requireNonNull(body, "body");
        if (!TOKEN.matcher(method).matches()) {
            throw new IllegalArgumentException("Invalid HTTP method: " + method);
        }
        validatePath(path);
        if (headers.isEmpty()) {
            headers = Map.of();
        } else {
            var copy = new TreeMap<String, String>(String.CASE_INSENSITIVE_ORDER);
            headers.forEach((name, value) -> {
                if (!TOKEN.matcher(name).matches() || value.chars().anyMatch(c -> (c < 32 && c != '\t') || c == 127)) {
                    throw new IllegalArgumentException("Invalid request header");
                }
                if (copy.put(name, value) != null) {
                    throw new IllegalArgumentException("Header names must be unique ignoring case");
                }
            });
            headers = Collections.unmodifiableMap(copy);
        }
    }

    /**
     * Returns a header value, matching the name case-insensitively.
     *
     * @param name header name
     * @return value, if present
     */
    public Optional<String> header(String name) {
        return Optional.ofNullable(headers.get(Objects.requireNonNull(name, "name")));
    }

    /**
     * Returns a copy with the supplied headers replacing the current ones.
     *
     * @param headers header fields
     * @return request with the headers
     */
    public Request withHeaders(Map<String, String> headers) {
        return new Request(method, path, headers, body);
    }

    /**
     * Returns a copy with the supplied body.
     *
     * @param body request content
     * @return request with the body
     */
    public Request withBody(Body body) {
        return new Request(method, path, headers, body);
    }

    /**
     * Describes the request without header values or body content.
     *
     * @return method, path, header names and body summary
     */
    @Override public String toString() {
        return "Request[method=" + method + ", path=" + path + ", headers=" + headers.keySet() + ", body=" + body + "]";
    }

    /**
     * Creates a GET request for the supplied path.
     *
     * @param path absolute raw path
     * @return a GET request
     */
    public static Request get(String path) {
        return new Request("GET", path);
    }

    /**
     * Creates a request from an HTTP origin-form request target ({@code path[?query]}) in a
     * single pass. The path is validated as by {@link #Request(String, String)}; the query
     * must use RFC 3986 query characters and well-formed percent-escapes, and is discarded
     * because queries are not modeled yet. Transports use this instead of parsing the target
     * separately.
     *
     * @param method HTTP token
     * @param target origin-form request target
     * @return a request for the target's path
     * @throws InvalidRequestPathException for a rejected path
     * @throws IllegalArgumentException for an invalid method or query
     */
    public static Request fromTarget(String method, String target) {
        Objects.requireNonNull(target, "target");
        int query = target.indexOf('?');
        if (query < 0) { return new Request(method, target); }
        for (int i = query + 1; i < target.length(); i++) {
            char c = target.charAt(i);
            if (c == '%') {
                if (i + 2 >= target.length() || hex(target.charAt(i + 1)) < 0 || hex(target.charAt(i + 2)) < 0) {
                    throw new IllegalArgumentException("Malformed percent-escape in query");
                }
                i += 2;
            } else if (c != '/' && c != '?' && !pathCharacter(c)) {
                throw new IllegalArgumentException("Invalid character in query");
            }
        }
        return new Request(method, target.substring(0, query));
    }

    private static void validatePath(String path) {
        if (!path.startsWith("/")) {
            throw new InvalidRequestPathException("Expected an absolute path: " + path);
        }
        int segmentStart = 1;
        for (int i = 1; i <= path.length(); i++) {
            char c = i < path.length() ? path.charAt(i) : '/';
            if (c == '/') {
                checkSegment(path, segmentStart, i);
                segmentStart = i + 1;
            } else if (c == '%') {
                if (i + 2 >= path.length() || hex(path.charAt(i + 1)) < 0 || hex(path.charAt(i + 2)) < 0) {
                    throw new InvalidRequestPathException("Malformed percent-escape in path: " + path);
                }
                int decoded = hex(path.charAt(i + 1)) * 16 + hex(path.charAt(i + 2));
                if (decoded == '.' || decoded == '/' || decoded == '\\' || decoded == 0) {
                    throw new InvalidRequestPathException("Encoded dot, slash, backslash, or NUL in path: " + path);
                }
                i += 2;
            } else if (!pathCharacter(c)) {
                throw new InvalidRequestPathException(c == '?' || c == '#'
                        ? "Expected a path without query or fragment: " + path
                        : "Invalid character in path: " + path);
            }
        }
    }

    private static void checkSegment(String path, int start, int end) {
        int length = end - start;
        if (length == 0 && end != path.length() && path.length() > 1) {
            throw new InvalidRequestPathException("Empty segment in path: " + path);
        }
        if ((length == 1 || length == 2) && path.startsWith("..".substring(0, length), start)) {
            throw new InvalidRequestPathException("Dot segment in path: " + path);
        }
    }

    private static boolean pathCharacter(char c) {
        if (c >= 0x80) {
            return !Character.isISOControl(c) && !Character.isSpaceChar(c);
        }
        return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9')
                || "-._~!$&'()*+,;=:@".indexOf(c) >= 0;
    }

    private static int hex(char c) {
        if (c >= '0' && c <= '9') { return c - '0'; }
        if (c >= 'a' && c <= 'f') { return c - 'a' + 10; }
        if (c >= 'A' && c <= 'F') { return c - 'A' + 10; }
        return -1;
    }
}
