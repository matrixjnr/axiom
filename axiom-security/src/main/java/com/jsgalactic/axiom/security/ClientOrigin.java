package com.jsgalactic.axiom.security;

import java.net.InetAddress;
import java.util.Objects;

/**
 * Where a request came from, as decided by {@link TrustedProxies}: the client's IP address and
 * the scheme it used. Immutable.
 *
 * @param address the client address: the transport peer, or the forwarded address when the peer
 *        is a trusted proxy
 * @param scheme {@code http} or {@code https}
 * @param forwarded true when the values come from headers set by trusted proxies rather than from
 *        the connection itself
 */
public record ClientOrigin(InetAddress address, String scheme, boolean forwarded) {
    /**
     * Validates the origin.
     *
     * @param address client address
     * @param scheme {@code http} or {@code https}
     * @param forwarded whether proxy headers supplied the values
     * @throws IllegalArgumentException for another scheme
     */
    public ClientOrigin {
        Objects.requireNonNull(address, "address");
        Objects.requireNonNull(scheme, "scheme");
        if (!scheme.equals("http") && !scheme.equals("https")) {
            throw new IllegalArgumentException("The scheme is http or https");
        }
    }

    /**
     * Reports whether the client used HTTPS.
     *
     * @return true for {@code https}
     */
    public boolean secure() {
        return scheme.equals("https");
    }
}
