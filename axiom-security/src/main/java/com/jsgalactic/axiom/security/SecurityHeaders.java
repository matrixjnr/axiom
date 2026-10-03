package com.jsgalactic.axiom.security;

import com.jsgalactic.axiom.context.Context;
import com.jsgalactic.axiom.context.Middleware;
import com.jsgalactic.axiom.http.Response;
import java.util.Collections;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/**
 * Middleware that adds secure default response headers for an HTTP API.
 *
 * <pre>{@code
 * app.use(SecurityHeaders.defaults());
 * app.use(SecurityHeaders.defaults().with("Strict-Transport-Security", "max-age=31536000"));
 * }</pre>
 *
 * <p>{@link #defaults()} sets:
 * <table class="striped">
 * <caption>Default headers</caption>
 * <thead><tr><th>Header</th><th>Value</th><th>Effect</th></tr></thead>
 * <tbody>
 * <tr><td>{@code X-Content-Type-Options}</td><td>{@code nosniff}</td><td>browsers keep the declared media type</td></tr>
 * <tr><td>{@code X-Frame-Options}</td><td>{@code DENY}</td><td>no framing (older browsers)</td></tr>
 * <tr><td>{@code Content-Security-Policy}</td><td>{@code default-src 'none'; frame-ancestors 'none'}</td>
 * <td>a response rendered by a browser loads nothing and cannot be framed</td></tr>
 * <tr><td>{@code Referrer-Policy}</td><td>{@code no-referrer}</td><td>no URL leaks through Referer</td></tr>
 * <tr><td>{@code Cross-Origin-Resource-Policy}</td><td>{@code same-origin}</td><td>other origins cannot embed responses</td></tr>
 * </tbody>
 * </table>
 * {@code Strict-Transport-Security} is not a default: HSTS is only meaningful, and safe to enable,
 * where the deployment serves HTTPS for the whole host and keeps doing so. Add it with
 * {@link #with(String, String)} on a TLS listener or behind a TLS-terminating proxy.
 *
 * <p>A header the response already carries is left unchanged, so a handler can deliberately loosen
 * one (for example a CSP for an HTML page). Registered globally, the middleware also decorates the
 * router's own 404, 405, automatic OPTIONS and 501 answers. It also decorates responses mapped
 * from exceptions (problem responses, including the 401 and 403 of {@link Security} policies, and
 * error handler responses) through {@link Middleware#afterError}.
 *
 * <p>Immutable and thread-safe; one instance can serve every request.
 */
public final class SecurityHeaders implements Middleware {
    private static final SecurityHeaders DEFAULTS = new SecurityHeaders(Map.of())
            .with("X-Content-Type-Options", "nosniff")
            .with("X-Frame-Options", "DENY")
            .with("Content-Security-Policy", "default-src 'none'; frame-ancestors 'none'")
            .with("Referrer-Policy", "no-referrer")
            .with("Cross-Origin-Resource-Policy", "same-origin");

    private final Map<String, String> headers;

    private SecurityHeaders(Map<String, String> headers) {
        var copy = new TreeMap<String, String>(String.CASE_INSENSITIVE_ORDER);
        copy.putAll(headers);
        this.headers = Collections.unmodifiableMap(copy);
    }

    /**
     * Returns the default header set described above.
     *
     * @return default security headers
     */
    public static SecurityHeaders defaults() {
        return DEFAULTS;
    }

    /**
     * Returns a copy that also sets, or replaces, one header.
     *
     * @param name header name, an HTTP token
     * @param value header value without control characters
     * @return a copy with the header
     * @throws IllegalArgumentException for an invalid name or value
     */
    public SecurityHeaders with(String name, String value) {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(value, "value");
        Response.of(200, null).withHeader(name, value); // Validates exactly as responses do.
        var copy = new TreeMap<String, String>(String.CASE_INSENSITIVE_ORDER);
        copy.putAll(headers);
        copy.put(name, value);
        return new SecurityHeaders(copy);
    }

    /**
     * Returns a copy that no longer sets one header.
     *
     * @param name header name, matched case-insensitively
     * @return a copy without the header
     */
    public SecurityHeaders without(String name) {
        Objects.requireNonNull(name, "name");
        var copy = new TreeMap<String, String>(String.CASE_INSENSITIVE_ORDER);
        copy.putAll(headers);
        copy.remove(name);
        return new SecurityHeaders(copy);
    }

    /**
     * Returns the headers this middleware sets.
     *
     * @return immutable, case-insensitive header map
     */
    public Map<String, String> headers() {
        return headers;
    }

    @Override
    public Response handle(Context context, Next next) throws Exception {
        return decorate(next.run());
    }

    @Override
    public Response afterError(Context context, Response response) {
        return decorate(response);
    }

    private Response decorate(Response response) {
        for (var header : headers.entrySet()) {
            if (!response.headers().containsKey(header.getKey())) {
                response = response.withHeader(header.getKey(), header.getValue());
            }
        }
        return response;
    }
}
