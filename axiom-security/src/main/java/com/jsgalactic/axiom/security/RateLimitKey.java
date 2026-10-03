package com.jsgalactic.axiom.security;

import com.jsgalactic.axiom.context.Context;
import java.util.Objects;
import java.util.Optional;

/**
 * Chooses the key a request is counted under by {@link RateLimit}: one budget per distinct key.
 *
 * <p>A key is text of 1 to {@value RateLimit#MAX_KEY_LENGTH} characters. It is built from facts the
 * server established (the transport peer, a trusted proxy's view, the verified identity), never from
 * a request header an attacker chooses freely; otherwise a client would pick a fresh key per request
 * and never be limited. {@link RateLimit} bounds how many keys it tracks, so a key function that is
 * flooded with new values evicts old entries instead of growing memory.
 *
 * <p>An empty or oversized result means the request has no usable key. {@link RateLimit} then counts
 * it under {@link #ANONYMOUS}, one shared budget, so keyless requests are still limited.
 *
 * <p>Implementations are called concurrently and must be thread-safe and fast.
 */
@FunctionalInterface
public interface RateLimitKey {
    /** The key of requests whose key function produced none. */
    String ANONYMOUS = "anonymous";

    /**
     * Returns the key of the request.
     *
     * @param context request context
     * @return the key, or empty when this function has none
     */
    Optional<String> key(Context context);

    /**
     * Keys by the transport peer address ({@code Request.remoteAddress()}), ignoring every header.
     * This is the default of {@link RateLimit}: it cannot be spoofed, but behind a reverse proxy
     * every client shares the proxy's address; use {@link #clientAddress(TrustedProxies)} there.
     *
     * @return the peer-address key function
     */
    static RateLimitKey peerAddress() {
        return context -> Optional.ofNullable(context.request().remoteAddress())
                .map(address -> address.getAddress().getHostAddress());
    }

    /**
     * Keys by the client address resolved through trusted proxies: forwarding headers are believed
     * only from configured proxies, as in {@link TrustedProxies#resolve}.
     *
     * @param proxies the trusted proxy configuration
     * @return the client-address key function
     */
    static RateLimitKey clientAddress(TrustedProxies proxies) {
        Objects.requireNonNull(proxies, "proxies");
        return context -> proxies.resolve(context.request()).map(origin -> origin.address().getHostAddress());
    }

    /**
     * Keys by the authenticated principal, falling back to another function for anonymous
     * requests. Place the authenticating middleware before the limiter.
     *
     * @param anonymous used when the request has no identity, typically {@link #peerAddress()}
     * @return the principal key function
     */
    static RateLimitKey principal(RateLimitKey anonymous) {
        Objects.requireNonNull(anonymous, "anonymous");
        return context -> context.identity().map(identity -> "user:" + identity.principal())
                .or(() -> anonymous.key(context).map(address -> "addr:" + address));
    }
}
