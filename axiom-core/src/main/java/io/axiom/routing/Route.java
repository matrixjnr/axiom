package io.axiom.routing;

import io.axiom.http.Request;

/**
 * Stable route identity, independent of a particular request or handler instance.
 * @param method case-sensitive HTTP method
 * @param path exact raw path
 */
public record Route(String method, String path) {
    /**
     * Creates and validates the method/path identity.
     *
     * @param method HTTP token
     * @param path exact absolute path
     */
    public Route {
        new Request(method, path);
        for (var segment : path.split("/", -1)) {
            if (segment.startsWith(":") || segment.startsWith("*")) {
                throw new IllegalArgumentException("Route templates are not supported yet: " + path);
            }
        }
    }
}
