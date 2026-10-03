package com.jsgalactic.axiom.security;

import com.jsgalactic.axiom.context.Context;
import com.jsgalactic.axiom.context.Middleware;
import com.jsgalactic.axiom.error.ForbiddenException;
import com.jsgalactic.axiom.http.Request;
import com.jsgalactic.axiom.http.Response;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.util.Base64;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Predicate;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Cross-site request forgery protection for browser clients that authenticate with cookies.
 *
 * <pre>{@code
 * // with server-side sessions: one random token per session
 * var csrf = Csrf.synchronizer(sessions).allowedOrigins("https://app.example.com").build();
 * // without a session: an HMAC-signed token in a cookie, echoed in a header
 * var csrf = Csrf.doubleSubmit(secretKey).build();
 *
 * app.use(sessions);
 * app.use(security.authenticate());
 * app.use(csrf);
 * app.get("/form", ctx -> render(csrf.token(ctx)));      // embed the token in the page
 * }</pre>
 *
 * <p><b>What is checked.</b> Requests with a safe method ({@code GET}, {@code HEAD}, {@code OPTIONS},
 * {@code TRACE}) pass untouched; they must not change state. Every other method must pass, in this
 * order, each failure being 403 {@code application/problem+json} with code {@code csrf_rejected}
 * (one code, so clients cannot tell which check failed):
 * <ol>
 * <li>{@code Sec-Fetch-Site}, when the browser sends it: {@code same-origin} and {@code none}
 * pass, {@code same-site} only with {@link Builder#allowSameSite}, anything else (including
 * {@code cross-site}) fails. Disable with {@link Builder#fetchMetadata(boolean)}.</li>
 * <li>{@code Origin}, when present: it must be one of {@link Builder#allowedOrigins} or, if none are
 * configured, have the same host and port as the {@code Host} header ({@code null} and malformed
 * values fail). A request without {@code Origin} (older browsers, non-browser clients) is decided by
 * the token alone.</li>
 * <li>The token in the request header ({@code X-CSRF-Token} by default), compared in constant time
 * with the expected one.</li>
 * </ol>
 * The first two are defence in depth: the token is the proof, and a missing or wrong token fails
 * whatever the headers say.
 *
 * <p><b>Synchronizer token</b> ({@link #synchronizer}). One random 256-bit token per session, kept in
 * the session. {@link #token(Context)} returns it (creating the session and token on first use) for
 * embedding in a page or API response; it stays valid for the life of the session and is discarded
 * when the session signs in ({@link Session#authenticate}), so fetch a fresh one after login. Use
 * {@link Sessions} before this middleware.
 *
 * <p><b>Double-submit cookie</b> ({@link #doubleSubmit}). No server state: the token is
 * {@code issued-at . nonce . HMAC-SHA256(key, binding, issued-at, nonce)}, set as a cookie (named
 * {@code __Host-csrf} by default, readable by scripts unless {@link Builder#httpOnly} is set) and
 * echoed by the client in the header. A request passes when the header equals the cookie and the
 * token's HMAC verifies for the request's binding, so an attacker who can plant a cookie (a
 * sub-domain) still cannot forge a token bound to another user. The binding is the authenticated
 * principal by default ({@link Builder#binding}); tokens older than {@link Builder#maxAge} (12 hours
 * by default) are refused. The cookie is issued on any response when the request has no valid one
 * and the response sets no other cookie (a response carries one {@code Set-Cookie}); after sign-in the
 * old token no longer matches the new binding, so the next safe request obtains a fresh one. Changing
 * the key invalidates all tokens at once.
 *
 * <p>Requests that carry credentials a browser never attaches on its own (an {@code Authorization}
 * header) are not exposed to CSRF; {@link Builder#exemptWhen} lets an application skip the check for
 * them.
 *
 * <p>Immutable configuration, thread-safe, shared by all requests.
 */
public final class Csrf implements Middleware {
    private static final String SESSION_ATTRIBUTE = Session.RESERVED + "csrf";
    private static final Set<String> SAFE_METHODS = Set.of("GET", "HEAD", "OPTIONS", "TRACE");
    private static final int MAX_TOKEN_LENGTH = 256;
    private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();

    private final Sessions sessions;
    private final byte[] key;
    private final String headerName;
    private final Set<String> allowedOrigins;
    private final boolean allowSameSite;
    private final boolean fetchMetadata;
    private final Predicate<Request> exempt;
    private final Function<Context, String> binding;
    private final Duration maxAge;
    private final Clock clock;
    private final SecureRandom random;
    private final SetCookie cookieTemplate;
    private final Map<Request, String> issued = Collections.synchronizedMap(new IdentityHashMap<>());

    private Csrf(Builder builder) {
        sessions = builder.sessions;
        key = builder.key;
        headerName = builder.headerName;
        allowedOrigins = Set.copyOf(builder.allowedOrigins);
        allowSameSite = builder.allowSameSite;
        fetchMetadata = builder.fetchMetadata;
        exempt = builder.exempt;
        binding = builder.binding;
        maxAge = builder.maxAge;
        clock = builder.clock;
        random = builder.random;
        if (key == null) {
            cookieTemplate = null;
        } else {
            var name = builder.cookieName != null ? builder.cookieName : builder.secure ? "__Host-csrf" : "csrf";
            cookieTemplate = SetCookie.of(name, "x").secure(builder.secure).sameSite(builder.sameSite).httpOnly(builder.httpOnly);
        }
    }

    /**
     * Starts a configuration that keeps one random token in each session.
     *
     * @param sessions the sessions in use; its middleware must run before this one
     * @return a builder
     */
    public static Builder synchronizer(Sessions sessions) {
        return new Builder(Objects.requireNonNull(sessions, "sessions"), null);
    }

    /**
     * Starts a configuration with HMAC-signed double-submit cookies.
     *
     * @param key the signing key, at least 32 random bytes; copied
     * @return a builder
     * @throws IllegalArgumentException if the key is shorter than 32 bytes
     */
    public static Builder doubleSubmit(byte[] key) {
        Objects.requireNonNull(key, "key");
        if (key.length < 32) { throw new IllegalArgumentException("The CSRF key must be at least 32 bytes"); }
        return new Builder(null, key.clone());
    }

    /** Builds {@link Csrf}; used during configuration, not thread-safe. */
    public static final class Builder {
        private final Sessions sessions;
        private final byte[] key;
        private String headerName = "X-CSRF-Token";
        private final Set<String> allowedOrigins = new LinkedHashSet<>();
        private boolean allowSameSite;
        private boolean fetchMetadata = true;
        private Predicate<Request> exempt = request -> false;
        private Function<Context, String> binding = context -> context.identity().map(identity -> identity.principal()).orElse("");
        private Duration maxAge = Duration.ofHours(12);
        private Clock clock = Clock.systemUTC();
        private SecureRandom random = new SecureRandom();
        private String cookieName;
        private boolean secure = true;
        private SetCookie.SameSite sameSite = SetCookie.SameSite.LAX;
        private boolean httpOnly;

        private Builder(Sessions sessions, byte[] key) {
            this.sessions = sessions;
            this.key = key;
        }

        /**
         * Sets the request header carrying the token (default {@code X-CSRF-Token}). Browsers only send
         * custom headers from scripts, which cross-origin pages cannot do without CORS approval.
         *
         * @param headerName an HTTP header token
         * @return this builder
         */
        public Builder headerName(String headerName) {
            Objects.requireNonNull(headerName, "headerName");
            if (!Cookies.isToken(headerName)) { throw new IllegalArgumentException("Invalid header name"); }
            this.headerName = headerName;
            return this;
        }

        /**
         * Sets the origins allowed to send unsafe requests, as exact {@code scheme://host[:port]} values
         * in lower case. Without any, an {@code Origin} must match the {@code Host} header.
         *
         * @param origins one or more origins
         * @return this builder
         */
        public Builder allowedOrigins(String... origins) {
            for (var origin : origins) {
                if (parseOrigin(Objects.requireNonNull(origin, "origin")) == null || !origin.equals(origin.toLowerCase(Locale.ROOT))) {
                    throw new IllegalArgumentException("Origins are exact lower-case http(s)://host[:port] values");
                }
                allowedOrigins.add(origin);
            }
            return this;
        }

        /**
         * Accepts {@code Sec-Fetch-Site: same-site} (other origins of the same registrable domain);
         * off by default.
         *
         * @param allow whether to accept it
         * @return this builder
         */
        public Builder allowSameSite(boolean allow) {
            this.allowSameSite = allow;
            return this;
        }

        /**
         * Turns the {@code Sec-Fetch-Site} check on (default) or off.
         *
         * @param enabled whether to check it
         * @return this builder
         */
        public Builder fetchMetadata(boolean enabled) {
            this.fetchMetadata = enabled;
            return this;
        }

        /**
         * Skips all checks for requests the predicate accepts, for example those with an
         * {@code Authorization} header, which a browser never adds by itself. The predicate decides
         * on request facts only; anything an attacker's page can cause (cookies, form posts) must not
         * make it return true.
         *
         * @param exempt the exemption rule
         * @return this builder
         */
        public Builder exemptWhen(Predicate<Request> exempt) {
            this.exempt = Objects.requireNonNull(exempt, "exempt");
            return this;
        }

        /**
         * Sets what a double-submit token is bound to (default: the principal, or empty when
         * anonymous). Ignored by the synchronizer variant.
         *
         * @param binding a pure function of the request that does not use client-controlled data
         * @return this builder
         */
        public Builder binding(Function<Context, String> binding) {
            this.binding = Objects.requireNonNull(binding, "binding");
            return this;
        }

        /**
         * Sets how long a double-submit token is accepted (default 12 hours).
         *
         * @param maxAge 1 minute to 30 days
         * @return this builder
         */
        public Builder maxAge(Duration maxAge) {
            Objects.requireNonNull(maxAge, "maxAge");
            if (maxAge.compareTo(Duration.ofMinutes(1)) < 0 || maxAge.compareTo(Duration.ofDays(30)) > 0) {
                throw new IllegalArgumentException("maxAge must be between 1 minute and 30 days");
            }
            this.maxAge = maxAge;
            return this;
        }

        /**
         * Sets the time source of double-submit tokens, for deterministic tests.
         *
         * @param clock the clock
         * @return this builder
         */
        public Builder clock(Clock clock) {
            this.clock = Objects.requireNonNull(clock, "clock");
            return this;
        }

        /**
         * Sets the source of randomness, for tests.
         *
         * @param random a cryptographically strong generator
         * @return this builder
         */
        public Builder random(SecureRandom random) {
            this.random = Objects.requireNonNull(random, "random");
            return this;
        }

        /**
         * Sets the double-submit cookie name (default {@code __Host-csrf}, or {@code csrf} without
         * {@code Secure}).
         *
         * @param cookieName a cookie token
         * @return this builder
         */
        public Builder cookieName(String cookieName) {
            this.cookieName = Objects.requireNonNull(cookieName, "cookieName");
            return this;
        }

        /**
         * Sets {@code Secure} on the double-submit cookie (default on; off only for local development).
         *
         * @param secure whether the cookie is Secure
         * @return this builder
         */
        public Builder secure(boolean secure) {
            this.secure = secure;
            return this;
        }

        /**
         * Sets {@code SameSite} on the double-submit cookie (default Lax).
         *
         * @param sameSite the attribute
         * @return this builder
         */
        public Builder sameSite(SetCookie.SameSite sameSite) {
            this.sameSite = Objects.requireNonNull(sameSite, "sameSite");
            return this;
        }

        /**
         * Makes the double-submit cookie invisible to scripts (default off). The page then needs the
         * token from {@link Csrf#token(Context)} instead of reading the cookie.
         *
         * @param httpOnly whether the cookie is HttpOnly
         * @return this builder
         */
        public Builder httpOnly(boolean httpOnly) {
            this.httpOnly = httpOnly;
            return this;
        }

        /**
         * Builds the middleware.
         *
         * @return the CSRF protection
         * @throws IllegalArgumentException if the cookie settings are inconsistent
         */
        public Csrf build() {
            return new Csrf(this);
        }
    }

    /**
     * Returns the token the client must send with unsafe requests.
     *
     * @param context the context of a request inside this middleware
     * @return the token; with the synchronizer variant this creates the session's token on first use
     * @throws IllegalStateException if this middleware is not active for the request
     */
    public String token(Context context) {
        if (key != null) {
            var token = issued.get(Objects.requireNonNull(context, "context").request());
            if (token == null) { throw new IllegalStateException("The CSRF middleware is not registered before this handler"); }
            return token;
        }
        var session = sessions.session(Objects.requireNonNull(context, "context"));
        var existing = session.attribute(SESSION_ATTRIBUTE);
        if (existing.isPresent()) { return existing.get(); }
        var created = randomToken();
        session.put(SESSION_ATTRIBUTE, created);
        return created;
    }

    @Override
    public Response handle(Context context, Next next) throws Exception {
        var request = Objects.requireNonNull(context, "context").request();
        boolean safe = SAFE_METHODS.contains(request.method());
        if (key != null) { return doubleSubmit(context, request, safe, next); }
        if (!safe && !exempt.test(request)) { verifySynchronizer(context, request); }
        return next.run();
    }

    private void verifySynchronizer(Context context, Request request) {
        checkOrigin(request);
        var expected = sessions.session(context).attribute(SESSION_ATTRIBUTE);
        if (expected.isEmpty() || !same(expected.get(), presented(request))) { throw new ForbiddenException("csrf_rejected"); }
    }

    // ---- double-submit cookie

    private Response doubleSubmit(Context context, Request request, boolean safe, Next next) throws Exception {
        if (issued.containsKey(request)) { return next.run(); }
        var bind = Objects.requireNonNull(binding.apply(context), "binding");
        var cookie = Cookies.of(request).get(cookieTemplate.name()).filter(token -> verifySigned(token, bind));
        if (!safe && !exempt.test(request)) {
            checkOrigin(request);
            if (cookie.isEmpty() || !same(cookie.get(), presented(request))) { throw new ForbiddenException("csrf_rejected"); }
        }
        var token = cookie.orElseGet(() -> sign(bind));
        issued.put(request, token);
        try {
            var response = next.run();
            if (cookie.isPresent() || response.headers().containsKey("Set-Cookie")) { return response; }
            return cookieTemplate.withValue(token).addTo(response);
        } finally {
            issued.remove(request);
        }
    }

    private String sign(String bind) {
        var nonce = new byte[16];
        random.nextBytes(nonce);
        var payload = ByteBuffer.allocate(24).putLong(clock.millis()).put(nonce, 0, 16).array();
        var encoded = ENCODER.encodeToString(payload);
        return encoded + "." + ENCODER.encodeToString(mac(bind, encoded));
    }

    private boolean verifySigned(String token, String bind) {
        if (token.length() != 32 + 1 + 43 || token.charAt(32) != '.') { return false; }
        var encoded = token.substring(0, 32);
        byte[] payload;
        byte[] tag;
        try {
            payload = Base64.getUrlDecoder().decode(encoded);
            tag = Base64.getUrlDecoder().decode(token.substring(33));
        } catch (IllegalArgumentException malformed) {
            return false;
        }
        if (payload.length != 24 || tag.length != 32 || !MessageDigest.isEqual(tag, mac(bind, encoded))) { return false; }
        long age = clock.millis() - ByteBuffer.wrap(payload).getLong();
        return age <= maxAge.toMillis() && age >= -60_000; // Tokens from the future beyond a minute of skew are forged or stale.
    }

    private byte[] mac(String bind, String encodedPayload) {
        try {
            var mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            mac.update("axiom-csrf-v1".getBytes(StandardCharsets.US_ASCII));
            mac.update((byte) 0);
            mac.update(bind.getBytes(StandardCharsets.UTF_8));
            mac.update((byte) 0);
            return mac.doFinal(encodedPayload.getBytes(StandardCharsets.US_ASCII));
        } catch (GeneralSecurityException impossible) {
            throw new IllegalStateException("HmacSHA256 is a required JDK algorithm", impossible);
        }
    }

    // ---- shared checks

    private String presented(Request request) {
        var value = request.header(headerName).orElse("");
        return value.length() > MAX_TOKEN_LENGTH ? "" : value;
    }

    private static boolean same(String expected, String presented) {
        if (presented.isEmpty()) { return false; }
        return MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8), presented.getBytes(StandardCharsets.UTF_8));
    }

    private String randomToken() {
        var bytes = new byte[32];
        random.nextBytes(bytes);
        return ENCODER.encodeToString(bytes);
    }

    private void checkOrigin(Request request) {
        if (fetchMetadata) {
            var site = request.header("Sec-Fetch-Site");
            if (site.isPresent() && !site.get().equals("same-origin") && !site.get().equals("none")
                    && !(allowSameSite && site.get().equals("same-site"))) {
                throw new ForbiddenException("csrf_rejected");
            }
        }
        var origin = request.header("Origin");
        if (origin.isEmpty()) { return; }
        var parsed = parseOrigin(origin.get());
        if (parsed == null) { throw new ForbiddenException("csrf_rejected"); }
        boolean allowed = allowedOrigins.isEmpty()
                ? request.header("Host").map(host -> host.toLowerCase(Locale.ROOT).equals(parsed.get(1))).orElse(false)
                : allowedOrigins.contains(origin.get());
        if (!allowed) { throw new ForbiddenException("csrf_rejected"); }
    }

    /** Returns [scheme, authority] of an exact origin, or null. */
    private static List<String> parseOrigin(String origin) {
        int separator = origin.indexOf("://");
        if (separator < 0 || origin.length() > 300) { return null; }
        var scheme = origin.substring(0, separator).toLowerCase(Locale.ROOT);
        var authority = origin.substring(separator + 3).toLowerCase(Locale.ROOT);
        if (!(scheme.equals("http") || scheme.equals("https")) || authority.isEmpty()) { return null; }
        for (int i = 0; i < authority.length(); i++) {
            char c = authority.charAt(i);
            boolean ok = (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '.' || c == '-' || c == ':' || c == '[' || c == ']';
            if (!ok) { return null; }
        }
        return List.of(scheme, authority);
    }

    @Override
    public String toString() {
        return "Csrf[" + (key == null ? "synchronizer" : "double-submit") + "]";
    }
}
