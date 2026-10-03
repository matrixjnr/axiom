package com.jsgalactic.axiom.security;

import com.jsgalactic.axiom.context.SecurityIdentity;
import com.jsgalactic.axiom.error.UnauthorizedException;
import com.jsgalactic.axiom.http.Request;
import java.util.Optional;

/**
 * Turns the credentials a request carries into a verified {@link SecurityIdentity}. Implemented
 * once per credential scheme, for example a bearer token or an API key, and used through
 * {@link Security}.
 *
 * <p>Outcomes:
 * <ul>
 * <li>The request carries no credentials for this scheme: return empty. The request continues
 * anonymously; a policy that needs an identity then answers 401 with {@link #challenge()}.</li>
 * <li>The credentials are valid: return the identity.</li>
 * <li>The credentials are present but malformed, expired, forged or otherwise invalid: throw an
 * {@link UnauthorizedException} carrying a challenge, which is answered 401 as
 * {@code application/problem+json} with {@code WWW-Authenticate}. Never put the credential, or
 * anything derived from it, into the exception's code or challenge; attach diagnostic detail as a
 * cause with {@code initCause}, which reaches logs only.</li>
 * </ul>
 * Any other exception fails the request like a handler exception (500 over HTTP).
 *
 * <p><b>Thread safety and lifecycle.</b> One instance serves every request concurrently, on the
 * request's thread, under its deadline; implementations must be thread-safe and should not block
 * for long. The application owns the instance through the middleware it is registered with.
 */
public interface Authenticator {
    /**
     * Verifies the request's credentials.
     *
     * @param request the immutable request; headers are untrusted client input
     * @return the verified identity, or empty when the request carries no credentials for this
     *         scheme
     * @throws UnauthorizedException when credentials are present but invalid
     */
    Optional<SecurityIdentity> authenticate(Request request);

    /**
     * Returns the {@code WWW-Authenticate} challenge sent when a policy requires an identity and
     * the request carries no credentials, for example {@code Bearer realm="api"}. A constant chosen
     * by the application, never a value taken from a request.
     *
     * @return challenge: an auth scheme, optionally followed by a space and parameters, in visible
     *         ASCII
     */
    String challenge();
}
