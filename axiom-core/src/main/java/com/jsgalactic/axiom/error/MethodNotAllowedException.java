package com.jsgalactic.axiom.error;

import java.io.Serial;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;

/**
 * A method the target resource does not support (405). The response's {@code Allow} header
 * lists the supplied methods in sorted order. The runtime answers unmatched methods on
 * registered routes with the same status and header.
 */
public class MethodNotAllowedException extends AxiomException {
    @Serial private static final long serialVersionUID = 1L;
    private static final Pattern TOKEN = Pattern.compile("[!#$%&'*+.^_`|~0-9A-Za-z-]{1,32}");

    /**
     * Creates the exception with code {@code method_not_allowed}.
     *
     * @param allowed supported method tokens; at least one
     * @throws IllegalArgumentException if the set is empty or a method is not an HTTP token of at
     *         most 32 characters
     */
    public MethodNotAllowedException(Set<String> allowed) {
        super(405, "method_not_allowed", Map.of("Allow", allow(allowed)));
    }

    private static String allow(Set<String> allowed) {
        var sorted = new TreeSet<String>();
        for (var method : allowed) {
            if (!TOKEN.matcher(method).matches()) { throw new IllegalArgumentException("Invalid method token"); }
            sorted.add(method);
        }
        if (sorted.isEmpty()) { throw new IllegalArgumentException("At least one allowed method is required"); }
        return String.join(", ", sorted);
    }
}
