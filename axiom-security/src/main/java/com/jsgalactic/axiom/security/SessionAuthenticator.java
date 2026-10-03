package com.jsgalactic.axiom.security;

import com.jsgalactic.axiom.context.SecurityIdentity;
import com.jsgalactic.axiom.error.UnauthorizedException;
import com.jsgalactic.axiom.http.Request;
import java.util.Objects;
import java.util.Optional;

/**
 * An {@link Authenticator} that reads the identity from the request's session, so the policies of
 * {@link Security} work for cookie-authenticated browsers exactly as for bearer tokens.
 *
 * <pre>{@code
 * var security = Security.of(sessions.authenticator());
 * app.use(sessions);
 * app.get("/account", handler, security.authenticated());   // 401 without a signed-in session
 * }</pre>
 *
 * <p>A request without a valid session is anonymous (empty), never a 401 by itself: an unknown,
 * expired, forged or malformed cookie is indistinguishable from none. Only the policy that
 * requires an identity answers 401, with this authenticator's challenge. Cookies carry no standard
 * authentication scheme, so the challenge is informational; set one with
 * {@link Sessions#authenticator(String)}. Inside {@link Sessions} the session already loaded for
 * the request is used; outside it the store is consulted read-only (the lookup counts as an access).
 *
 * <p>Thread-safe; created by {@link Sessions}.
 */
public final class SessionAuthenticator implements Authenticator {
    static final String DEFAULT_CHALLENGE = "Cookie realm=\"session\"";

    private final Sessions sessions;
    private final String challenge;

    SessionAuthenticator(Sessions sessions, String challenge) {
        this.sessions = sessions;
        this.challenge = Objects.requireNonNull(challenge, "challenge");
        new UnauthorizedException(challenge); // Validated once, at configuration time.
    }

    @Override
    public Optional<SecurityIdentity> authenticate(Request request) {
        return sessions.identityOf(Objects.requireNonNull(request, "request"));
    }

    @Override
    public String challenge() { return challenge; }
}
