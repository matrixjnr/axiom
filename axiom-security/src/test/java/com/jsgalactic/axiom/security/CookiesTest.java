package com.jsgalactic.axiom.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

import com.jsgalactic.axiom.http.Request;
import com.jsgalactic.axiom.http.Response;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Strict cookie parsing and secure Set-Cookie construction. */
class CookiesTest {
    @Test
    void parsesWellFormedHeaders() {
        var cookies = Cookies.parse("a=1; b=; c=x.y-z_9");
        assertThat(cookies.get("a")).contains("1");
        assertThat(cookies.get("b")).contains("");
        assertThat(cookies.get("c")).contains("x.y-z_9");
        assertThat(cookies.get("A")).isEmpty(); // Names are case-sensitive.
        assertThat(cookies.names()).containsExactly("a", "b", "c");
        assertThat(cookies.toString()).doesNotContain("x.y-z_9");
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "a", "=v", "a=1;b=2", "a=1;  b=2", "a=1; ", "a=1;", " a=1", "a=1 ", "a =1", "a=\"q\"", "a=b,c", "a=b\\c",
        "a=é", "a=1\t", "a b=1", "a=1; ; b=2", "a=1; =2", "a=1\u0000", "a=b c", "(x)=1", "a=1;b"})
    void rejectsMalformedHeaders(String header) {
        assertThatIllegalArgumentException().isThrownBy(() -> Cookies.parse(header));
    }

    @Test
    void boundsHeaderSizeAndPairCount() {
        var long64 = "a=" + "x".repeat(62);
        assertThat(Cookies.parse(long64, 64).get("a")).isPresent();
        assertThatIllegalArgumentException().isThrownBy(() -> Cookies.parse(long64 + "x", 64));
        var pairs = new StringBuilder();
        for (int i = 0; i < Cookies.MAX_PAIRS; i++) { pairs.append(i == 0 ? "" : "; ").append('c').append(i).append("=1"); }
        assertThat(Cookies.parse(pairs.toString()).names()).hasSize(Cookies.MAX_PAIRS);
        assertThatIllegalArgumentException().isThrownBy(() -> Cookies.parse(pairs + "; extra=1"));
        assertThatIllegalArgumentException().isThrownBy(() -> Cookies.parse("a=1", 63));
        assertThatIllegalArgumentException().isThrownBy(() -> Cookies.parse("a=1", 70_000));
    }

    @Test
    void duplicateNamesAreAmbiguousNotFirstWins() {
        var cookies = Cookies.parse("sid=one; other=1; sid=two");
        assertThat(cookies.get("sid")).isEmpty();
        assertThat(cookies.all("sid")).containsExactly("one", "two");
        assertThat(cookies.get("other")).contains("1");
    }

    @Test
    void requestsWithBadHeadersHaveNoCookies() {
        assertThat(Cookies.of(Request.get("/")).names()).isEmpty();
        assertThat(Cookies.of(Request.get("/").withHeaders(Map.of("Cookie", "a=1; b=2"))).get("b")).contains("2");
        assertThat(Cookies.of(Request.get("/").withHeaders(Map.of("Cookie", "a=1;b=2"))).names()).isEmpty();
        assertThat(Cookies.of(Request.get("/").withHeaders(Map.of("Cookie", "a=" + "x".repeat(100))), 64).names()).isEmpty();
        assertThat(Cookies.none().get("a")).isEmpty();
    }

    @Test
    void setCookieDefaultsAreSecure() {
        assertThat(SetCookie.of("theme", "dark").header()).isEqualTo("theme=dark; Path=/; Secure; HttpOnly; SameSite=Lax");
        var full = SetCookie.of("sid", "v").domain("example.com").path("/app").maxAge(Duration.ofMinutes(5))
                .sameSite(SetCookie.SameSite.STRICT).httpOnly(false);
        assertThat(full.header()).isEqualTo("sid=v; Path=/app; Domain=example.com; Max-Age=300; Secure; SameSite=Strict");
        assertThat(SetCookie.of("sid", "v").expire().header()).isEqualTo("sid=; Path=/; Max-Age=0; Secure; HttpOnly; SameSite=Lax");
        assertThat(SetCookie.of("sid", "secret").toString()).doesNotContain("secret");
    }

    @Test
    void prefixesAndSameSiteNoneAreEnforced() {
        assertThat(SetCookie.of("__Host-a", "1").header()).startsWith("__Host-a=1");
        assertThatIllegalArgumentException().isThrownBy(() -> SetCookie.of("__Host-a", "1").secure(false));
        assertThatIllegalArgumentException().isThrownBy(() -> SetCookie.of("__Host-a", "1").domain("example.com"));
        assertThatIllegalArgumentException().isThrownBy(() -> SetCookie.of("__Host-a", "1").path("/x"));
        assertThatIllegalArgumentException().isThrownBy(() -> SetCookie.of("__host-a", "1").path("/x"));
        assertThatIllegalArgumentException().isThrownBy(() -> SetCookie.of("__Secure-a", "1").secure(false));
        assertThat(SetCookie.of("__Secure-a", "1").path("/x").domain("example.com").header()).contains("Domain=example.com");
        assertThatIllegalArgumentException().isThrownBy(() -> SetCookie.of("a", "1").secure(false).sameSite(SetCookie.SameSite.NONE));
        assertThat(SetCookie.of("a", "1").sameSite(SetCookie.SameSite.NONE).header()).endsWith("SameSite=None");
    }

    @Test
    void invalidPartsAreRejected() {
        assertThatIllegalArgumentException().isThrownBy(() -> SetCookie.of("a b", "1"));
        assertThatIllegalArgumentException().isThrownBy(() -> SetCookie.of("a", "1;Domain=evil.example"));
        assertThatIllegalArgumentException().isThrownBy(() -> SetCookie.of("a", "x".repeat(4096)));
        assertThatIllegalArgumentException().isThrownBy(() -> SetCookie.of("a", "1").path("app"));
        assertThatIllegalArgumentException().isThrownBy(() -> SetCookie.of("a", "1").path("/a;Secure"));
        assertThatIllegalArgumentException().isThrownBy(() -> SetCookie.of("a", "1").domain(".example.com"));
        assertThatIllegalArgumentException().isThrownBy(() -> SetCookie.of("a", "1").domain("Example.com"));
        assertThatIllegalArgumentException().isThrownBy(() -> SetCookie.of("a", "1").domain("192.0.2.1"));
        assertThatIllegalArgumentException().isThrownBy(() -> SetCookie.of("a", "1").maxAge(Duration.ofSeconds(-1)));
        assertThatIllegalArgumentException().isThrownBy(() -> SetCookie.of("a", "1").maxAge(Duration.ofDays(401)));
    }

    @Test
    void oneSetCookiePerResponse() {
        var first = SetCookie.of("a", "1").addTo(Response.of(200, "x"));
        assertThat(first.headers()).containsEntry("Set-Cookie", "a=1; Path=/; Secure; HttpOnly; SameSite=Lax");
        assertThatIllegalStateException().isThrownBy(() -> SetCookie.of("b", "2").addTo(first));
    }
}
