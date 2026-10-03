package com.jsgalactic.axiom.security;

import static org.assertj.core.api.Assertions.assertThat;

import com.jsgalactic.axiom.http.Request;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.HashMap;
import java.util.Optional;
import java.util.OptionalInt;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** RFC 7239 Forwarded, X-Forwarded-Host and X-Forwarded-Port, with spoofing cases for each header. */
class ForwardedHeadersTest {
    private static final TrustedProxies X = TrustedProxies.of("10.0.0.0/8", "2001:db8:1::/48");
    private static final TrustedProxies F = X.reading(ForwardedHeaders.FORWARDED);

    private static Request from(String peer, String... headers) throws Exception {
        var map = new HashMap<String, String>();
        for (int i = 0; i < headers.length; i += 2) { map.put(headers[i], headers[i + 1]); }
        return Request.get("/").withHeaders(map).withRemoteAddress(new InetSocketAddress(InetAddress.getByName(peer), 5000));
    }

    private static ClientOrigin resolve(TrustedProxies proxies, Request request) {
        return proxies.resolve(request).orElseThrow();
    }

    private static String client(TrustedProxies proxies, String forwarded) throws Exception {
        return resolve(proxies, from("10.0.0.1", "Forwarded", forwarded)).address().getHostAddress();
    }

    // X-Forwarded-Host and X-Forwarded-Port

    @Test
    void readsHostAndPortFromATrustedPeer() throws Exception {
        var origin = resolve(X, from("10.0.0.1", "X-Forwarded-Host", "API.Example.com:8443"));
        assertThat(origin.host()).contains("api.example.com");
        assertThat(origin.port()).hasValue(8443);
        assertThat(origin.forwarded()).isTrue();
        var split = resolve(X, from("10.0.0.1", "X-Forwarded-Host", "api.example.com", "X-Forwarded-Port", "8080"));
        assertThat(split.host()).contains("api.example.com");
        assertThat(split.port()).hasValue(8080);
        // A port inside the host wins over X-Forwarded-Port: one source of truth.
        var both = resolve(X, from("10.0.0.1", "X-Forwarded-Host", "h.test:81", "X-Forwarded-Port", "82"));
        assertThat(both.port()).hasValue(81);
        assertThat(resolve(X, from("10.0.0.1", "X-Forwarded-Host", "[2001:DB8::1]:444")).host()).contains("[2001:db8::1]");
        assertThat(resolve(X, from("10.0.0.1", "X-Forwarded-Host", "192.0.2.7")).host()).contains("192.0.2.7");
        var none = resolve(X, from("10.0.0.1"));
        assertThat(none.host()).isEmpty();
        assertThat(none.port()).isEmpty();
        assertThat(none.forwarded()).isFalse();
    }

    @Test
    void ignoresHostAndPortFromAnUntrustedPeer() throws Exception {
        for (var proxies : new TrustedProxies[] {X, F}) {
            var origin = resolve(proxies, from("198.51.100.20", "X-Forwarded-Host", "evil.test", "X-Forwarded-Port", "1",
                    "Forwarded", "for=203.0.113.1;host=evil.test:1;proto=https"));
            assertThat(origin.host()).isEmpty();
            assertThat(origin.port()).isEmpty();
            assertThat(origin.scheme()).isEqualTo("http");
            assertThat(origin.address().getHostAddress()).isEqualTo("198.51.100.20");
            assertThat(origin.forwarded()).isFalse();
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"a.test, b.test", "evil.test/path", "user@evil.test", "evil.test:99999", "evil.test:0", "evil.test:",
            "evil .test", "-bad.test", "bad-.test", "a..test", "1.2.3", "999.1.1.1", "[::1", "[evil]", "evil.test:80:80",
            "evïl.test", "", ":80", "[::1]x", "a_b.test"})
    void rejectsUnusableForwardedHosts(String value) throws Exception {
        var origin = resolve(X, from("10.0.0.1", "X-Forwarded-Host", value));
        assertThat(origin.host()).isEmpty();
        assertThat(origin.port()).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "65536", "-1", "80, 81", "8o", "+80", "080", "", "99999999999"})
    void rejectsUnusablePorts(String value) throws Exception {
        assertThat(resolve(X, from("10.0.0.1", "X-Forwarded-Port", value)).port()).isEmpty();
    }

    @Test
    void doesNotReadForwardedWhenXForwardedIsConfiguredAndTheOtherWayAround() throws Exception {
        var forwarded = "for=203.0.113.9;host=forwarded.test;proto=https";
        var xs = new String[] {"X-Forwarded-For", "203.0.113.5", "X-Forwarded-Host", "x.test", "X-Forwarded-Proto", "http"};
        var both = new String[] {xs[0], xs[1], xs[2], xs[3], xs[4], xs[5], "Forwarded", forwarded};
        var asX = resolve(X, from("10.0.0.1", both));
        assertThat(asX.address().getHostAddress()).isEqualTo("203.0.113.5");
        assertThat(asX.host()).contains("x.test");
        assertThat(asX.scheme()).isEqualTo("http");
        var asForwarded = resolve(F, from("10.0.0.1", both));
        assertThat(asForwarded.address().getHostAddress()).isEqualTo("203.0.113.9");
        assertThat(asForwarded.host()).contains("forwarded.test");
        assertThat(asForwarded.scheme()).isEqualTo("https");
        // X-Forwarded-* spoofing is invisible to a Forwarded-only configuration.
        var spoof = resolve(F, from("10.0.0.1", xs));
        assertThat(spoof.address().getHostAddress()).isEqualTo("10.0.0.1");
        assertThat(spoof.host()).isEmpty();
        assertThat(spoof.forwarded()).isFalse();
    }

    // RFC 7239 Forwarded

    @Test
    void takesTheRightmostUntrustedElementAndItsProtoAndHost() throws Exception {
        // The client prepended a fake element; the proxy at 10.0.0.1 appended the real one.
        var origin = resolve(F, from("10.0.0.1",
                "Forwarded", "for=1.2.3.4;proto=https;host=fake.test, for=203.0.113.7;proto=https;host=\"Api.Test:8443\", for=10.0.0.9;proto=http;host=inner.test"));
        assertThat(origin.address().getHostAddress()).isEqualTo("203.0.113.7");
        assertThat(origin.scheme()).isEqualTo("https");
        assertThat(origin.host()).contains("api.test");
        assertThat(origin.port()).hasValue(8443);
        assertThat(origin.forwarded()).isTrue();
    }

    @Test
    void usesTheLeftmostElementWhenEveryHopIsTrusted() throws Exception {
        assertThat(client(F, "for=10.1.1.1, for=10.0.0.9")).isEqualTo("10.1.1.1");
        var none = resolve(F, from("10.0.0.1"));
        assertThat(none.address().getHostAddress()).isEqualTo("10.0.0.1");
        assertThat(none.forwarded()).isFalse();
    }

    @Test
    void parsesQuotedValuesPortsAndIpv6() throws Exception {
        assertThat(client(F, "for=\"[2001:db8::7]:4711\"")).isEqualTo("2001:db8:0:0:0:0:0:7");
        assertThat(client(F, "for=\"[2001:db8::7]\"")).isEqualTo("2001:db8:0:0:0:0:0:7");
        assertThat(client(F, "for=\"203.0.113.5:4711\"")).isEqualTo("203.0.113.5");
        assertThat(client(F, "for=203.0.113.5")).isEqualTo("203.0.113.5");
        assertThat(client(F, "For=203.0.113.5;PROTO=https")).isEqualTo("203.0.113.5");
        assertThat(client(F, "for=\"203.0.113.5:_hidden\"")).isEqualTo("203.0.113.5"); // obfuscated port
        assertThat(client(F, "for=\"\\2\\0\\3.0.113.5\"")).isEqualTo("203.0.113.5"); // quoted-pair
        assertThat(resolve(F, from("10.0.0.1", "Forwarded", "for=203.0.113.5;host=\"[2001:db8::1]:444\"")).host())
                .contains("[2001:db8::1]");
    }

    @ParameterizedTest
    @ValueSource(strings = {"for=unknown", "for=_hidden", "for=_hidden:80", "for=example.org", "for=2001:db8::7",
            "for=\"[203.0.113.5]\"", "for=203.0.113.5:99999", "for=203.0.113.5:", "for=256.1.1.1",
            "for=203.0.113.007", "proto=https", "for=203.0.113.5;for=203.0.113.6",
            "for=203.0.113.5; proto=https", "for = 203.0.113.5", "for=203.0.113.5;", "for=", "=1.2.3.4", "for=1.2.3.4;;proto=http",
            "for=203.0.113.5;proto"})
    void stopsAtAnElementThatIsNotAnAddressLiteralAndKeepsTheLastVouchedAddress(String element) throws Exception {
        assertThat(client(F, "for=203.0.113.5, " + element)).isEqualTo("10.0.0.1");
        assertThat(client(F, "for=203.0.113.5, " + element + ", for=10.0.0.9")).isEqualTo("10.0.0.9");
    }

    @Test
    void ignoresAHeaderWithAnUnterminatedQuoteAndEmptyElements() throws Exception {
        for (var header : new String[] {"for=\"203.0.113.5", "for=\"203.0.113.5\\\"", "for=\"a\"b\"", "for=203.0.113.5, for=\"10.0.0.9", ",", "for=203.0.113.5,,for=10.0.0.9", ""}) {
            var origin = resolve(F, from("10.0.0.1", "Forwarded", header));
            assertThat(origin.address().getHostAddress()).isIn("10.0.0.1", "10.0.0.9");
            assertThat(origin.address().getHostAddress()).isNotEqualTo("203.0.113.5");
        }
    }

    @Test
    void takesProtoAndHostOnlyFromTheVouchingElement() throws Exception {
        // The outer proxy's proto must not decorate a client it did not see.
        var origin = resolve(F, from("10.0.0.1", "Forwarded", "for=203.0.113.5, for=10.0.0.9;proto=https;host=inner.test"));
        assertThat(origin.address().getHostAddress()).isEqualTo("203.0.113.5");
        assertThat(origin.scheme()).isEqualTo("http");
        assertThat(origin.host()).isEmpty();
        // An unusable proto or host in the vouching element is ignored, not repaired.
        var bad = resolve(F, from("10.0.0.1", "Forwarded", "for=203.0.113.5;proto=ftp;host=\"evil.test/x\""));
        assertThat(bad.scheme()).isEqualTo("http");
        assertThat(bad.host()).isEmpty();
        assertThat(bad.address().getHostAddress()).isEqualTo("203.0.113.5");
    }

    @Test
    void boundsTheNumberOfElementsItWalks() throws Exception {
        var chain = new StringBuilder("for=203.0.113.5");
        for (int i = 0; i < 40; i++) { chain.append(", for=10.0.0.").append(i + 1); }
        assertThat(client(F, chain.toString())).isEqualTo("10.0.0.9");
    }

    @Test
    void validatesPortsAsOptionalIntegers() {
        assertThat(new ClientOrigin(InetAddress.getLoopbackAddress(), "http", false).port()).isEqualTo(OptionalInt.empty());
        assertThat(new ClientOrigin(InetAddress.getLoopbackAddress(), "http", false).host()).isEqualTo(Optional.empty());
        org.assertj.core.api.Assertions.assertThatIllegalArgumentException().isThrownBy(() -> new ClientOrigin(
                InetAddress.getLoopbackAddress(), "http", false, Optional.empty(), OptionalInt.of(0)));
    }
}
