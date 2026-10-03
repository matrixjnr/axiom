package com.jsgalactic.axiom.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import com.jsgalactic.axiom.Axiom;
import com.jsgalactic.axiom.application.Application;
import com.jsgalactic.axiom.context.SecurityIdentity;
import com.jsgalactic.axiom.http.Request;
import com.jsgalactic.axiom.http.Response;
import com.jsgalactic.axiom.test.TestClient;
import java.time.Duration;
import java.util.HashMap;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Server-side sessions through the real dispatcher: cookies, rotation, fixation, forgery, expiry. */
class SessionsTest {
    private static final String COOKIE = "__Host-sid";

    private final RateLimitTest.ManualClock clock = new RateLimitTest.ManualClock();
    private final InMemorySessionStore store = InMemorySessionStore.builder().clock(clock)
            .idleTimeout(Duration.ofMinutes(10)).absoluteTimeout(Duration.ofHours(1)).build();
    private final Sessions sessions = Sessions.builder(store).build();
    private final Security security = Security.of(sessions.authenticator());

    private Application app() {
        var app = Axiom.create();
        app.use(sessions);
        app.use(security.authenticate());
        app.get("/peek", ctx -> sessions.session(ctx).attribute("n").orElse("none") + "/"
                + ctx.identity().map(SecurityIdentity::principal).orElse("anonymous"));
        app.post("/set", ctx -> {
            sessions.session(ctx).attribute("n", ctx.query("v").orElseThrow());
            return "set";
        });
        app.post("/login", ctx -> {
            sessions.session(ctx).authenticate(new SecurityIdentity(ctx.query("user").orElseThrow(), Set.of(ctx.query("role").orElse("user")), Set.of()));
            return "in";
        });
        app.post("/rotate", ctx -> {
            sessions.session(ctx).rotate();
            return "rotated";
        });
        app.post("/logout", ctx -> {
            sessions.session(ctx).invalidate();
            return "out";
        });
        app.post("/boom", ctx -> {
            sessions.session(ctx).attribute("n", "lost");
            throw new IllegalStateException("handler failure");
        });
        app.post("/own-cookie", ctx -> {
            sessions.session(ctx).attribute("n", "x");
            return Response.of(200, "x").withHeader("Set-Cookie", "other=1");
        });
        app.get("/me", ctx -> ctx.identity().orElseThrow().principal(), security.authenticated());
        app.get("/admin", ctx -> "admin", security.hasRole("admin"));
        return app;
    }

    private static Request request(String method, String target, String cookie) {
        var request = Request.fromTarget(method, target);
        var headers = new HashMap<String, String>();
        if (cookie != null) { headers.put("Cookie", cookie); }
        return request.withHeaders(headers);
    }

    private static String sessionCookie(Response response) {
        var header = response.headers().get("Set-Cookie");
        assertThat(header).as("Set-Cookie").isNotNull().startsWith(COOKIE + "=");
        return header.substring(COOKIE.length() + 1, header.indexOf(';'));
    }

    private static String body(Response response) {
        return response.body() instanceof byte[] bytes ? new String(bytes, java.nio.charset.StandardCharsets.UTF_8) : String.valueOf(response.body());
    }

    private String body(TestClient client, String method, String target, String id) throws Exception {
        return body(client.execute(request(method, target, id == null ? null : COOKIE + "=" + id)));
    }

    @Test
    void readingNeverCreatesASessionOrACookie() throws Exception {
        try (var client = TestClient.start(app())) {
            var response = client.execute(request("GET", "/peek", null));
            assertThat(response.headers()).doesNotContainKey("Set-Cookie");
            assertThat(body(response)).isEqualTo("none/anonymous");
        }
        assertThat(store.size()).isZero();
    }

    @Test
    void firstWriteIssuesASecureCookieAndLaterRequestsSeeTheData() throws Exception {
        try (var client = TestClient.start(app())) {
            var response = client.execute(request("POST", "/set?v=7", null));
            var header = response.headers().get("Set-Cookie");
            assertThat(header).matches(COOKIE + "=[A-Za-z0-9_-]{43}; Path=/; Secure; HttpOnly; SameSite=Lax");
            var id = sessionCookie(response);
            assertThat(body(client, "GET", "/peek", id)).isEqualTo("7/anonymous");
            // Further writes keep the identifier, so no new cookie is sent.
            var again = client.execute(request("POST", "/set?v=8", COOKIE + "=" + id));
            assertThat(again.headers()).doesNotContainKey("Set-Cookie");
            assertThat(body(client, "GET", "/peek", id)).isEqualTo("8/anonymous");
        }
        assertThat(store.size()).isEqualTo(1);
    }

    @Test
    void identifiersAreUniqueAndRandom() throws Exception {
        var seen = new java.util.HashSet<String>();
        try (var client = TestClient.start(app())) {
            for (int i = 0; i < 200; i++) { assertThat(seen.add(sessionCookie(client.execute(request("POST", "/set?v=1", null))))).isTrue(); }
        }
    }

    @Test
    void loginRotatesTheIdentifierAndTheOldOneStopsWorking() throws Exception {
        try (var client = TestClient.start(app())) {
            var anonymous = sessionCookie(client.execute(request("POST", "/set?v=cart", null)));
            var login = client.execute(request("POST", "/login?user=ada", COOKIE + "=" + anonymous));
            var signedIn = sessionCookie(login);
            assertThat(signedIn).isNotEqualTo(anonymous);
            assertThat(body(client, "GET", "/peek", signedIn)).isEqualTo("cart/ada"); // Data is kept.
            // The pre-login identifier is dead: a planted or sniffed value gains nothing.
            assertThat(body(client, "GET", "/peek", anonymous)).isEqualTo("none/anonymous");
            assertThat(client.execute(request("GET", "/me", COOKIE + "=" + anonymous)).status()).isEqualTo(401);
            assertThat(client.execute(request("GET", "/me", COOKIE + "=" + signedIn)).status()).isEqualTo(200);
        }
    }

    @Test
    void sessionFixationWithAPlantedIdentifierFails() throws Exception {
        try (var client = TestClient.start(app())) {
            // The attacker obtains a valid anonymous identifier and plants it in the victim's browser.
            var planted = sessionCookie(client.execute(request("POST", "/set?v=x", null)));
            var victimLogin = client.execute(request("POST", "/login?user=victim", COOKIE + "=" + planted));
            assertThat(sessionCookie(victimLogin)).isNotEqualTo(planted);
            assertThat(client.execute(request("GET", "/me", COOKIE + "=" + planted)).status()).isEqualTo(401);
            // An identifier the attacker invented is never adopted either.
            var invented = "A".repeat(43);
            var login = client.execute(request("POST", "/login?user=victim", COOKIE + "=" + invented));
            assertThat(sessionCookie(login)).isNotEqualTo(invented);
            assertThat(client.execute(request("GET", "/me", COOKIE + "=" + invented)).status()).isEqualTo(401);
        }
    }

    @Test
    void explicitRotationKeepsDataAndPrivilegeChangeRotatesAgain() throws Exception {
        try (var client = TestClient.start(app())) {
            var first = sessionCookie(client.execute(request("POST", "/login?user=ada&role=user", null)));
            assertThat(client.execute(request("GET", "/admin", COOKIE + "=" + first)).status()).isEqualTo(403);
            var elevated = sessionCookie(client.execute(request("POST", "/login?user=ada&role=admin", COOKIE + "=" + first)));
            assertThat(elevated).isNotEqualTo(first);
            assertThat(client.execute(request("GET", "/admin", COOKIE + "=" + first)).status()).isEqualTo(401);
            assertThat(client.execute(request("GET", "/admin", COOKIE + "=" + elevated)).status()).isEqualTo(200);
            var rotated = sessionCookie(client.execute(request("POST", "/rotate", COOKIE + "=" + elevated)));
            assertThat(client.execute(request("GET", "/admin", COOKIE + "=" + elevated)).status()).isEqualTo(401);
            assertThat(client.execute(request("GET", "/admin", COOKIE + "=" + rotated)).status()).isEqualTo(200);
        }
    }

    @Test
    void forgedMalformedAndWronglyNamedCookiesAreAnonymous() throws Exception {
        try (var client = TestClient.start(app())) {
            var valid = sessionCookie(client.execute(request("POST", "/login?user=ada", null)));
            var flipped = valid.substring(0, 20) + (valid.charAt(20) == 'a' ? 'b' : 'a') + valid.substring(21);
            for (var forged : new String[] {flipped, "", valid + "A", valid.substring(1), "!".repeat(43), "a".repeat(44), valid.substring(0, 42) + "B"}) {
                assertThat(client.execute(request("GET", "/me", COOKIE + "=" + forged)).status()).as(forged).isEqualTo(401);
            }
            assertThat(client.execute(request("GET", "/me", "sid=" + valid)).status()).isEqualTo(401);
            assertThat(client.execute(request("GET", "/me", "x-" + COOKIE + "=" + valid)).status()).isEqualTo(401);
            assertThat(client.execute(request("GET", "/me", COOKIE + "=" + valid)).status()).isEqualTo(200);
        }
    }

    @Test
    void cookieParsingAbuseMeansNoSession() throws Exception {
        try (var client = TestClient.start(app())) {
            var valid = sessionCookie(client.execute(request("POST", "/login?user=ada", null)));
            var good = COOKIE + "=" + valid;
            var abuse = new String[] {
                good + ";x=1",                      // no space after the semicolon
                "a=1; " + good + ";",               // trailing separator
                good + "; " + good,                 // sent twice: ambiguous
                good + "; " + COOKIE + "=" + "B".repeat(43), // a planted second cookie of the same name
                "\"" + good + "\"",
                good + "; junk=\"v\"",
                good + "; " + "p=".concat("x".repeat(9000)),
            };
            for (var cookie : abuse) {
                assertThat(client.execute(request("GET", "/me", cookie)).status()).as(cookie.length() + ":" + cookie.substring(0, Math.min(30, cookie.length()))).isEqualTo(401);
            }
            assertThat(client.execute(request("GET", "/me", "a=1; " + good + "; z=2")).status()).isEqualTo(200);
        }
    }

    @Test
    void idleAndAbsoluteTimeoutsEndTheSession() throws Exception {
        try (var client = TestClient.start(app())) {
            var id = sessionCookie(client.execute(request("POST", "/login?user=ada", null)));
            clock.advance(Duration.ofMinutes(9));
            assertThat(client.execute(request("GET", "/me", COOKIE + "=" + id)).status()).isEqualTo(200);
            clock.advance(Duration.ofMinutes(10));
            assertThat(client.execute(request("GET", "/me", COOKIE + "=" + id)).status()).isEqualTo(401);
            assertThat(client.execute(request("GET", "/me", COOKIE + "=" + id)).status()).isEqualTo(401);
            var busy = sessionCookie(client.execute(request("POST", "/login?user=bob", null)));
            for (int i = 0; i < 7; i++) {
                clock.advance(Duration.ofMinutes(9));
                client.execute(request("GET", "/me", COOKIE + "=" + busy));
            }
            clock.advance(Duration.ofMinutes(9)); // 72 minutes since login: past the absolute limit.
            assertThat(client.execute(request("GET", "/me", COOKIE + "=" + busy)).status()).isEqualTo(401);
        }
    }

    @Test
    void logoutDeletesTheSessionAndExpiresTheCookie() throws Exception {
        try (var client = TestClient.start(app())) {
            var id = sessionCookie(client.execute(request("POST", "/login?user=ada", null)));
            var out = client.execute(request("POST", "/logout", COOKIE + "=" + id));
            assertThat(out.headers().get("Set-Cookie")).startsWith(COOKIE + "=; Path=/; Max-Age=0; Secure; HttpOnly");
            assertThat(store.size()).isZero();
            assertThat(client.execute(request("GET", "/me", COOKIE + "=" + id)).status()).isEqualTo(401);
            // Logging out without a session sends no cookie.
            assertThat(client.execute(request("POST", "/logout", null)).headers()).doesNotContainKey("Set-Cookie");
        }
    }

    @Test
    void aFailingHandlerAppliesNoSessionWrites() throws Exception {
        try (var client = TestClient.start(app())) {
            var id = sessionCookie(client.execute(request("POST", "/set?v=kept", null)));
            org.assertj.core.api.Assertions.assertThatThrownBy(() -> client.execute(request("POST", "/boom", COOKIE + "=" + id)))
                    .isInstanceOf(IllegalStateException.class);
            assertThat(body(client, "GET", "/peek", id)).isEqualTo("kept/anonymous");
            org.assertj.core.api.Assertions.assertThatThrownBy(() -> client.execute(request("POST", "/boom", null)))
                    .isInstanceOf(IllegalStateException.class);
        }
        assertThat(store.size()).isEqualTo(1);
    }

    @Test
    void aSecondCookieFromTheHandlerFailsLoudlyBeforeTouchingTheStore() throws Exception {
        try (var client = TestClient.start(app())) {
            org.assertj.core.api.Assertions.assertThatThrownBy(() -> client.execute(request("POST", "/own-cookie", null)))
                    .isInstanceOf(IllegalStateException.class).hasMessageContaining("Set-Cookie");
        }
        assertThat(store.size()).isZero();
    }

    @Test
    void policiesAnswer401And403ThroughTheErrorModel() throws Exception {
        try (var client = TestClient.start(app())) {
            var anonymous = client.execute(request("GET", "/me", null));
            assertThat(anonymous.status()).isEqualTo(401);
            assertThat(anonymous.headers()).containsEntry("WWW-Authenticate", "Cookie realm=\"session\"")
                    .containsEntry("Content-Type", "application/problem+json");
            var user = sessionCookie(client.execute(request("POST", "/login?user=ada", null)));
            assertThat(client.execute(request("GET", "/admin", COOKIE + "=" + user)).status()).isEqualTo(403);
        }
    }

    @Test
    void concurrentRequestsOfOneSessionKeepEachOthersAttributes() throws Exception {
        var barrier = new CyclicBarrier(2);
        var seed = Axiom.create();
        seed.use(sessions);
        seed.post("/seed", ctx -> {
            sessions.session(ctx).attribute("seed", "1");
            return "ok";
        });
        seed.post("/slow", ctx -> {
            sessions.session(ctx).attribute(ctx.query("k").orElseThrow(), "1");
            barrier.await(10, TimeUnit.SECONDS);
            return "ok";
        });
        seed.get("/dump", ctx -> sessions.session(ctx).attributes().keySet().toString());
        try (var client = TestClient.start(seed)) {
            var id = sessionCookie(client.execute(request("POST", "/seed", null)));
            var one = client.submit(request("POST", "/slow?k=a", COOKIE + "=" + id));
            var two = client.submit(request("POST", "/slow?k=b", COOKIE + "=" + id));
            assertThat(one.get(15, TimeUnit.SECONDS).status()).isEqualTo(200);
            assertThat(two.get(15, TimeUnit.SECONDS).status()).isEqualTo(200);
            assertThat(body(client, "GET", "/dump", id)).isEqualTo("[a, b, seed]");
        }
    }

    @Test
    void aSessionEndedElsewhereIsNotResurrectedByALaterWriteButALoginStartsANewOne() throws Exception {
        var inside = new CountDownLatch(1);
        var proceed = new CountDownLatch(1);
        var app = Axiom.create();
        app.use(sessions);
        app.post("/seed", ctx -> {
            sessions.session(ctx).attribute("a", "1");
            return "ok";
        });
        app.post("/write", ctx -> {
            sessions.session(ctx).attribute("b", "2");
            inside.countDown();
            proceed.await(10, TimeUnit.SECONDS);
            return "ok";
        });
        app.post("/relogin", ctx -> {
            sessions.session(ctx).authenticate(new SecurityIdentity("ada", Set.of(), Set.of()));
            inside.countDown();
            proceed.await(10, TimeUnit.SECONDS);
            return "ok";
        });
        try (var client = TestClient.start(app)) {
            var id = sessionCookie(client.execute(request("POST", "/seed", null)));
            var write = client.submit(request("POST", "/write", COOKIE + "=" + id));
            assertThat(inside.await(10, TimeUnit.SECONDS)).isTrue();
            store.remove(id); // Logged out in another tab while the request was in flight.
            proceed.countDown();
            assertThat(write.get(15, TimeUnit.SECONDS).headers()).doesNotContainKey("Set-Cookie");
            assertThat(store.size()).isZero();
        }
    }

    @Test
    void aLoginOnASessionEndedElsewhereStartsANewSession() throws Exception {
        var inside = new CountDownLatch(1);
        var proceed = new CountDownLatch(1);
        var app = Axiom.create();
        app.use(sessions);
        app.post("/seed", ctx -> {
            sessions.session(ctx).attribute("a", "1");
            return "ok";
        });
        app.post("/relogin", ctx -> {
            sessions.session(ctx).authenticate(new SecurityIdentity("ada", Set.of(), Set.of()));
            inside.countDown();
            proceed.await(10, TimeUnit.SECONDS);
            return "ok";
        });
        try (var client = TestClient.start(app)) {
            var id = sessionCookie(client.execute(request("POST", "/seed", null)));
            var login = client.submit(request("POST", "/relogin", COOKIE + "=" + id));
            assertThat(inside.await(10, TimeUnit.SECONDS)).isTrue();
            store.remove(id);
            proceed.countDown();
            var fresh = sessionCookie(login.get(15, TimeUnit.SECONDS));
            assertThat(fresh).isNotEqualTo(id);
            assertThat(store.find(fresh)).isPresent();
            assertThat(store.find(id)).isEmpty();
        }
    }

    @Test
    void usingSessionsWithoutTheMiddlewareFails() throws Exception {
        var app = Axiom.create();
        app.get("/x", ctx -> sessions.session(ctx).toString());
        try (var client = TestClient.start(app)) {
            org.assertj.core.api.Assertions.assertThatThrownBy(() -> client.get("/x")).isInstanceOf(IllegalStateException.class);
        }
    }

    @Test
    void configurationRulesAreEnforcedWhenBuilt() {
        assertThatIllegalArgumentException().isThrownBy(() -> Sessions.builder(store).secure(false).cookieName("__Host-sid").build());
        assertThatIllegalArgumentException().isThrownBy(() -> Sessions.builder(store).domain("example.com").cookieName("__Host-sid").build());
        assertThatIllegalArgumentException().isThrownBy(() -> Sessions.builder(store).path("/app").cookieName("__Host-sid").build());
        assertThatIllegalArgumentException().isThrownBy(() -> Sessions.builder(store).secure(false).sameSite(SetCookie.SameSite.NONE).build());
        assertThatIllegalArgumentException().isThrownBy(() -> Sessions.builder(store).maxCookieHeader(10));
        assertThat(Sessions.builder(store).secure(false).build().toString()).contains("cookie=sid");
        assertThat(Sessions.builder(store).domain("example.com").build().toString()).contains("__Secure-sid");
        assertThat(Sessions.builder(store).build().toString()).contains("__Host-sid");
    }

    @ParameterizedTest
    @ValueSource(strings = {"Lax", "Strict"})
    void developmentConfigurationsIssueTheRequestedAttributes(String sameSite) throws Exception {
        var custom = Sessions.builder(store).secure(false).cookieName("sid").sameSite(SetCookie.SameSite.valueOf(sameSite.toUpperCase()))
                .maxAge(Duration.ofHours(1)).build();
        var app = Axiom.create();
        app.use(custom);
        app.post("/set", ctx -> {
            custom.session(ctx).attribute("n", "1");
            return "ok";
        });
        try (var client = TestClient.start(app)) {
            assertThat(client.execute(request("POST", "/set", null)).headers().get("Set-Cookie"))
                    .matches("sid=[A-Za-z0-9_-]{43}; Path=/; Max-Age=3600; HttpOnly; SameSite=" + sameSite);
        }
    }
}
