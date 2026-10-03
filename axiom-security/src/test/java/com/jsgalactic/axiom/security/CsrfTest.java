package com.jsgalactic.axiom.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import com.jsgalactic.axiom.Axiom;
import com.jsgalactic.axiom.application.Application;
import com.jsgalactic.axiom.context.SecurityIdentity;
import com.jsgalactic.axiom.http.Request;
import com.jsgalactic.axiom.http.Response;
import com.jsgalactic.axiom.test.TestClient;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.HashMap;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Synchronizer and double-submit CSRF protection through the real dispatcher. */
class CsrfTest {
    private static final String SESSION_COOKIE = "__Host-sid";
    private static final String CSRF_COOKIE = "__Host-csrf";

    private final RateLimitTest.ManualClock clock = new RateLimitTest.ManualClock();
    private final byte[] key = randomKey();
    private final InMemorySessionStore store = InMemorySessionStore.builder().clock(clock).build();
    private final Sessions sessions = Sessions.builder(store).build();
    private final Security security = Security.of(sessions.authenticator());

    private static byte[] randomKey() {
        var bytes = new byte[32];
        new SecureRandom().nextBytes(bytes);
        return bytes;
    }

    private static String body(Response response) {
        return response.body() instanceof byte[] bytes ? new String(bytes, StandardCharsets.UTF_8) : String.valueOf(response.body());
    }

    private static Request request(String method, String target, String... headers) {
        var map = new HashMap<String, String>();
        for (int i = 0; i < headers.length; i += 2) { map.put(headers[i], headers[i + 1]); }
        return Request.fromTarget(method, target).withHeaders(map);
    }

    private static String cookieValue(Response response, String name) {
        var header = response.headers().get("Set-Cookie");
        assertThat(header).isNotNull().startsWith(name + "=");
        return header.substring(name.length() + 1, header.indexOf(';'));
    }

    private Application synchronizerApp(Csrf.Builder builder) {
        var csrf = builder.build();
        var app = Axiom.create();
        app.use(sessions);
        app.use(security.authenticate());
        app.use(csrf);
        app.get("/form", ctx -> csrf.token(ctx));
        app.post("/login", ctx -> {
            sessions.session(ctx).authenticate(new SecurityIdentity("ada", Set.of(), Set.of()));
            return "in";
        });
        app.post("/transfer", ctx -> "done");
        app.put("/transfer", ctx -> "done");
        app.delete("/transfer", ctx -> "done");
        app.get("/read", ctx -> "read");
        return app;
    }

    private Application doubleSubmitApp(Csrf.Builder builder) {
        var csrf = builder.build();
        var app = Axiom.create();
        app.use(sessions);
        app.use(security.authenticate());
        app.use(csrf);
        app.get("/form", ctx -> csrf.token(ctx));
        app.get("/with-cookie", ctx -> Response.of(200, "x").withHeader("Set-Cookie", "other=1"));
        app.post("/login", ctx -> {
            sessions.session(ctx).authenticate(new SecurityIdentity("ada", Set.of(), Set.of()));
            return "in";
        });
        app.post("/transfer", ctx -> "done");
        return app;
    }

    private Csrf.Builder synchronizer() {
        return Csrf.synchronizer(sessions);
    }

    @Test
    void safeMethodsPassWithoutATokenAndUnsafeOnesNeedOne() throws Exception {
        try (var client = TestClient.start(synchronizerApp(synchronizer()))) {
            assertThat(client.execute(request("GET", "/read")).status()).isEqualTo(200);
            assertThat(client.execute(request("OPTIONS", "/read")).status()).isEqualTo(204);
            for (var method : new String[] {"POST", "PUT", "DELETE"}) {
                var rejected = client.execute(request(method, "/transfer"));
                assertThat(rejected.status()).as(method).isEqualTo(403);
                assertThat(rejected.headers()).containsEntry("Content-Type", "application/problem+json");
                assertThat(body(rejected)).contains("\"code\":\"csrf_rejected\"");
            }
        }
    }

    @Test
    void theSynchronizerTokenIsPerSessionAndAcceptedWithTheSessionOnly() throws Exception {
        try (var client = TestClient.start(synchronizerApp(synchronizer()))) {
            var page = client.execute(request("GET", "/form"));
            var token = body(page);
            assertThat(token).matches("[A-Za-z0-9_-]{43}");
            var cookie = SESSION_COOKIE + "=" + cookieValue(page, SESSION_COOKIE);
            assertThat(body(client.execute(request("GET", "/form", "Cookie", cookie)))).isEqualTo(token); // Stable per session.
            assertThat(client.execute(request("POST", "/transfer", "Cookie", cookie, "X-CSRF-Token", token)).status()).isEqualTo(200);
            // Reuse is allowed for the life of the session; another session's token is not.
            assertThat(client.execute(request("POST", "/transfer", "Cookie", cookie, "X-CSRF-Token", token)).status()).isEqualTo(200);
            var otherPage = client.execute(request("GET", "/form"));
            assertThat(body(otherPage)).isNotEqualTo(token);
            assertThat(client.execute(request("POST", "/transfer", "Cookie", cookie, "X-CSRF-Token", body(otherPage))).status()).isEqualTo(403);
        }
    }

    @Test
    void mismatchedMissingEmptyAndOversizedTokensAreRejected() throws Exception {
        try (var client = TestClient.start(synchronizerApp(synchronizer()))) {
            var page = client.execute(request("GET", "/form"));
            var token = body(page);
            var cookie = SESSION_COOKIE + "=" + cookieValue(page, SESSION_COOKIE);
            var flipped = token.substring(0, 42) + (token.charAt(42) == 'A' ? 'Q' : 'A');
            var swapped = token.toLowerCase().equals(token) ? token.toUpperCase() : token.toLowerCase();
            for (var bad : new String[] {flipped, "", token + "x", token.substring(1), " " + token, swapped, "x".repeat(5000)}) {
                assertThat(client.execute(request("POST", "/transfer", "Cookie", cookie, "X-CSRF-Token", bad)).status()).as(bad.length() + bad).isEqualTo(403);
            }
            assertThat(client.execute(request("POST", "/transfer", "Cookie", cookie)).status()).isEqualTo(403);
            // The token in a query parameter or the Cookie header is not accepted.
            assertThat(client.execute(request("POST", "/transfer?csrf=" + token, "Cookie", cookie)).status()).isEqualTo(403);
            // A session without a token yet cannot pass, even with a header.
            assertThat(client.execute(request("POST", "/transfer", "X-CSRF-Token", token)).status()).isEqualTo(403);
        }
    }

    @Test
    void loginDiscardsTheOldTokenSoAFixatedTokenIsUseless() throws Exception {
        try (var client = TestClient.start(synchronizerApp(synchronizer()))) {
            var page = client.execute(request("GET", "/form"));
            var oldToken = body(page);
            var anonymousCookie = SESSION_COOKIE + "=" + cookieValue(page, SESSION_COOKIE);
            var login = client.execute(request("POST", "/login", "Cookie", anonymousCookie, "X-CSRF-Token", oldToken));
            assertThat(login.status()).isEqualTo(200);
            var signedIn = SESSION_COOKIE + "=" + cookieValue(login, SESSION_COOKIE);
            assertThat(client.execute(request("POST", "/transfer", "Cookie", signedIn, "X-CSRF-Token", oldToken)).status()).isEqualTo(403);
            var fresh = body(client.execute(request("GET", "/form", "Cookie", signedIn)));
            assertThat(fresh).isNotEqualTo(oldToken);
            assertThat(client.execute(request("POST", "/transfer", "Cookie", signedIn, "X-CSRF-Token", fresh)).status()).isEqualTo(200);
            // A login without a token never gets that far.
            assertThat(client.execute(request("POST", "/login", "Cookie", anonymousCookie)).status()).isEqualTo(403);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"cross-site", "same-site", "bogus", "SAME-ORIGIN", ""})
    void fetchMetadataRejectsCrossSiteEvenWithAValidToken(String site) throws Exception {
        try (var client = TestClient.start(synchronizerApp(synchronizer()))) {
            var page = client.execute(request("GET", "/form"));
            var cookie = SESSION_COOKIE + "=" + cookieValue(page, SESSION_COOKIE);
            assertThat(client.execute(request("POST", "/transfer", "Cookie", cookie, "X-CSRF-Token", body(page), "Sec-Fetch-Site", site)).status())
                    .isEqualTo(403);
        }
    }

    @Test
    void fetchMetadataSameOriginNoneAndSameSiteWhenAllowed() throws Exception {
        try (var client = TestClient.start(synchronizerApp(synchronizer().allowSameSite(true)))) {
            var page = client.execute(request("GET", "/form"));
            var cookie = SESSION_COOKIE + "=" + cookieValue(page, SESSION_COOKIE);
            for (var site : new String[] {"same-origin", "none", "same-site"}) {
                assertThat(client.execute(request("POST", "/transfer", "Cookie", cookie, "X-CSRF-Token", body(page), "Sec-Fetch-Site", site)).status())
                        .as(site).isEqualTo(200);
            }
            assertThat(client.execute(request("POST", "/transfer", "Cookie", cookie, "X-CSRF-Token", body(page), "Sec-Fetch-Site", "cross-site")).status())
                    .isEqualTo(403);
        }
        try (var client = TestClient.start(synchronizerApp(synchronizer().fetchMetadata(false)))) {
            var page = client.execute(request("GET", "/form"));
            var cookie = SESSION_COOKIE + "=" + cookieValue(page, SESSION_COOKIE);
            assertThat(client.execute(request("POST", "/transfer", "Cookie", cookie, "X-CSRF-Token", body(page), "Sec-Fetch-Site", "cross-site")).status())
                    .isEqualTo(200);
        }
    }

    @Test
    void originMustBeAllowedOrMatchTheHost() throws Exception {
        try (var client = TestClient.start(synchronizerApp(synchronizer().allowedOrigins("https://app.example.com")))) {
            var page = client.execute(request("GET", "/form"));
            var cookie = SESSION_COOKIE + "=" + cookieValue(page, SESSION_COOKIE);
            var token = body(page);
            assertThat(client.execute(request("POST", "/transfer", "Cookie", cookie, "X-CSRF-Token", token, "Origin", "https://app.example.com")).status())
                    .isEqualTo(200);
            var wrong = new String[] {"https://evil.example", "null", "http://app.example.com", "https://app.example.com:8443",
                "https://app.example.com/x", "https://app.example.com.evil.example", "garbage", ""};
            for (var origin : wrong) {
                assertThat(client.execute(request("POST", "/transfer", "Cookie", cookie, "X-CSRF-Token", token, "Origin", origin)).status())
                        .as(origin).isEqualTo(403);
            }
        }
        try (var client = TestClient.start(synchronizerApp(synchronizer()))) {
            var page = client.execute(request("GET", "/form"));
            var cookie = SESSION_COOKIE + "=" + cookieValue(page, SESSION_COOKIE);
            var token = body(page);
            assertThat(client.execute(request("POST", "/transfer", "Cookie", cookie, "X-CSRF-Token", token, "Origin", "https://Shop.example:8443",
                    "Host", "shop.example:8443")).status()).isEqualTo(200);
            assertThat(client.execute(request("POST", "/transfer", "Cookie", cookie, "X-CSRF-Token", token, "Origin", "https://evil.example",
                    "Host", "shop.example")).status()).isEqualTo(403);
            assertThat(client.execute(request("POST", "/transfer", "Cookie", cookie, "X-CSRF-Token", token, "Origin", "https://shop.example")).status())
                    .isEqualTo(403); // No Host to compare with.
        }
    }

    @Test
    void exemptionsApplyToTheRequestPredicateOnly() throws Exception {
        try (var client = TestClient.start(synchronizerApp(synchronizer().exemptWhen(request -> request.header("Authorization").isPresent())))) {
            assertThat(client.execute(request("POST", "/transfer", "Authorization", "Bearer x")).status()).isEqualTo(200);
            assertThat(client.execute(request("POST", "/transfer")).status()).isEqualTo(403);
        }
    }

    @Test
    void aCustomHeaderNameIsHonoured() throws Exception {
        try (var client = TestClient.start(synchronizerApp(synchronizer().headerName("X-XSRF-Token")))) {
            var page = client.execute(request("GET", "/form"));
            var cookie = SESSION_COOKIE + "=" + cookieValue(page, SESSION_COOKIE);
            assertThat(client.execute(request("POST", "/transfer", "Cookie", cookie, "X-CSRF-Token", body(page))).status()).isEqualTo(403);
            assertThat(client.execute(request("POST", "/transfer", "Cookie", cookie, "X-XSRF-Token", body(page))).status()).isEqualTo(200);
        }
    }

    // ---- double submit

    private Csrf.Builder doubleSubmit() {
        return Csrf.doubleSubmit(key).clock(clock);
    }

    @Test
    void doubleSubmitIssuesAHardenedCookieAndAcceptsTheEchoedToken() throws Exception {
        try (var client = TestClient.start(doubleSubmitApp(doubleSubmit()))) {
            var page = client.execute(request("GET", "/form"));
            var header = page.headers().get("Set-Cookie");
            assertThat(header).matches(CSRF_COOKIE + "=[A-Za-z0-9_-]{32}\\.[A-Za-z0-9_-]{43}; Path=/; Secure; SameSite=Lax");
            var token = cookieValue(page, CSRF_COOKIE);
            assertThat(body(page)).isEqualTo(token);
            var cookie = CSRF_COOKIE + "=" + token;
            assertThat(client.execute(request("POST", "/transfer", "Cookie", cookie, "X-CSRF-Token", token)).status()).isEqualTo(200);
            // A valid cookie is not re-issued on later responses.
            assertThat(client.execute(request("GET", "/form", "Cookie", cookie)).headers()).doesNotContainKey("Set-Cookie");
            assertThat(client.execute(request("POST", "/transfer", "Cookie", cookie)).status()).isEqualTo(403);
            assertThat(client.execute(request("POST", "/transfer", "X-CSRF-Token", token)).status()).isEqualTo(403);
            // The token itself is bound to the cookie: a header that differs fails.
            var other = cookieValue(client.execute(request("GET", "/form")), CSRF_COOKIE);
            assertThat(client.execute(request("POST", "/transfer", "Cookie", cookie, "X-CSRF-Token", other)).status()).isEqualTo(403);
        }
    }

    @Test
    void forgedTokensFailEvenWhenCookieAndHeaderAgree() throws Exception {
        try (var client = TestClient.start(doubleSubmitApp(doubleSubmit()))) {
            var token = cookieValue(client.execute(request("GET", "/form")), CSRF_COOKIE);
            var payload = token.substring(0, 32);
            var tag = token.substring(33);
            var attacker = Csrf.doubleSubmit(randomKey()).clock(clock);
            var forgedWithOtherKey = cookieValue(TestClient.start(doubleSubmitAppWith(attacker)).execute(request("GET", "/form")), CSRF_COOKIE);
            var flippedTag = tag.substring(0, 42) + (tag.charAt(42) == 'A' ? 'Q' : 'A');
            var forgeries = new String[] {forgedWithOtherKey, payload + "." + flippedTag, "A".repeat(32) + "." + tag, payload + ".", "plain-value",
                payload + "." + tag + "x", payload.substring(1) + "." + tag, "!".repeat(32) + "." + "!".repeat(43), ""};
            for (var forged : forgeries) {
                var cookie = CSRF_COOKIE + "=" + forged;
                assertThat(client.execute(request("POST", "/transfer", "Cookie", cookie, "X-CSRF-Token", forged)).status()).as(forged).isEqualTo(403);
            }
        }
    }

    private Application doubleSubmitAppWith(Csrf.Builder builder) {
        return doubleSubmitApp(builder);
    }

    @Test
    void doubleSubmitTokensExpireAndAreReissued() throws Exception {
        try (var client = TestClient.start(doubleSubmitApp(doubleSubmit().maxAge(Duration.ofHours(1))))) {
            var token = cookieValue(client.execute(request("GET", "/form")), CSRF_COOKIE);
            var cookie = CSRF_COOKIE + "=" + token;
            clock.advance(Duration.ofMinutes(59));
            assertThat(client.execute(request("POST", "/transfer", "Cookie", cookie, "X-CSRF-Token", token)).status()).isEqualTo(200);
            clock.advance(Duration.ofMinutes(2));
            assertThat(client.execute(request("POST", "/transfer", "Cookie", cookie, "X-CSRF-Token", token)).status()).isEqualTo(403);
            var renewed = client.execute(request("GET", "/form", "Cookie", cookie));
            var fresh = cookieValue(renewed, CSRF_COOKIE);
            assertThat(fresh).isNotEqualTo(token);
            assertThat(client.execute(request("POST", "/transfer", "Cookie", CSRF_COOKIE + "=" + fresh, "X-CSRF-Token", fresh)).status()).isEqualTo(200);
            // A clock that moved backwards makes tokens look like they come from the future: refused beyond a minute of skew.
            clock.advance(Duration.ofHours(-5));
            assertThat(client.execute(request("POST", "/transfer", "Cookie", CSRF_COOKIE + "=" + fresh, "X-CSRF-Token", fresh)).status()).isEqualTo(403);
        }
    }

    @Test
    void doubleSubmitTokensAreBoundToTheUser() throws Exception {
        try (var client = TestClient.start(doubleSubmitApp(doubleSubmit()))) {
            var anonymousToken = cookieValue(client.execute(request("GET", "/form")), CSRF_COOKIE);
            var login = client.execute(request("POST", "/login", "Cookie", CSRF_COOKIE + "=" + anonymousToken, "X-CSRF-Token", anonymousToken));
            var session = SESSION_COOKIE + "=" + cookieValue(login, SESSION_COOKIE);
            var both = session + "; " + CSRF_COOKIE + "=" + anonymousToken;
            // A token planted before sign-in (for example by a sub-domain) does not work for the signed-in user.
            assertThat(client.execute(request("POST", "/transfer", "Cookie", both, "X-CSRF-Token", anonymousToken)).status()).isEqualTo(403);
            var refreshed = client.execute(request("GET", "/form", "Cookie", both));
            var userToken = cookieValue(refreshed, CSRF_COOKIE);
            var userBoth = session + "; " + CSRF_COOKIE + "=" + userToken;
            assertThat(client.execute(request("POST", "/transfer", "Cookie", userBoth, "X-CSRF-Token", userToken)).status()).isEqualTo(200);
            // And the signed-in user's token is useless for an anonymous browser.
            assertThat(client.execute(request("POST", "/transfer", "Cookie", CSRF_COOKIE + "=" + userToken, "X-CSRF-Token", userToken)).status()).isEqualTo(403);
        }
    }

    @Test
    void duplicateOrMalformedCsrfCookiesFailClosed() throws Exception {
        try (var client = TestClient.start(doubleSubmitApp(doubleSubmit()))) {
            var token = cookieValue(client.execute(request("GET", "/form")), CSRF_COOKIE);
            var other = cookieValue(client.execute(request("GET", "/form")), CSRF_COOKIE);
            var duplicated = CSRF_COOKIE + "=" + token + "; " + CSRF_COOKIE + "=" + other;
            assertThat(client.execute(request("POST", "/transfer", "Cookie", duplicated, "X-CSRF-Token", token)).status()).isEqualTo(403);
            assertThat(client.execute(request("POST", "/transfer", "Cookie", CSRF_COOKIE + "=" + token + ";x=1", "X-CSRF-Token", token)).status())
                    .isEqualTo(403);
        }
    }

    @Test
    void doubleSubmitDoesNotOverwriteAnotherCookieOfTheResponse() throws Exception {
        try (var client = TestClient.start(doubleSubmitApp(doubleSubmit()))) {
            var response = client.execute(request("GET", "/with-cookie"));
            assertThat(response.headers()).containsEntry("Set-Cookie", "other=1");
        }
    }

    @Test
    void fetchMetadataAndOriginApplyToDoubleSubmitToo() throws Exception {
        try (var client = TestClient.start(doubleSubmitApp(doubleSubmit().allowedOrigins("https://app.example.com")))) {
            var token = cookieValue(client.execute(request("GET", "/form")), CSRF_COOKIE);
            var cookie = CSRF_COOKIE + "=" + token;
            assertThat(client.execute(request("POST", "/transfer", "Cookie", cookie, "X-CSRF-Token", token, "Origin", "https://app.example.com",
                    "Sec-Fetch-Site", "same-origin")).status()).isEqualTo(200);
            assertThat(client.execute(request("POST", "/transfer", "Cookie", cookie, "X-CSRF-Token", token, "Origin", "https://evil.example")).status())
                    .isEqualTo(403);
            assertThat(client.execute(request("POST", "/transfer", "Cookie", cookie, "X-CSRF-Token", token, "Sec-Fetch-Site", "cross-site")).status())
                    .isEqualTo(403);
        }
    }

    @Test
    void scriptHiddenAndDevelopmentCookieOptions() throws Exception {
        try (var client = TestClient.start(doubleSubmitApp(doubleSubmit().httpOnly(true).sameSite(SetCookie.SameSite.STRICT)))) {
            assertThat(client.execute(request("GET", "/form")).headers().get("Set-Cookie")).endsWith("Secure; HttpOnly; SameSite=Strict");
        }
        try (var client = TestClient.start(doubleSubmitApp(doubleSubmit().secure(false)))) {
            assertThat(client.execute(request("GET", "/form")).headers().get("Set-Cookie")).startsWith("csrf=").doesNotContain("Secure");
        }
    }

    @Test
    void configurationIsValidated() {
        assertThatIllegalArgumentException().isThrownBy(() -> Csrf.doubleSubmit(new byte[31]));
        assertThatIllegalArgumentException().isThrownBy(() -> synchronizer().headerName("bad header"));
        assertThatIllegalArgumentException().isThrownBy(() -> synchronizer().allowedOrigins("https://App.example.com"));
        assertThatIllegalArgumentException().isThrownBy(() -> synchronizer().allowedOrigins("*"));
        assertThatIllegalArgumentException().isThrownBy(() -> synchronizer().allowedOrigins("https://a.example/path"));
        assertThatIllegalArgumentException().isThrownBy(() -> doubleSubmit().maxAge(Duration.ofSeconds(1)));
        assertThatIllegalArgumentException().isThrownBy(() -> doubleSubmit().secure(false).cookieName("__Host-csrf").build());
        assertThat(doubleSubmit().build().toString()).isEqualTo("Csrf[double-submit]");
    }
}
