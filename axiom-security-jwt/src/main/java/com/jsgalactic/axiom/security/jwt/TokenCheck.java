package com.jsgalactic.axiom.security.jwt;

/**
 * An application check that runs after a token's signature, time claims, issuer, audience and
 * subject have been verified, to reject a token that is cryptographically valid but must not be
 * accepted: revoked, replayed (same {@code jti}), issued to a suspended account, and so on.
 *
 * <pre>{@code
 * var seen = new ConcurrentHashMap<String, Instant>();           // pruned by a scheduled task
 * JwtAuthenticator.builder()...
 *         .tokenCheck(claims -> claims.id().map(id -> seen.putIfAbsent(id, claims.expiresAt()) == null).orElse(false))
 *         .tokenCheck(claims -> !revokedSubjects.contains(claims.subject()))
 *         .build();
 * }</pre>
 *
 * <p>Returning {@code false} makes the authenticator answer 401 {@code invalid_token}, like any
 * other invalid token, without telling the client why. An {@link IllegalArgumentException}, for
 * example from a {@link JwtClaims} accessor on a claim of the wrong type, counts the same way.
 * Any other exception propagates (a 500 over HTTP): a check that cannot decide, such as an
 * unreachable revocation store, must not let the token through, and the operator should see it.
 *
 * <p>Implementations are shared by all requests and must be thread-safe. Checks run in
 * registration order and stop at the first rejection, so a check with side effects (recording a
 * {@code jti}) should be registered last. Each request runs its checks on its own thread; blocking
 * calls are acceptable but count against the request deadline.
 */
@FunctionalInterface
public interface TokenCheck {
    /**
     * Decides whether a verified token is acceptable.
     *
     * @param claims the verified claims
     * @return true to accept the token, false to reject it with 401 {@code invalid_token}
     */
    boolean accept(JwtClaims claims);
}
