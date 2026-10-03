package com.jsgalactic.axiom.http;

import com.jsgalactic.axiom.internal.PercentDecoding;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
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
 * <p>
 * The query is kept raw, without the leading {@code ?}; an absent and an empty query are both
 * {@code ""}. It is validated when the request is created (see
 * {@link #Request(String, String, String, Map, Body)}) and decoded on each lookup by
 * {@link #query(String)} and {@link #queryAll(String)}. Fragments are not modeled.
 * <p>
 * Headers are an immutable, case-insensitive map with one value per name; transports join
 * repeated fields with {@code ", "}. The {@link Body} carries the content and its Content-Type.
 * {@link #toString()} omits the query, header values, body content and remote address, which
 * may hold credentials or personal data.
 * <p>
 * The remote address is the transport peer: the socket address the connection came from, set by
 * the HTTP listener. It is {@code null} for requests created in memory unless a test sets it with
 * {@link #withRemoteAddress(InetSocketAddress)}. Behind a reverse proxy it is the proxy's address;
 * forwarded client addresses are headers and must only be believed from trusted proxies.
 * @param method case-sensitive HTTP method token
 * @param path absolute raw path without query or fragment, or {@code *} for {@code OPTIONS *}
 * @param query raw query without the leading {@code ?}; {@code ""} when absent
 * @param headers request header fields; copied into an immutable case-insensitive map
 * @param body request content; never null, {@link Body#empty()} when absent
 * @param remoteAddress resolved transport peer address, or {@code null} when unknown
 */
public record Request(String method, String path, String query, Map<String, String> headers, Body body,
                      InetSocketAddress remoteAddress) {
    private static final Pattern TOKEN = Pattern.compile("[!#$%&'*+.^_`|~0-9A-Za-z-]+");
    /** Longest accepted raw query, in characters. */
    public static final int MAX_QUERY_LENGTH = 4096;
    /** Most parameters an accepted query may contain; empty {@code &}-separated pairs do not count. */
    public static final int MAX_QUERY_PARAMETERS = 256;

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
        this(method, path, "", Map.of(), Body.empty());
    }

    /**
     * Creates a request without a query, validating it as described by the canonical
     * constructor.
     *
     * @param method HTTP token
     * @param path absolute raw path
     * @param headers header fields
     * @param body request content
     * @throws IllegalArgumentException for an invalid method or header
     * @throws InvalidRequestPathException for a rejected path
     */
    public Request(String method, String path, Map<String, String> headers, Body body) {
        this(method, path, "", headers, body);
    }

    /**
     * Creates a request without a known remote address, validating it as described by the
     * canonical constructor.
     *
     * @param method HTTP token
     * @param path absolute raw path
     * @param query raw query without the leading {@code ?}; {@code ""} when absent
     * @param headers header fields
     * @param body request content
     * @throws IllegalArgumentException for an invalid method, query or header
     * @throws InvalidRequestPathException for a rejected path
     */
    public Request(String method, String path, String query, Map<String, String> headers, Body body) {
        this(method, path, query, headers, body, null);
    }

    /**
     * Creates and validates the request.
     * The path is either {@code *} or an absolute path. {@code *} (the asterisk-form of
     * {@code OPTIONS *}) is accepted only with the method {@code OPTIONS} and an empty query; it
     * addresses the server rather than a resource and is never routed.
     * A path must start with {@code /} and contain only RFC 3986 path characters,
     * well-formed percent-escapes, and non-ASCII characters other than controls and spaces.
     * It is rejected when it contains an empty segment ({@code //}; a single trailing slash is
     * allowed), a {@code .} or {@code ..} segment, a backslash, a NUL or other control
     * character, a malformed percent-escape, or an encoded dot, slash, backslash, or NUL
     * ({@code %2E}, {@code %2F}, {@code %5C}, {@code %00} in either case).
     * <p>
     * The query may contain RFC 3986 query characters ({@code pchar}, {@code /} and {@code ?})
     * and non-ASCII characters other than controls and spaces, and every percent-escape must be
     * well-formed. It holds {@code &}-separated {@code name=value} pairs; empty pairs are
     * ignored, and each name and value must percent-decode (with {@code +} as a space) to
     * well-formed UTF-8. At most {@link #MAX_QUERY_LENGTH} characters and
     * {@link #MAX_QUERY_PARAMETERS} parameters are accepted. Rejection messages never contain
     * the query.
     * <p>
     * Header names must be HTTP tokens and values must not contain control characters other
     * than horizontal tab. A remote address, when present, must be resolved (carry an IP
     * address), so that no code ever triggers a name lookup for it.
     *
     * @param method HTTP token
     * @param path absolute raw path
     * @param query raw query without the leading {@code ?}; {@code ""} when absent
     * @param headers header fields
     * @param body request content
     * @param remoteAddress resolved transport peer address, or {@code null} when unknown
     * @throws IllegalArgumentException for an invalid method, query, header or an unresolved
     *         remote address
     * @throws InvalidRequestPathException for a rejected path
     */
    public Request {
        Objects.requireNonNull(method, "method");
        Objects.requireNonNull(path, "path");
        Objects.requireNonNull(query, "query");
        Objects.requireNonNull(headers, "headers");
        Objects.requireNonNull(body, "body");
        if (remoteAddress != null && remoteAddress.isUnresolved()) {
            throw new IllegalArgumentException("The remote address must be resolved");
        }
        if (!TOKEN.matcher(method).matches()) {
            throw new IllegalArgumentException("Invalid HTTP method: " + method);
        }
        if (path.equals("*")) {
            // RFC 9110 section 7.1: the asterisk-form addresses the server, and only for OPTIONS.
            if (!method.equals("OPTIONS")) {
                throw new InvalidRequestPathException("The asterisk-form target is only valid for OPTIONS");
            }
            if (!query.isEmpty()) { throw new InvalidRequestPathException("The asterisk-form target has no query"); }
        } else {
            validatePath(path);
        }
        validateQuery(query);
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
     * Returns the first value of a query parameter. Names and values are percent-decoded once
     * as UTF-8, with {@code +} decoded as a space ({@code %2B} is a literal plus), and names
     * match exactly after decoding. A parameter without {@code =} has the value {@code ""}.
     *
     * @param name decoded parameter name
     * @return first value, if present
     */
    public Optional<String> query(String name) {
        Objects.requireNonNull(name, "name");
        var values = parameters(name, true);
        return values.isEmpty() ? Optional.empty() : Optional.of(values.getFirst());
    }

    /**
     * Returns every value of a query parameter in request order, decoded as by
     * {@link #query(String)}.
     *
     * @param name decoded parameter name
     * @return immutable values; empty when the parameter is absent
     */
    public List<String> queryAll(String name) {
        return List.copyOf(parameters(Objects.requireNonNull(name, "name"), false));
    }

    /**
     * Returns a copy with the supplied headers replacing the current ones.
     *
     * @param headers header fields
     * @return request with the headers
     */
    public Request withHeaders(Map<String, String> headers) {
        return new Request(method, path, query, headers, body, remoteAddress);
    }

    /**
     * Returns a copy with the supplied body.
     *
     * @param body request content
     * @return request with the body
     */
    public Request withBody(Body body) {
        return new Request(method, path, query, headers, body, remoteAddress);
    }

    /**
     * Returns a copy with the supplied transport peer address. Transports call this with the
     * connection's peer; tests use it to simulate a client or a proxy.
     *
     * @param remoteAddress resolved peer address, or {@code null} for unknown
     * @return request with the remote address
     * @throws IllegalArgumentException for an unresolved address
     */
    public Request withRemoteAddress(InetSocketAddress remoteAddress) {
        return new Request(method, path, query, headers, body, remoteAddress);
    }

    /**
     * Describes the request without its query, header values, body content or remote address.
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
     * Creates a request from an HTTP origin-form request target ({@code path[?query]}), or from
     * the asterisk-form {@code *} of an OPTIONS request. The path is validated as by
     * {@link #Request(String, String)} and the query, which is retained, as by the canonical
     * constructor. Absolute-form targets ({@code http://host/path}) are rejected, not normalized.
     * Transports and the test client use this instead of splitting the target themselves.
     *
     * @param method HTTP token
     * @param target origin-form request target, or {@code *} for OPTIONS
     * @return a request for the target's path and query
     * @throws InvalidRequestPathException for a rejected path
     * @throws IllegalArgumentException for an invalid method or query
     */
    public static Request fromTarget(String method, String target) {
        Objects.requireNonNull(target, "target");
        int query = target.indexOf('?');
        if (query < 0) { return new Request(method, target); }
        if (query == 1 && target.charAt(0) == '*') {
            throw new InvalidRequestPathException("The asterisk-form target has no query");
        }
        return new Request(method, target.substring(0, query), target.substring(query + 1), Map.of(), Body.empty());
    }

    /** Collects decoded values of one parameter; the query is already validated. */
    private List<String> parameters(String name, boolean first) {
        if (query.isEmpty()) { return List.of(); }
        var values = new ArrayList<String>(1);
        int start = 0;
        while (start <= query.length()) {
            int end = query.indexOf('&', start);
            if (end < 0) { end = query.length(); }
            if (end > start) {
                int equals = query.indexOf('=', start);
                int nameEnd = equals < 0 || equals > end ? end : equals;
                if (PercentDecoding.decode(query, start, nameEnd, true).equals(name)) {
                    values.add(nameEnd == end ? "" : PercentDecoding.decode(query, nameEnd + 1, end, true));
                    if (first) { return values; }
                }
            }
            start = end + 1;
        }
        return values;
    }

    private static void validateQuery(String query) {
        if (query.length() > MAX_QUERY_LENGTH) {
            throw new IllegalArgumentException("Query longer than " + MAX_QUERY_LENGTH + " characters");
        }
        int parameters = 0;
        int start = 0;
        for (int i = 0; i <= query.length(); i++) {
            char c = i < query.length() ? query.charAt(i) : '&';
            if (c == '&') {
                if (i > start) {
                    if (++parameters > MAX_QUERY_PARAMETERS) {
                        throw new IllegalArgumentException("Query has more than " + MAX_QUERY_PARAMETERS + " parameters");
                    }
                    int equals = query.indexOf('=', start);
                    int nameEnd = equals < 0 || equals > i ? i : equals;
                    try {
                        PercentDecoding.decode(query, start, nameEnd, true);
                        if (nameEnd < i) { PercentDecoding.decode(query, nameEnd + 1, i, true); }
                    } catch (IllegalArgumentException malformed) {
                        throw new IllegalArgumentException("Query parameter is not valid percent-encoded UTF-8");
                    }
                }
                start = i + 1;
            } else if (c == '%') {
                if (i + 2 >= query.length() || hex(query.charAt(i + 1)) < 0 || hex(query.charAt(i + 2)) < 0) {
                    throw new IllegalArgumentException("Malformed percent-escape in query");
                }
                i += 2;
            } else if (c != '/' && c != '?' && !pathCharacter(c)) {
                throw new IllegalArgumentException("Invalid character in query");
            }
        }
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

    private static int hex(char c) { return PercentDecoding.hex(c); }
}
