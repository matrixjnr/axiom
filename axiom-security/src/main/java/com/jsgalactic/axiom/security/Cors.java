package com.jsgalactic.axiom.security;

import com.jsgalactic.axiom.context.Context;
import com.jsgalactic.axiom.context.Middleware;
import com.jsgalactic.axiom.http.Response;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;

/**
 * Cross-origin resource sharing (the Fetch standard's CORS protocol) as global middleware, with a
 * strict allow-list of origins.
 *
 * <pre>{@code
 * app.use(Cors.builder()
 *         .allowOrigin("https://app.example.com")
 *         .allowMethods("GET", "POST", "DELETE")
 *         .allowHeaders("Content-Type", "Authorization")
 *         .exposeHeaders("X-Request-ID")
 *         .allowCredentials()
 *         .maxAge(Duration.ofMinutes(10))
 *         .build());
 * }</pre>
 *
 * <p><b>Configuration is checked when {@link Builder#build()} runs</b>, so an unsafe setup never
 * serves a request: at least one origin is required; origins are exact {@code scheme://host[:port]}
 * values (no paths, wildcards, user information or {@code null}); the wildcard origin
 * ({@link Builder#anyOrigin()}) cannot be combined with credentials, because a browser refuses the
 * combination and an echoed origin would amount to allowing every site; methods and headers are
 * explicit tokens, never {@code *}.
 *
 * <p><b>Requests with an {@code Origin} header</b> whose origin is on the allow-list receive
 * {@code Access-Control-Allow-Origin} with that exact origin (or {@code *} for
 * {@code anyOrigin()}), {@code Access-Control-Allow-Credentials: true} if enabled, and
 * {@code Access-Control-Expose-Headers} if configured. A request from any other origin is not
 * blocked, since CORS is enforced by the browser and is no authentication: it is answered normally
 * without any CORS header, which makes the browser refuse to expose the response. The comparison is
 * exact and case-sensitive on the serialized origin, so {@code https://app.example.com.evil.test},
 * a different port or scheme, and a list of several origins never match.
 *
 * <p><b>Preflight</b> is an {@code OPTIONS} request with {@code Origin} and
 * {@code Access-Control-Request-Method}. The middleware runs the rest of the chain first, which for
 * a routed path is the router's automatic {@code OPTIONS} answer (204 with {@code Allow}), and only
 * decorates a successful answer. It adds {@code Access-Control-Allow-Origin},
 * {@code Access-Control-Allow-Methods} (the configured methods the route also allows),
 * {@code Access-Control-Allow-Headers} (the requested headers) and {@code Access-Control-Max-Age}
 * when the origin is allowed, the requested method is configured and, when the answer has an
 * {@code Allow} header, supported by the route, and every requested header is configured. Otherwise
 * the answer is left without CORS headers, so the browser fails the preflight. Unrouted paths keep
 * their 404, so a preflight never succeeds for a path that does not exist. An application's own
 * {@code OPTIONS} route is passed through and decorated the same way.
 *
 * <p>{@code Vary: Origin} is added to every response unless {@code anyOrigin()} is used, and a
 * preflight also varies on the two request headers, so caches never serve a response meant for one
 * origin to another. Existing {@code Vary} values are kept.
 *
 * <p><b>Placement.</b> Register it globally with {@code app.use(...)}; group middleware do not run
 * for the router's own answers, so a group-scoped instance would not see preflights. The
 * middleware is immutable and thread-safe. Like other middleware it does not decorate responses
 * mapped from exceptions (see the middleware documentation).
 */
public final class Cors implements Middleware {
    private static final Pattern TOKEN = Pattern.compile("[!#$%&'*+.^_`|~0-9A-Za-z-]+");
    private static final int MAX_REQUESTED_HEADERS = 64;

    private final boolean anyOrigin;
    private final Set<String> origins;
    private final List<String> methods;
    private final Set<String> headersLower;
    private final String exposed;
    private final boolean credentials;
    private final long maxAgeSeconds;

    private Cors(Builder builder) {
        anyOrigin = builder.anyOrigin;
        origins = Set.copyOf(builder.origins);
        methods = List.copyOf(builder.methods);
        var lower = new LinkedHashSet<String>();
        builder.headers.forEach(header -> lower.add(header.toLowerCase(Locale.ROOT)));
        headersLower = Set.copyOf(lower);
        exposed = String.join(", ", builder.exposed);
        credentials = builder.credentials;
        maxAgeSeconds = builder.maxAge.toSeconds();
    }

    /**
     * Starts a configuration.
     *
     * @return a new builder
     */
    public static Builder builder() {
        return new Builder();
    }

    @Override
    public Response handle(Context context, Next next) throws Exception {
        var request = context.request();
        var origin = request.header("Origin").orElse(null);
        boolean preflight = request.method().equals("OPTIONS") && origin != null
                && request.header("Access-Control-Request-Method").isPresent();
        var response = next.run();
        if (!anyOrigin) { response = vary(response, preflight ? "Origin, Access-Control-Request-Method, Access-Control-Request-Headers" : "Origin"); }
        if (origin == null || !(anyOrigin || origins.contains(origin))) { return response; }
        if (!preflight) { return actual(response, origin); }
        return preflight(request.header("Access-Control-Request-Method").get(),
                request.header("Access-Control-Request-Headers").orElse(null), origin, response);
    }

    private Response actual(Response response, String origin) {
        response = response.withHeader("Access-Control-Allow-Origin", anyOrigin ? "*" : origin);
        if (credentials) { response = response.withHeader("Access-Control-Allow-Credentials", "true"); }
        if (!exposed.isEmpty()) { response = response.withHeader("Access-Control-Expose-Headers", exposed); }
        return response;
    }

    private Response preflight(String method, String requestedHeaders, String origin, Response response) {
        if (response.status() < 200 || response.status() > 299 || !methods.contains(method)) { return response; }
        var allow = response.headers().get("Allow");
        List<String> permitted = methods;
        if (allow != null) {
            var routed = new LinkedHashSet<String>();
            for (var item : allow.split(",")) { routed.add(item.strip()); }
            if (!routed.contains(method)) { return response; }
            permitted = methods.stream().filter(routed::contains).toList();
        }
        var requested = requested(requestedHeaders);
        if (requested == null) { return response; }
        response = response.withHeader("Access-Control-Allow-Origin", anyOrigin ? "*" : origin)
                .withHeader("Access-Control-Allow-Methods", String.join(", ", permitted));
        if (!requested.isEmpty()) { response = response.withHeader("Access-Control-Allow-Headers", String.join(", ", requested)); }
        if (credentials) { response = response.withHeader("Access-Control-Allow-Credentials", "true"); }
        if (maxAgeSeconds > 0) { response = response.withHeader("Access-Control-Max-Age", Long.toString(maxAgeSeconds)); }
        return response;
    }

    /** The requested header names if all are configured (empty if none were requested), else null. */
    private List<String> requested(String header) {
        var names = new ArrayList<String>();
        if (header == null || header.isBlank()) { return names; }
        for (var item : header.split(",", -1)) {
            var name = item.strip().toLowerCase(Locale.ROOT);
            if (names.size() >= MAX_REQUESTED_HEADERS || !TOKEN.matcher(name).matches() || !headersLower.contains(name)) { return null; }
            names.add(name);
        }
        return names;
    }

    private static Response vary(Response response, String values) {
        var existing = response.headers().get("Vary");
        if (existing == null || existing.isBlank()) { return response.withHeader("Vary", values); }
        if (existing.strip().equals("*")) { return response; }
        var merged = new LinkedHashSet<String>();
        var seen = new TreeSet<String>(String.CASE_INSENSITIVE_ORDER);
        for (var item : (existing + ", " + values).split(",")) {
            var name = item.strip();
            if (!name.isEmpty() && seen.add(name)) { merged.add(name); }
        }
        return response.withHeader("Vary", String.join(", ", merged));
    }

    /** Configures {@link Cors}. Not thread-safe; build once at startup. */
    public static final class Builder {
        private final Set<String> origins = new LinkedHashSet<>();
        private final List<String> methods = new ArrayList<>(List.of("GET", "HEAD"));
        private final Set<String> headers = new LinkedHashSet<>();
        private final Set<String> exposed = new LinkedHashSet<>();
        private boolean anyOrigin;
        private boolean credentials;
        private Duration maxAge = Duration.ofMinutes(10);

        private Builder() {}

        /**
         * Allows one origin. Call again for more.
         *
         * @param origin exact serialized origin: {@code http} or {@code https}, a host name or IP
         *        literal and an optional port, with no path, wildcard or user information
         * @return this builder
         * @throws IllegalArgumentException for anything else, including {@code *} (use
         *         {@link #anyOrigin()}) and {@code null}
         */
        public Builder allowOrigin(String origin) {
            Objects.requireNonNull(origin, "origin");
            if (origin.equals("*")) { throw new IllegalArgumentException("Use anyOrigin() for the wildcard origin"); }
            int separator = origin.indexOf("://");
            var scheme = separator < 0 ? "" : origin.substring(0, separator);
            var authority = separator < 0 ? null : Forwarded.authority(origin.substring(separator + 3));
            if (!(scheme.equals("http") || scheme.equals("https")) || authority == null
                    || !origin.substring(separator + 3).equals(origin.substring(separator + 3).toLowerCase(Locale.ROOT))) {
                throw new IllegalArgumentException("An origin is scheme://host[:port] in lower case with scheme http or https, "
                        + "without path, wildcard or user information");
            }
            origins.add(origin);
            return this;
        }

        /**
         * Allows every origin with {@code Access-Control-Allow-Origin: *}, for public APIs that
         * never receive credentials. Cannot be combined with {@link #allowCredentials()}.
         *
         * @return this builder
         */
        public Builder anyOrigin() {
            anyOrigin = true;
            return this;
        }

        /**
         * Sets the methods a preflight may request, replacing the default of {@code GET} and
         * {@code HEAD}. A method is still only granted if the route allows it.
         *
         * @param methods HTTP method tokens, upper case, never {@code *}
         * @return this builder
         * @throws IllegalArgumentException for a wildcard, an invalid token or an empty list
         */
        public Builder allowMethods(String... methods) {
            Objects.requireNonNull(methods, "methods");
            var parsed = new ArrayList<String>();
            for (var method : methods) {
                if (!TOKEN.matcher(Objects.requireNonNull(method, "method")).matches() || method.equals("*")
                        || !method.equals(method.toUpperCase(Locale.ROOT))) {
                    throw new IllegalArgumentException("A CORS method is an upper-case HTTP token, never *");
                }
                if (!parsed.contains(method)) { parsed.add(method); }
            }
            if (parsed.isEmpty()) { throw new IllegalArgumentException("At least one method is required"); }
            this.methods.clear();
            this.methods.addAll(parsed);
            return this;
        }

        /**
         * Sets the request headers a preflight may ask for (none by default). Matching is
         * case-insensitive. Headers the Fetch standard always permits need not be listed.
         *
         * @param headers header name tokens, never {@code *}
         * @return this builder
         */
        public Builder allowHeaders(String... headers) {
            return collect(this.headers, headers, "header");
        }

        /**
         * Makes response headers readable by the page's script.
         *
         * @param headers header name tokens, never {@code *}
         * @return this builder
         */
        public Builder exposeHeaders(String... headers) {
            return collect(exposed, headers, "exposed header");
        }

        /**
         * Lets browsers send credentials (cookies, {@code Authorization}) with cross-origin
         * requests; incompatible with {@link #anyOrigin()}.
         *
         * @return this builder
         */
        public Builder allowCredentials() {
            credentials = true;
            return this;
        }

        /**
         * Sets how long a browser may cache a preflight answer.
         *
         * @param maxAge zero (no header) to 24 hours, whole seconds; 10 minutes by default
         * @return this builder
         */
        public Builder maxAge(Duration maxAge) {
            Objects.requireNonNull(maxAge, "maxAge");
            if (maxAge.isNegative() || maxAge.compareTo(Duration.ofHours(24)) > 0 || maxAge.getNano() != 0) {
                throw new IllegalArgumentException("The preflight max age is zero to 24 hours in whole seconds");
            }
            this.maxAge = maxAge;
            return this;
        }

        /**
         * Builds the middleware.
         *
         * @return the immutable middleware
         * @throws IllegalStateException without an origin, or for {@code anyOrigin()} combined with
         *         origins or credentials
         */
        public Cors build() {
            if (!anyOrigin && origins.isEmpty()) { throw new IllegalStateException("At least one allowed origin is required"); }
            if (anyOrigin && credentials) {
                throw new IllegalStateException("The wildcard origin cannot be combined with credentials");
            }
            if (anyOrigin && !origins.isEmpty()) {
                throw new IllegalStateException("anyOrigin() already allows every origin; do not list origins as well");
            }
            return new Cors(this);
        }

        private Builder collect(Set<String> target, String[] names, String kind) {
            Objects.requireNonNull(names, "names");
            for (var name : names) {
                if (!TOKEN.matcher(Objects.requireNonNull(name, kind)).matches() || name.equals("*")) {
                    throw new IllegalArgumentException("A CORS " + kind + " is an HTTP header token, never *");
                }
                target.add(name);
            }
            return this;
        }
    }
}
