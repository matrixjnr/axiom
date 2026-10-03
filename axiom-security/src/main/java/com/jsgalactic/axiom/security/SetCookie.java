package com.jsgalactic.axiom.security;

import com.jsgalactic.axiom.http.Response;
import java.time.Duration;
import java.util.Locale;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * A {@code Set-Cookie} header with secure defaults, validated when it is created.
 *
 * <pre>{@code
 * var cookie = SetCookie.of("__Host-theme", "dark");           // Secure; HttpOnly; SameSite=Lax; Path=/
 * return response.withHeader("Set-Cookie", cookie.header());
 * }</pre>
 *
 * <p><b>Defaults.</b> {@code Secure}, {@code HttpOnly}, {@code SameSite=Lax}, {@code Path=/}, no
 * {@code Domain} (so the cookie is host-only and no sub-domain receives it), no {@code Max-Age}
 * (a browser-session cookie).
 *
 * <p><b>Rules enforced.</b> The name is an HTTP token and the value consists of cookie octets; name
 * plus value is at most 4,096 bytes. A name starting with {@code __Host-} (compared ignoring case,
 * as browsers do) requires {@code Secure}, {@code Path=/} and no {@code Domain}; {@code __Secure-}
 * requires {@code Secure}. {@code SameSite=None} requires {@code Secure}. A {@code Domain} is a
 * lower-case DNS name (no leading dot, not an IP address); a {@code Path} starts with {@code /} and
 * has no control characters or semicolons; {@code Max-Age} is 0 (delete) to 400 days. Violations
 * throw {@link IllegalArgumentException}.
 *
 * <p>One response can carry one {@code Set-Cookie} in Axiom's header model; {@link #addTo} refuses to
 * replace another.
 *
 * <p>Immutable and thread-safe. {@code toString()} omits the value.
 */
public final class SetCookie {
    private static final Pattern DOMAIN = Pattern.compile("[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?(\\.[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?)*");
    private static final Duration MAX_AGE = Duration.ofDays(400);

    /** The {@code SameSite} attribute. */
    public enum SameSite {
        /** Sent only on same-site requests. */
        STRICT("Strict"),
        /** Also sent on top-level cross-site navigations with a safe method. */
        LAX("Lax"),
        /** Sent on all requests; requires {@code Secure}. */
        NONE("None");

        private final String text;

        SameSite(String text) { this.text = text; }
    }

    private final String name;
    private final String value;
    private final boolean secure;
    private final boolean httpOnly;
    private final SameSite sameSite;
    private final String path;
    private final String domain;
    private final Duration maxAge;

    private SetCookie(String name, String value, boolean secure, boolean httpOnly, SameSite sameSite, String path,
                      String domain, Duration maxAge) {
        if (!Cookies.isToken(Objects.requireNonNull(name, "name"))) { throw new IllegalArgumentException("Invalid cookie name"); }
        if (!Cookies.isCookieValue(Objects.requireNonNull(value, "value"))) { throw new IllegalArgumentException("Invalid cookie value"); }
        if (name.length() + value.length() > 4096) { throw new IllegalArgumentException("Cookie name and value exceed 4096 bytes"); }
        Objects.requireNonNull(sameSite, "sameSite");
        Objects.requireNonNull(path, "path");
        if (path.isEmpty() || path.charAt(0) != '/' || path.length() > 1024
                || path.chars().anyMatch(c -> c < 0x20 || c > 0x7e || c == ';')) {
            throw new IllegalArgumentException("Invalid cookie path");
        }
        if (domain != null && (domain.length() > 253 || !DOMAIN.matcher(domain).matches() || domain.chars().allMatch(c -> c == '.' || (c >= '0' && c <= '9')))) {
            throw new IllegalArgumentException("Invalid cookie domain");
        }
        if (maxAge != null && (maxAge.isNegative() || maxAge.compareTo(MAX_AGE) > 0)) {
            throw new IllegalArgumentException("Max-Age must be between 0 and 400 days");
        }
        var lower = name.toLowerCase(Locale.ROOT);
        if ((lower.startsWith("__host-") || lower.startsWith("__secure-")) && !secure) {
            throw new IllegalArgumentException("Prefixed cookies require Secure");
        }
        if (lower.startsWith("__host-") && (!path.equals("/") || domain != null)) {
            throw new IllegalArgumentException("__Host- cookies require Path=/ and no Domain");
        }
        if (sameSite == SameSite.NONE && !secure) { throw new IllegalArgumentException("SameSite=None requires Secure"); }
        this.name = name;
        this.value = value;
        this.secure = secure;
        this.httpOnly = httpOnly;
        this.sameSite = sameSite;
        this.path = path;
        this.domain = domain;
        this.maxAge = maxAge;
    }

    /**
     * Creates a cookie with the secure defaults.
     *
     * @param name cookie name
     * @param value cookie value, possibly empty
     * @return the cookie
     * @throws IllegalArgumentException if a rule above is violated
     */
    public static SetCookie of(String name, String value) {
        return new SetCookie(name, value, true, true, SameSite.LAX, "/", null, null);
    }

    /**
     * Returns a copy with another value.
     *
     * @param value cookie value
     * @return the copy
     */
    public SetCookie withValue(String value) { return new SetCookie(name, value, secure, httpOnly, sameSite, path, domain, maxAge); }

    /**
     * Returns a copy with the {@code Secure} attribute set or cleared. Browsers drop Secure cookies
     * received over plain HTTP (except on localhost), so clear it only for local development.
     *
     * @param secure whether the cookie is Secure
     * @return the copy
     */
    public SetCookie secure(boolean secure) { return new SetCookie(name, value, secure, httpOnly, sameSite, path, domain, maxAge); }

    /**
     * Returns a copy with {@code HttpOnly} set or cleared.
     *
     * @param httpOnly whether scripts are denied access
     * @return the copy
     */
    public SetCookie httpOnly(boolean httpOnly) { return new SetCookie(name, value, secure, httpOnly, sameSite, path, domain, maxAge); }

    /**
     * Returns a copy with another {@code SameSite} attribute.
     *
     * @param sameSite the attribute
     * @return the copy
     */
    public SetCookie sameSite(SameSite sameSite) { return new SetCookie(name, value, secure, httpOnly, sameSite, path, domain, maxAge); }

    /**
     * Returns a copy with another {@code Path}.
     *
     * @param path path starting with {@code /}
     * @return the copy
     */
    public SetCookie path(String path) { return new SetCookie(name, value, secure, httpOnly, sameSite, path, domain, maxAge); }

    /**
     * Returns a copy with a {@code Domain}, which also sends the cookie to every sub-domain.
     *
     * @param domain lower-case DNS name without a leading dot, or null for a host-only cookie
     * @return the copy
     */
    public SetCookie domain(String domain) { return new SetCookie(name, value, secure, httpOnly, sameSite, path, domain, maxAge); }

    /**
     * Returns a copy with a {@code Max-Age}, in whole seconds (rounded down).
     *
     * @param maxAge lifetime from 0 to 400 days, or null for a browser-session cookie
     * @return the copy
     */
    public SetCookie maxAge(Duration maxAge) {
        return new SetCookie(name, value, secure, httpOnly, sameSite, path, domain, maxAge);
    }

    /**
     * Returns a copy that tells the browser to delete the cookie (empty value, {@code Max-Age=0});
     * name, path and domain must match the cookie being deleted.
     *
     * @return the deleting cookie
     */
    public SetCookie expire() { return new SetCookie(name, "", secure, httpOnly, sameSite, path, domain, Duration.ZERO); }

    /**
     * Returns the cookie name.
     *
     * @return the name
     */
    public String name() { return name; }

    /**
     * Returns the cookie value.
     *
     * @return the value, a credential for session and token cookies
     */
    public String value() { return value; }

    /**
     * Returns whether the cookie is {@code Secure}.
     *
     * @return the attribute
     */
    public boolean isSecure() { return secure; }

    /**
     * Returns whether the cookie is {@code HttpOnly}.
     *
     * @return the attribute
     */
    public boolean isHttpOnly() { return httpOnly; }

    /**
     * Returns the header value, for example {@code sid=abc; Path=/; Secure; HttpOnly; SameSite=Lax}.
     *
     * @return the {@code Set-Cookie} value
     */
    public String header() {
        var out = new StringBuilder(name.length() + value.length() + 64).append(name).append('=').append(value);
        out.append("; Path=").append(path);
        if (domain != null) { out.append("; Domain=").append(domain); }
        if (maxAge != null) { out.append("; Max-Age=").append(maxAge.toSeconds()); }
        if (secure) { out.append("; Secure"); }
        if (httpOnly) { out.append("; HttpOnly"); }
        return out.append("; SameSite=").append(sameSite.text).toString();
    }

    /**
     * Returns the response with this cookie as its {@code Set-Cookie}.
     *
     * @param response the response
     * @return a copy carrying the header
     * @throws IllegalStateException if the response already has a {@code Set-Cookie}
     */
    public Response addTo(Response response) {
        if (Objects.requireNonNull(response, "response").headers().containsKey("Set-Cookie")) {
            throw new IllegalStateException("The response already sets a cookie; a response carries one Set-Cookie");
        }
        return response.withHeader("Set-Cookie", header());
    }

    @Override
    public String toString() {
        return "SetCookie[" + name + "]";
    }
}
