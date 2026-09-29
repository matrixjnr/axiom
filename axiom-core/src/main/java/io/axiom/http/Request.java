package io.axiom.http;

import java.net.URI;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Immutable metadata for an in-memory HTTP request. Paths are compared verbatim:
 * case, repeated slashes, trailing slashes, dot segments, and encoding are preserved.
 * Query strings, fragments, request bodies, and headers are not modeled yet.
 * @param method case-sensitive HTTP method token
 * @param path absolute raw path without query or fragment
 */
public record Request(String method, String path) {
    private static final Pattern TOKEN = Pattern.compile("[!#$%&'*+.^_`|~0-9A-Za-z-]+");

    /**
     * Creates and validates the method/path identity.
     *
     * @param method HTTP token
     * @param path absolute raw path
     */
    public Request {
        Objects.requireNonNull(method, "method");
        Objects.requireNonNull(path, "path");
        if (!TOKEN.matcher(method).matches()) {
            throw new IllegalArgumentException("Invalid HTTP method: " + method);
        }
        // Prefix an authority so paths starting with // remain paths, not URI authorities.
        var uri = URI.create("http://axiom.invalid" + path);
        if (!path.startsWith("/") || uri.getRawQuery() != null || uri.getRawFragment() != null
                || !path.equals(uri.getRawPath())) {
            throw new IllegalArgumentException("Expected an absolute path without query or fragment: " + path);
        }
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
}
