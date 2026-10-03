package com.jsgalactic.axiom.security;

import com.jsgalactic.axiom.http.Request;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;

/**
 * Decides a request's client address and scheme, believing {@code X-Forwarded-For} and
 * {@code X-Forwarded-Proto} only when they were set by configured, trusted proxies.
 *
 * <pre>{@code
 * static final TrustedProxies PROXIES = TrustedProxies.of("10.0.0.0/8", "::1");
 * var origin = PROXIES.resolve(ctx.request());    // empty only for in-memory requests
 * origin.map(ClientOrigin::address)...
 * }</pre>
 *
 * <p><b>Rules.</b> Forwarding headers are client input and anyone can send them, so:
 * <ul>
 * <li>When the transport peer ({@link Request#remoteAddress()}) is not a trusted proxy, the client
 * is the peer and the scheme is {@code http} (the listener has no TLS); forwarding headers are
 * ignored entirely.</li>
 * <li>When the peer is trusted, {@code X-Forwarded-For} is read from right to left, the order in
 * which proxies appended to it. Entries that are trusted proxies are skipped; the first untrusted
 * entry is the client. If every entry is trusted, the leftmost one is the client. The walk stops at
 * the first entry that is not an IP address literal (for example {@code unknown} or a host name),
 * and after 32 entries: the client is then the last address that was vouched for by a trusted
 * proxy. Addresses are never resolved through DNS. Entries may carry a port
 * ({@code 192.0.2.1:4711}, {@code [2001:db8::1]:4711}), which is ignored.</li>
 * <li>When the peer is trusted, {@code X-Forwarded-Proto} supplies the scheme if it is exactly one
 * value, {@code http} or {@code https} (any case); otherwise the scheme is {@code http}.</li>
 * <li>{@code X-Forwarded-Host} supplies the host (and its port, if any) when it is exactly one
 * value that is a DNS name, an IPv4 literal or a bracketed IPv6 literal; {@code X-Forwarded-Port}
 * supplies the port, 1 to 65535, when the host carries none. Lists, names that are not valid host
 * names (spaces, paths, credentials, {@code @}) and out-of-range ports are ignored.</li>
 * </ul>
 * <p><b>Header family.</b> By default the {@code X-Forwarded-*} family is read. {@link
 * #reading(ForwardedHeaders)} selects the RFC 7239 {@code Forwarded} header instead; exactly one
 * family is ever read, so mixing a spoofed header of one with a genuine header of the other cannot
 * happen. {@code Forwarded} is parsed strictly (tokens and quoted strings; an element with a
 * repeated or malformed parameter is invalid; no whitespace around {@code =} or {@code ;}), then
 * walked right to left with the same trust rule and the same 32-hop bound as above. The {@code for}
 * value must be an IPv4 literal or a quoted, bracketed IPv6 literal, with an optional port;
 * obfuscated identifiers ({@code _hidden}), {@code unknown} and names stop the walk. {@code proto}
 * and {@code host} are taken from the element that vouches for the client address (the one whose
 * {@code for} became the client), because that proxy describes what the client sent. A header
 * with an unterminated quoted string is ignored as a whole.
 *
 * <p>Trust only proxies that overwrite or append to these headers; a trusted proxy that passes a
 * client's forwarding headers through unchanged lets the client choose its address and host. The
 * host and port are client-influenced input as soon as the proxy does not set them itself: never
 * use them for security decisions such as password-reset links without an allow-list.
 *
 * <p>Immutable and thread-safe; configure once and share.
 */
public final class TrustedProxies {
    private static final int MAX_HOPS = 32;
    private static final TrustedProxies NONE = new TrustedProxies(List.of(), ForwardedHeaders.X_FORWARDED);

    private final List<Range> ranges;
    private final ForwardedHeaders family;

    private TrustedProxies(List<Range> ranges, ForwardedHeaders family) {
        this.ranges = List.copyOf(ranges);
        this.family = family;
    }

    /**
     * Trusts no proxy: the client is always the transport peer.
     *
     * @return the empty configuration
     */
    public static TrustedProxies none() {
        return NONE;
    }

    /**
     * Trusts proxies in the given address ranges.
     *
     * @param ranges IP address literals ({@code 10.0.0.7}, {@code ::1}) or CIDR ranges
     *        ({@code 10.0.0.0/8}, {@code fd00::/8}) whose host bits are zero
     * @return the configuration
     * @throws IllegalArgumentException for a range that is not an IP literal or a CIDR range
     */
    public static TrustedProxies of(String... ranges) {
        var parsed = new ArrayList<Range>(ranges.length);
        for (var range : ranges) { parsed.add(Range.parse(Objects.requireNonNull(range, "range"))); }
        return new TrustedProxies(parsed, ForwardedHeaders.X_FORWARDED);
    }

    /**
     * Returns a copy that reads the given header family instead of the default
     * {@link ForwardedHeaders#X_FORWARDED}. Only that family is read; the other is ignored.
     *
     * @param headers the family the trusted proxies set
     * @return a copy with the same trusted ranges
     */
    public TrustedProxies reading(ForwardedHeaders headers) {
        return new TrustedProxies(ranges, Objects.requireNonNull(headers, "headers"));
    }

    /**
     * Reports whether an address is a trusted proxy.
     *
     * @param address address to check
     * @return true if it lies in a configured range
     */
    public boolean isTrusted(InetAddress address) {
        var bytes = Objects.requireNonNull(address, "address").getAddress();
        for (var range : ranges) {
            if (range.contains(bytes)) { return true; }
        }
        return false;
    }

    /**
     * Decides the request's client address and scheme by the rules above.
     *
     * @param request the request
     * @return the origin, or empty when the request has no transport peer (in-memory requests)
     */
    public Optional<ClientOrigin> resolve(Request request) {
        var peer = Objects.requireNonNull(request, "request").remoteAddress();
        if (peer == null) { return Optional.empty(); }
        var client = peer.getAddress();
        if (!isTrusted(client)) { return Optional.of(new ClientOrigin(client, "http", false)); }
        return Optional.of(family == ForwardedHeaders.FORWARDED ? fromForwarded(request, client)
                : fromXForwarded(request, client));
    }

    private ClientOrigin fromXForwarded(Request request, InetAddress peer) {
        var client = peer;
        boolean forwarded = false;
        var chain = request.header("X-Forwarded-For").orElse("");
        int end = chain.length();
        for (int hops = 0; hops < MAX_HOPS && end > 0; hops++) {
            int comma = chain.lastIndexOf(',', end - 1);
            var hop = parseHop(chain.substring(comma + 1, end).trim());
            if (hop == null) { break; }
            client = hop;
            forwarded = true;
            if (!isTrusted(hop)) { break; }
            end = comma < 0 ? 0 : comma;
        }
        var proto = request.header("X-Forwarded-Proto").orElse(null);
        var scheme = scheme(proto);
        forwarded |= proto != null && proto.trim().equalsIgnoreCase(scheme);
        // One value only: a list means several proxies appended and nothing says which one to believe.
        var hostHeader = request.header("X-Forwarded-Host").orElse(null);
        var authority = single(hostHeader) ? Forwarded.authority(hostHeader.trim()) : null;
        int port = authority == null ? 0 : authority.port();
        if (port == 0) {
            var text = request.header("X-Forwarded-Port").map(String::trim).orElse(null);
            port = single(text) ? Forwarded.port(text) : 0;
        }
        return origin(client, scheme, forwarded, authority, port);
    }

    private ClientOrigin fromForwarded(Request request, InetAddress peer) {
        var client = peer;
        Map<String, String> vouching = null;
        var header = request.header("Forwarded").orElse(null);
        var elements = header == null ? null : Forwarded.parse(header);
        if (elements != null) {
            for (int i = elements.size() - 1, hops = 0; i >= 0 && hops < MAX_HOPS; i--, hops++) {
                var element = elements.get(i);
                var hop = element == null ? null : Forwarded.node(element.get("for"));
                if (hop == null) { break; }
                client = hop;
                vouching = element;
                if (!isTrusted(hop)) { break; }
            }
        }
        if (vouching == null) { return new ClientOrigin(client, "http", false); }
        // Proto and host come from the element of the proxy that saw the client: the one vouching for it.
        var authority = Forwarded.authority(vouching.get("host"));
        return origin(client, scheme(vouching.get("proto")), true, authority, authority == null ? 0 : authority.port());
    }

    private static ClientOrigin origin(InetAddress client, String scheme, boolean forwarded, Forwarded.Authority authority,
            int port) {
        boolean extra = !scheme.equals("http") || authority != null || port != 0;
        return new ClientOrigin(client, scheme, forwarded || extra,
                Optional.ofNullable(authority == null ? null : authority.host()),
                port == 0 ? OptionalInt.empty() : OptionalInt.of(port));
    }

    private static String scheme(String value) {
        var proto = value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
        return proto.equals("http") || proto.equals("https") ? proto : "http";
    }

    private static boolean single(String value) {
        return value != null && value.indexOf(',') < 0;
    }

    /** Parses one X-Forwarded-For entry: an IP literal with an optional port, or null. */
    private static InetAddress parseHop(String hop) {
        if (hop.startsWith("[")) {
            int close = hop.indexOf(']');
            if (close < 0 || (close + 1 < hop.length() && !port(hop, close + 1))) { return null; }
            var address = literal(hop.substring(1, close));
            return address instanceof Inet6Address ? address : null;
        }
        int colon = hop.indexOf(':');
        if (colon >= 0 && colon == hop.lastIndexOf(':')) { // IPv4 with a port; IPv6 has two colons or more.
            return port(hop, colon) ? literal(hop.substring(0, colon)) : null;
        }
        return literal(hop);
    }

    private static boolean port(String hop, int colon) {
        int length = hop.length() - colon - 1;
        if (hop.charAt(colon) != ':' || length < 1 || length > 5) { return false; }
        for (int i = colon + 1; i < hop.length(); i++) {
            if (hop.charAt(i) < '0' || hop.charAt(i) > '9') { return false; }
        }
        return Integer.parseInt(hop, colon + 1, hop.length(), 10) <= 65535;
    }

    /**
     * Parses an IPv4 dotted-quad or an IPv6 literal without any name lookup; null otherwise.
     * IPv4-mapped IPv6 literals become IPv4 addresses. Zone identifiers are rejected.
     */
    static InetAddress literal(String text) {
        if (text.isEmpty() || text.length() > 45) { return null; }
        if (text.indexOf(':') < 0) {
            var bytes = ipv4(text);
            try {
                return bytes == null ? null : InetAddress.getByAddress(bytes);
            } catch (UnknownHostException impossible) {
                return null;
            }
        }
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (Character.digit(c, 16) < 0 && c != ':' && c != '.') { return null; }
        }
        // Only hexadecimal digits, colons and dots, with a colon: the JDK parses this as an IPv6
        // literal and never performs a lookup; an invalid literal throws.
        try {
            return InetAddress.getByName(text);
        } catch (UnknownHostException invalid) {
            return null;
        }
    }

    private static byte[] ipv4(String text) {
        var bytes = new byte[4];
        int part = 0;
        int start = 0;
        for (int i = 0; i <= text.length(); i++) {
            if (i == text.length() || text.charAt(i) == '.') {
                int length = i - start;
                if (part > 3 || length < 1 || length > 3 || (length > 1 && text.charAt(start) == '0')) { return null; }
                int value = 0;
                for (int j = start; j < i; j++) {
                    char c = text.charAt(j);
                    if (c < '0' || c > '9') { return null; }
                    value = value * 10 + (c - '0');
                }
                if (value > 255) { return null; }
                bytes[part++] = (byte) value;
                start = i + 1;
            }
        }
        return part == 4 ? bytes : null;
    }

    /** An address range: the network bytes and a prefix length. */
    private record Range(byte[] network, int prefix) {
        static Range parse(String text) {
            int slash = text.indexOf('/');
            var address = literal(slash < 0 ? text : text.substring(0, slash));
            if (address == null) {
                throw new IllegalArgumentException("A trusted proxy is an IP address literal or a CIDR range");
            }
            var bytes = address.getAddress();
            int bits = bytes.length * 8;
            int prefix = bits;
            if (slash >= 0) {
                var digits = text.substring(slash + 1);
                if (digits.isEmpty() || digits.length() > 3 || !digits.chars().allMatch(c -> c >= '0' && c <= '9')
                        || Integer.parseInt(digits) > bits) {
                    throw new IllegalArgumentException("A CIDR prefix is 0 to " + bits + " bits");
                }
                prefix = Integer.parseInt(digits);
            }
            var range = new Range(bytes, prefix);
            for (int bit = prefix; bit < bits; bit++) {
                if (range.bit(bytes, bit)) {
                    throw new IllegalArgumentException("A CIDR range must not set bits after its prefix");
                }
            }
            return range;
        }

        boolean contains(byte[] address) {
            if (address.length != network.length) { return false; }
            for (int bit = 0; bit < prefix; bit++) {
                if (bit(address, bit) != bit(network, bit)) { return false; }
            }
            return true;
        }

        private boolean bit(byte[] bytes, int index) {
            return (bytes[index / 8] & (0x80 >>> (index % 8))) != 0;
        }
    }
}
