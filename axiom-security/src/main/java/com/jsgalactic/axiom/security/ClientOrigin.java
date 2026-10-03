package com.jsgalactic.axiom.security;

import java.net.InetAddress;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;

/**
 * Where a request came from, as decided by {@link TrustedProxies}: the client's IP address, the
 * scheme it used and, when a trusted proxy reported them, the host and port it addressed.
 * Immutable.
 *
 * @param address the client address: the transport peer, or the forwarded address when the peer
 *        is a trusted proxy
 * @param scheme {@code http} or {@code https}
 * @param forwarded true when the values come from headers set by trusted proxies rather than from
 *        the connection itself
 * @param host the host the client addressed, as validated by {@link TrustedProxies}: a DNS name
 *        in lower case, an IPv4 literal, or an IPv6 literal in brackets; empty when no trusted
 *        proxy reported a usable one
 * @param port the port the client addressed, 1 to 65535; empty when no trusted proxy reported a
 *        usable one (no default is inferred from the scheme)
 */
public record ClientOrigin(InetAddress address, String scheme, boolean forwarded, Optional<String> host,
        OptionalInt port) {
    /**
     * Validates the origin.
     *
     * @param address client address
     * @param scheme {@code http} or {@code https}
     * @param forwarded whether proxy headers supplied the values
     * @param host reported host, if any
     * @param port reported port, if any
     * @throws IllegalArgumentException for another scheme
     */
    public ClientOrigin {
        Objects.requireNonNull(address, "address");
        Objects.requireNonNull(scheme, "scheme");
        Objects.requireNonNull(host, "host");
        Objects.requireNonNull(port, "port");
        if (!scheme.equals("http") && !scheme.equals("https")) {
            throw new IllegalArgumentException("The scheme is http or https");
        }
        if (port.isPresent() && (port.getAsInt() < 1 || port.getAsInt() > 65535)) {
            throw new IllegalArgumentException("The port is 1 to 65535");
        }
    }

    /**
     * Creates an origin without a reported host or port.
     *
     * @param address client address
     * @param scheme {@code http} or {@code https}
     * @param forwarded whether proxy headers supplied the values
     * @throws IllegalArgumentException for another scheme
     */
    public ClientOrigin(InetAddress address, String scheme, boolean forwarded) {
        this(address, scheme, forwarded, Optional.empty(), OptionalInt.empty());
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
