package com.jsgalactic.axiom.security;

import java.util.Collections;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Pattern;

/**
 * Replaces the values of credential-bearing headers before headers are logged.
 *
 * <pre>{@code
 * var redaction = HeaderRedaction.defaults().and("X-Tenant-Secret");
 * LOG.log(INFO, "headers " + redaction.redact(ctx.request().headers()));
 * }</pre>
 *
 * <p>{@link #defaults()} redacts {@code Authorization}, {@code Proxy-Authorization},
 * {@code Cookie}, {@code Set-Cookie}, {@code X-Api-Key}, {@code X-Auth-Token},
 * {@code X-Csrf-Token}, {@code X-Xsrf-Token} and {@code X-Amz-Security-Token}. Names compare
 * case-insensitively. A redacted value becomes {@value #REDACTED}; it is replaced entirely, so
 * neither the scheme nor the length of a credential is kept. Other values are copied unchanged and
 * remain untrusted client input when they come from a request.
 *
 * <p>Redaction is a logging aid, not access control: Axiom never logs header values itself, and
 * {@code Request.toString()} omits them. Immutable and thread-safe.
 */
public final class HeaderRedaction {
    /** The value that replaces a sensitive header value. */
    public static final String REDACTED = "[redacted]";
    private static final Pattern TOKEN = Pattern.compile("[!#$%&'*+.^_`|~0-9A-Za-z-]+");
    private static final HeaderRedaction DEFAULTS = new HeaderRedaction(Set.of()).and(
            "Authorization", "Proxy-Authorization", "Cookie", "Set-Cookie", "X-Api-Key", "X-Auth-Token",
            "X-Csrf-Token", "X-Xsrf-Token", "X-Amz-Security-Token");

    private final Set<String> names;

    private HeaderRedaction(Set<String> names) {
        var copy = new TreeSet<String>(String.CASE_INSENSITIVE_ORDER);
        copy.addAll(names);
        this.names = Collections.unmodifiableSet(copy);
    }

    /**
     * Returns the default set of sensitive header names described above.
     *
     * @return default redaction
     */
    public static HeaderRedaction defaults() {
        return DEFAULTS;
    }

    /**
     * Returns a copy that also redacts the named headers.
     *
     * @param names header names, HTTP tokens
     * @return a copy redacting the additional names
     * @throws IllegalArgumentException for a name that is not a token
     */
    public HeaderRedaction and(String... names) {
        var copy = new TreeSet<String>(String.CASE_INSENSITIVE_ORDER);
        copy.addAll(this.names);
        for (var name : names) {
            if (!TOKEN.matcher(Objects.requireNonNull(name, "name")).matches()) {
                throw new IllegalArgumentException("A header name is an HTTP token");
            }
            copy.add(name.toLowerCase(Locale.ROOT));
        }
        return new HeaderRedaction(copy);
    }

    /**
     * Reports whether a header's value is redacted.
     *
     * @param name header name, matched case-insensitively
     * @return true if sensitive
     */
    public boolean isSensitive(String name) {
        return names.contains(Objects.requireNonNull(name, "name"));
    }

    /**
     * Returns a copy of the headers with sensitive values replaced by {@value #REDACTED}.
     * Works for request headers ({@code request.headers()}) and response headers alike.
     *
     * @param headers header map
     * @return immutable, case-insensitive copy, in name order
     */
    public Map<String, String> redact(Map<String, String> headers) {
        Objects.requireNonNull(headers, "headers");
        var copy = new TreeMap<String, String>(String.CASE_INSENSITIVE_ORDER);
        headers.forEach((name, value) -> copy.put(name, isSensitive(name) ? REDACTED : value));
        return Collections.unmodifiableMap(copy);
    }
}
