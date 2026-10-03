package com.jsgalactic.axiom.routing;

import com.jsgalactic.axiom.http.Request;
import java.util.HashSet;
import java.util.regex.Pattern;

/**
 * Stable route identity, independent of request values or handler instances.
 * Templates support whole-segment parameters ({@code :id}) and a named terminal
 * wildcard ({@code *path}). Names must be unique within a template.
 * @param method case-sensitive HTTP method
 * @param path absolute raw path template
 */
public record Route(String method, String path) {
    private static final Pattern NAME = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");

    /**
     * Validates the method, path, and capture names without normalizing the template.
     *
     * @param method HTTP token
     * @param path absolute path template
     * @throws IllegalArgumentException for malformed templates (including {@code *}) or duplicate capture names
     */
    public Route {
        new Request(method, path);
        if (path.equals("*")) {
            throw new IllegalArgumentException("The asterisk-form is not a route template; OPTIONS * is answered automatically");
        }
        var names = new HashSet<String>();
        var segments = path.substring(1).split("/", -1);
        for (int i = 0; i < segments.length; i++) {
            var segment = segments[i];
            if (!segment.startsWith(":") && !segment.startsWith("*")) {
                continue;
            }
            var name = segment.substring(1);
            if (!NAME.matcher(name).matches()) {
                throw new IllegalArgumentException("Invalid capture name in route: " + path);
            }
            if (!names.add(name)) {
                throw new IllegalArgumentException("Duplicate capture name '" + name + "' in route: " + path);
            }
            if (segment.startsWith("*") && i != segments.length - 1) {
                throw new IllegalArgumentException("Wildcard must be the final segment: " + path);
            }
        }
    }
}
