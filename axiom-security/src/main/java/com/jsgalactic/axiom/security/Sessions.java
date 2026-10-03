package com.jsgalactic.axiom.security;

import com.jsgalactic.axiom.context.Context;
import com.jsgalactic.axiom.context.Middleware;
import com.jsgalactic.axiom.http.Request;
import com.jsgalactic.axiom.http.Response;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Base64;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Server-side sessions as middleware: a cookie carries a random secret identifier, a
 * {@link SessionStore} holds everything else.
 *
 * <pre>{@code
 * var sessions = Sessions.builder(InMemorySessionStore.builder().build()).build();
 * var security = Security.of(sessions.authenticator());
 * app.use(sessions);                               // loads the session, issues the cookie
 * app.use(security.authenticate());                // identity from the session
 * app.post("/login", ctx -> {
 *     sessions.session(ctx).authenticate(verifiedIdentity);   // new identifier at login
 *     return ctx.noContent();
 * });
 * app.post("/logout", ctx -> { sessions.session(ctx).invalidate(); return ctx.noContent(); });
 * }</pre>
 *
 * <p><b>Identifiers.</b> 256 random bits from a {@link SecureRandom}, base64url without padding (43
 * characters). The cookie value must have exactly that shape and name a live session in the store,
 * otherwise it is ignored: the server never adopts an identifier the client chose, which defeats
 * session fixation. Identifiers rotate at {@link Session#authenticate} and {@link Session#rotate}.
 * They are never logged, exposed through the API, or placed in problem responses.
 *
 * <p><b>Cookie.</b> {@code Secure}, {@code HttpOnly}, {@code SameSite=Lax}, {@code Path=/}, host-only,
 * and named {@code __Host-sid} by default (see {@link SetCookie}); the cookie is a browser-session
 * cookie unless {@link Builder#maxAge} is set. The name defaults to {@code __Secure-sid} when a path or
 * domain is configured and to {@code sid} when {@code Secure} is turned off for local development.
 * Request cookies are parsed with {@link Cookies}: a malformed or oversized {@code Cookie} header,
 * or the session cookie sent twice, means "no session".
 *
 * <p><b>Lifecycle.</b> The middleware looks the session up once per request, makes it available
 * through {@link #session(Context)} and {@link SessionAuthenticator}, and after the handler returns
 * applies the recorded writes and sets the cookie when the identifier is new or changed (or expires it
 * after {@link Session#invalidate}). Nothing is stored for requests that do not write. If the handler
 * throws, no writes are applied. A response carries one {@code Set-Cookie}: when the session needs the
 * cookie and the response already has one, the request fails with {@link IllegalStateException}
 * (before the store is modified) rather than losing the login silently.
 *
 * <p>Place it before the {@link Security} policies that use its authenticator. Thread-safe and
 * shared by all requests; the store owns the session data.
 */
public final class Sessions implements Middleware {
    private static final int ID_BYTES = 32;
    private static final int ID_LENGTH = 43;
    private static final String ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_";

    private final SessionStore store;
    private final SecureRandom random;
    private final SetCookie template;
    private final int maxCookieHeader;
    private final Map<Request, Session> active = Collections.synchronizedMap(new IdentityHashMap<>());

    private Sessions(Builder builder) {
        store = builder.store;
        random = builder.random;
        maxCookieHeader = builder.maxCookieHeader;
        boolean hostOnly = builder.domain == null && builder.path.equals("/");
        var name = builder.name != null ? builder.name
                : !builder.secure ? "sid" : hostOnly ? "__Host-sid" : "__Secure-sid";
        template = SetCookie.of(name, "x".repeat(ID_LENGTH)).secure(builder.secure).sameSite(builder.sameSite)
                .path(builder.path).domain(builder.domain).maxAge(builder.maxAge);
    }

    /**
     * Starts a configuration with the secure defaults.
     *
     * @param store where session data lives
     * @return a builder
     */
    public static Builder builder(SessionStore store) {
        return new Builder(Objects.requireNonNull(store, "store"));
    }

    /** Builds {@link Sessions}; used during configuration, not thread-safe. */
    public static final class Builder {
        private final SessionStore store;
        private String name;
        private boolean secure = true;
        private SetCookie.SameSite sameSite = SetCookie.SameSite.LAX;
        private String path = "/";
        private String domain;
        private Duration maxAge;
        private SecureRandom random = new SecureRandom();
        private int maxCookieHeader = Cookies.DEFAULT_MAX_LENGTH;

        private Builder(SessionStore store) { this.store = store; }

        /**
         * Sets the cookie name. A {@code __Host-} or {@code __Secure-} prefix is checked against the
         * other settings when built.
         *
         * @param name cookie name, an HTTP token
         * @return this builder
         */
        public Builder cookieName(String name) {
            this.name = Objects.requireNonNull(name, "name");
            return this;
        }

        /**
         * Sets the {@code Secure} attribute (on by default). Browsers drop Secure cookies received
         * over plain HTTP, so turn it off only for local development.
         *
         * @param secure whether the cookie is Secure
         * @return this builder
         */
        public Builder secure(boolean secure) {
            this.secure = secure;
            return this;
        }

        /**
         * Sets {@code SameSite} (Lax by default). {@code NONE} requires {@code Secure}.
         *
         * @param sameSite the attribute
         * @return this builder
         */
        public Builder sameSite(SetCookie.SameSite sameSite) {
            this.sameSite = Objects.requireNonNull(sameSite, "sameSite");
            return this;
        }

        /**
         * Sets the cookie path (default {@code /}).
         *
         * @param path path starting with {@code /}
         * @return this builder
         */
        public Builder path(String path) {
            this.path = Objects.requireNonNull(path, "path");
            return this;
        }

        /**
         * Sets a cookie domain, which sends the cookie to every sub-domain; the default is host-only.
         *
         * @param domain a lower-case DNS name without a leading dot
         * @return this builder
         */
        public Builder domain(String domain) {
            this.domain = Objects.requireNonNull(domain, "domain");
            return this;
        }

        /**
         * Makes the cookie persistent for this long (default: a browser-session cookie). The store's
         * timeouts still decide when the session ends.
         *
         * @param maxAge 0 to 400 days
         * @return this builder
         */
        public Builder maxAge(Duration maxAge) {
            this.maxAge = Objects.requireNonNull(maxAge, "maxAge");
            return this;
        }

        /**
         * Sets the source of identifiers; for tests that need determinism.
         *
         * @param random a cryptographically strong generator
         * @return this builder
         */
        public Builder random(SecureRandom random) {
            this.random = Objects.requireNonNull(random, "random");
            return this;
        }

        /**
         * Sets the longest {@code Cookie} header read (default 8,192 characters).
         *
         * @param maxCookieHeader 64 to 65,536
         * @return this builder
         */
        public Builder maxCookieHeader(int maxCookieHeader) {
            if (maxCookieHeader < 64 || maxCookieHeader > 65_536) { throw new IllegalArgumentException("maxCookieHeader must be between 64 and 65,536"); }
            this.maxCookieHeader = maxCookieHeader;
            return this;
        }

        /**
         * Builds the sessions.
         *
         * @return the middleware
         * @throws IllegalArgumentException if the cookie settings are inconsistent
         */
        public Sessions build() {
            return new Sessions(this);
        }
    }

    /**
     * Returns the session of the request the context belongs to.
     *
     * @param context the context passed to a handler or middleware that runs inside this middleware
     * @return the request's session; it is created in the store on its first write
     * @throws IllegalStateException if this middleware is not active for the request
     */
    public Session session(Context context) {
        var session = active.get(Objects.requireNonNull(context, "context").request());
        if (session == null) { throw new IllegalStateException("The sessions middleware is not registered before this handler"); }
        return session;
    }

    /**
     * Returns an authenticator that yields the identity stored in the session, for {@link Security#of}.
     *
     * @return the authenticator, with the default challenge
     */
    public SessionAuthenticator authenticator() {
        return new SessionAuthenticator(this, SessionAuthenticator.DEFAULT_CHALLENGE);
    }

    /**
     * Returns an authenticator with another {@code WWW-Authenticate} challenge for 401 answers.
     *
     * @param challenge a valid header value, for example {@code Cookie realm="app"}
     * @return the authenticator
     */
    public SessionAuthenticator authenticator(String challenge) {
        return new SessionAuthenticator(this, challenge);
    }

    /** The session of a request: the one the middleware loaded, or a read-only lookup without it. */
    Optional<com.jsgalactic.axiom.context.SecurityIdentity> identityOf(Request request) {
        var loaded = active.get(request);
        if (loaded != null) { return loaded.identity(); }
        return lookup(request).flatMap(state -> state.identity());
    }

    private Optional<SessionState> lookup(Request request) {
        return cookieId(request).flatMap(store::find);
    }

    private Optional<String> cookieId(Request request) {
        return Cookies.of(request, maxCookieHeader).get(template.name()).filter(Sessions::validId);
    }

    @Override
    public Response handle(Context context, Next next) throws Exception {
        var request = Objects.requireNonNull(context, "context").request();
        if (active.containsKey(request)) { return next.run(); }
        var cookie = cookieId(request);
        var state = cookie.flatMap(store::find);
        var session = new Session(state.isPresent() ? cookie.get() : null, state.orElse(null));
        active.put(request, session);
        try {
            return commit(session, next.run(), cookie.isPresent());
        } finally {
            active.remove(request);
        }
    }

    private Response commit(Session session, Response response, boolean cookieSent) {
        if (session.invalidated()) {
            if (session.id() != null) { store.remove(session.id()); }
            return session.id() != null || cookieSent ? addCookie(response, template.expire()) : response;
        }
        if (!session.changed()) { return response; }
        boolean needsCookie = session.isNew() || session.rotates();
        if (needsCookie && response.headers().containsKey("Set-Cookie")) {
            throw new IllegalStateException("The response already sets a cookie; a response carries one Set-Cookie");
        }
        var newId = persist(session);
        return newId == null ? response : addCookie(response, template.withValue(newId));
    }

    /** Stores the writes; returns the identifier to send, or null when the cookie stays as it is. */
    private String persist(Session session) {
        var writes = session.writes();
        var id = session.id();
        if (id != null) {
            var target = session.rotates() ? newId() : id;
            boolean stored = session.rotates() ? store.rename(id, target) && store.update(target, writes) : store.update(id, writes);
            if (stored) { return session.rotates() ? target : null; }
            // The session vanished meanwhile (expired, logged out elsewhere). A sign-in starts a new one;
            // other writes are dropped rather than resurrecting a session that was ended on purpose.
            if (!session.signsIn()) { return null; }
        }
        for (int attempt = 0; attempt < 3; attempt++) {
            var fresh = newId();
            if (store.create(fresh, writes.apply(SessionState.empty()))) { return fresh; }
        }
        throw new IllegalStateException("Could not allocate a session identifier");
    }

    private Response addCookie(Response response, SetCookie cookie) {
        return cookie.addTo(response);
    }

    private String newId() {
        var bytes = new byte[ID_BYTES];
        random.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    /** Whether the text has the exact, canonical shape of an identifier. */
    static boolean validId(String text) {
        if (text.length() != ID_LENGTH) { return false; }
        for (int i = 0; i < ID_LENGTH; i++) {
            if (ALPHABET.indexOf(text.charAt(i)) < 0) { return false; }
        }
        return ALPHABET.indexOf(text.charAt(ID_LENGTH - 1)) % 4 == 0; // 256 bits leave two unused bits.
    }

    @Override
    public String toString() {
        return "Sessions[cookie=" + template.name() + "]";
    }
}
