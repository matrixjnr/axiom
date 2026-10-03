package com.jsgalactic.axiom.http.internal;

import java.net.URI;
import java.util.Locale;

/**
 * Turns the request-target of a request line into the origin-form the router understands.
 * <p>
 * An origin server must accept the absolute-form {@code http://host/path?query} that a proxy
 * sends (RFC 9112 section 3.2.2). The scheme and authority never reach the application: they
 * are checked and dropped, and the path and query are validated afterwards exactly like an
 * origin-form target, so dot segments, encoded separators and the other path rules apply to
 * them too. The checks are strict because a request whose target and {@code Host} disagree is a
 * classic source of routing and cache confusion:
 * <ul>
 * <li>the scheme is {@code http} or {@code https} (case-insensitive) and is otherwise ignored;
 * <li>the authority is a valid host with an optional port in range, with no user information;
 * <li>when the request has a {@code Host} header, it must equal the authority, ignoring case;
 * <li>an empty path becomes {@code /}, so {@code http://host} and {@code http://host?a=1} are
 *     requests for the root, for every method;
 * <li>anything after the authority other than a path or query (a fragment, for example) is rejected.
 * </ul>
 */
final class RequestTargets {
    private RequestTargets() {}

    /**
     * Returns the origin-form target for a request-target.
     *
     * @param target request-target as received
     * @param host the request's Host field value, or null when it has none (HTTP/1.0 only)
     * @return {@code target} itself unless it is in absolute form, else its path and query
     * @throws IllegalArgumentException if an absolute-form target is invalid or disagrees with Host
     */
    static String originForm(String target, String host) {
        if (target.startsWith("/") || target.startsWith("*")) { return target; }
        int separator = target.indexOf("://");
        if (separator < 0) { return target; } // Not absolute-form; the path rules reject it.
        var scheme = target.substring(0, separator).toLowerCase(Locale.ROOT);
        if (!scheme.equals("http") && !scheme.equals("https")) {
            throw new IllegalArgumentException("Unsupported scheme in absolute-form target");
        }
        int start = separator + 3;
        int end = start;
        while (end < target.length() && "/?#".indexOf(target.charAt(end)) < 0) { end++; }
        var authority = target.substring(start, end);
        if (!validAuthority(authority)) { throw new IllegalArgumentException("Invalid authority in absolute-form target"); }
        if (host != null && !host.equalsIgnoreCase(authority)) {
            throw new IllegalArgumentException("Absolute-form authority differs from Host");
        }
        var rest = target.substring(end);
        if (rest.isEmpty()) { return "/"; }
        if (rest.charAt(0) == '?') { return "/" + rest; }
        if (rest.charAt(0) != '/') { throw new IllegalArgumentException("Fragment in absolute-form target"); }
        return rest;
    }

    /** A host with an optional port in range, without user information, path, query or fragment. */
    static boolean validAuthority(String authority) {
        if (authority.isEmpty() || authority.endsWith(":")) { return false; }
        try {
            var uri = URI.create("http://" + authority);
            return uri.getHost() != null && uri.getRawUserInfo() == null && uri.getRawPath().isEmpty()
                    && uri.getRawQuery() == null && uri.getRawFragment() == null && uri.getPort() <= 65535;
        } catch (IllegalArgumentException invalid) { return false; }
    }
}
