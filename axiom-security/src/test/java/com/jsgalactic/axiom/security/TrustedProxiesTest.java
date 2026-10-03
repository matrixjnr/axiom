package com.jsgalactic.axiom.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import com.jsgalactic.axiom.http.Request;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.Test;

class TrustedProxiesTest {
    private static final TrustedProxies PROXIES = TrustedProxies.of("10.0.0.0/8", "2001:db8:1::/48", "192.0.2.1");

    private static Request from(String peer, String forwardedFor, String proto) throws Exception {
        var headers = new HashMap<String, String>();
        if (forwardedFor != null) { headers.put("X-Forwarded-For", forwardedFor); }
        if (proto != null) { headers.put("X-Forwarded-Proto", proto); }
        return Request.get("/").withHeaders(headers).withRemoteAddress(new InetSocketAddress(InetAddress.getByName(peer), 5000));
    }

    private static String client(Request request) {
        return PROXIES.resolve(request).orElseThrow().address().getHostAddress();
    }

    @Test
    void ignoresForwardingHeadersFromAnUntrustedPeer() throws Exception {
        var spoofed = from("198.51.100.20", "10.0.0.5, 203.0.113.1", "https");
        var origin = PROXIES.resolve(spoofed).orElseThrow();
        assertThat(origin.address().getHostAddress()).isEqualTo("198.51.100.20");
        assertThat(origin.scheme()).isEqualTo("http");
        assertThat(origin.forwarded()).isFalse();
        assertThat(TrustedProxies.none().resolve(from("10.0.0.1", "203.0.113.1", "https")).orElseThrow().address()
                .getHostAddress()).isEqualTo("10.0.0.1");
    }

    @Test
    void takesTheRightmostUntrustedHopFromATrustedPeer() throws Exception {
        // The client prepended a fake address; proxies appended the real one.
        var request = from("10.0.0.1", "1.2.3.4, 203.0.113.7, 10.0.0.9", "https");
        var origin = PROXIES.resolve(request).orElseThrow();
        assertThat(origin.address().getHostAddress()).isEqualTo("203.0.113.7");
        assertThat(origin.scheme()).isEqualTo("https");
        assertThat(origin.secure()).isTrue();
        assertThat(origin.forwarded()).isTrue();
    }

    @Test
    void usesTheLeftmostHopWhenEveryHopIsTrustedAndThePeerWithoutHeaders() throws Exception {
        assertThat(client(from("10.0.0.1", "10.1.1.1, 10.0.0.9", null))).isEqualTo("10.1.1.1");
        var direct = PROXIES.resolve(from("10.0.0.1", null, null)).orElseThrow();
        assertThat(direct.address().getHostAddress()).isEqualTo("10.0.0.1");
        assertThat(direct.forwarded()).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {"unknown", "localhost", "example.org", "203.0.113.007", "256.1.1.1", "1.2.3", "1.2.3.4.5",
            "fe80::1%eth0", "[2001:db8::1", "1.2.3.4:99999", "1.2.3.4:", "", "0x7f.0.0.1", "_hidden"})
    void stopsAtAnEntryThatIsNotAnAddressLiteralAndKeepsTheLastVouchedAddress(String entry) throws Exception {
        assertThat(client(from("10.0.0.1", "203.0.113.5, " + entry, null))).isEqualTo("10.0.0.1");
        assertThat(client(from("10.0.0.1", "203.0.113.5, " + entry + ", 10.0.0.9", null))).isEqualTo("10.0.0.9");
    }

    @Test
    void acceptsPortsBracketsAndIpv6() throws Exception {
        assertThat(client(from("10.0.0.1", "203.0.113.5:4711", null))).isEqualTo("203.0.113.5");
        assertThat(client(from("10.0.0.1", "[2001:db8::7]:443", null))).isEqualTo("2001:db8:0:0:0:0:0:7");
        assertThat(client(from("10.0.0.1", "2001:db8::7", null))).isEqualTo("2001:db8:0:0:0:0:0:7");
        assertThat(client(from("10.0.0.1", "::ffff:203.0.113.5", null))).isEqualTo("203.0.113.5");
        assertThat(client(from("2001:db8:1::5", "203.0.113.5, 2001:db8:1::9", null))).isEqualTo("203.0.113.5");
        assertThat(client(from("10.0.0.1", "[203.0.113.5]", null))).isEqualTo("10.0.0.1"); // IPv4 in brackets
    }

    @Test
    void boundsTheNumberOfHopsItWalks() throws Exception {
        var chain = new StringBuilder("203.0.113.5");
        for (int i = 0; i < 40; i++) { chain.append(", 10.0.0.").append(i + 1); }
        // 32 trusted hops are walked from the right; the client entry beyond them is never reached.
        assertThat(client(from("10.0.0.1", chain.toString(), null))).isEqualTo("10.0.0.9");
    }

    @ParameterizedTest
    @ValueSource(strings = {"ftp", "https, http", "HTTPS ,", "javascript", ""})
    void ignoresAnUnusableForwardedProto(String proto) throws Exception {
        assertThat(PROXIES.resolve(from("10.0.0.1", null, proto)).orElseThrow().scheme()).isEqualTo("http");
        assertThat(PROXIES.resolve(from("10.0.0.1", null, "HTTPS")).orElseThrow().scheme()).isEqualTo("https");
    }

    @Test
    void hasNoOriginForInMemoryRequests() {
        assertThat(PROXIES.resolve(Request.get("/").withHeaders(Map.of("X-Forwarded-For", "203.0.113.5")))).isEmpty();
    }

    @Test
    void matchesRangesByPrefix() throws Exception {
        assertThat(PROXIES.isTrusted(InetAddress.getByName("10.255.255.255"))).isTrue();
        assertThat(PROXIES.isTrusted(InetAddress.getByName("11.0.0.0"))).isFalse();
        assertThat(PROXIES.isTrusted(InetAddress.getByName("192.0.2.1"))).isTrue();
        assertThat(PROXIES.isTrusted(InetAddress.getByName("192.0.2.2"))).isFalse();
        assertThat(PROXIES.isTrusted(InetAddress.getByName("2001:db8:1:ffff::1"))).isTrue();
        assertThat(PROXIES.isTrusted(InetAddress.getByName("2001:db8:2::1"))).isFalse();
        assertThat(TrustedProxies.of("0.0.0.0/0").isTrusted(InetAddress.getByName("203.0.113.5"))).isTrue();
        assertThat(TrustedProxies.of("0.0.0.0/0").isTrusted(InetAddress.getByName("::1"))).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {"10.0.0.1/8", "10.0.0.0/33", "10.0.0.0/", "10.0.0.0/-1", "::/129", "localhost",
            "example.org/24", "10.0.0.0/8/8", "", "fe80::1%1"})
    void rejectsInvalidRanges(String range) {
        assertThatIllegalArgumentException().isThrownBy(() -> TrustedProxies.of(range));
    }
}
