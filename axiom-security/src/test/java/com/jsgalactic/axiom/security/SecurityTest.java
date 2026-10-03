package com.jsgalactic.axiom.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

import com.jsgalactic.axiom.Axiom;
import com.jsgalactic.axiom.application.Application;
import com.jsgalactic.axiom.context.SecurityIdentity;
import com.jsgalactic.axiom.error.UnauthorizedException;
import com.jsgalactic.axiom.http.Request;
import com.jsgalactic.axiom.http.Response;
import com.jsgalactic.axiom.test.TestClient;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/** Policies through the real dispatcher and problem mapping. */
class SecurityTest {
    /** Accepts {@code Token <user>}; {@code Token bad} is an invalid credential. */
    static final class TokenAuthenticator implements Authenticator {
        final AtomicInteger calls = new AtomicInteger();

        @Override
        public Optional<SecurityIdentity> authenticate(Request request) {
            calls.incrementAndGet();
            var header = request.header("Authorization");
            if (header.isEmpty() || !header.get().startsWith("Token ")) { return Optional.empty(); }
            var user = header.get().substring(6);
            return switch (user) {
                case "bad" -> throw new UnauthorizedException("Token realm=\"test\", error=\"invalid_token\"", "invalid_token");
                case "admin" -> Optional.of(new SecurityIdentity("admin", Set.of("admin"), Set.of("notes:delete")));
                default -> Optional.of(new SecurityIdentity(user, Set.of("reader"), Set.of("notes:read")));
            };
        }

        @Override
        public String challenge() { return "Token realm=\"test\""; }
    }

    private final TokenAuthenticator authenticator = new TokenAuthenticator();
    private final Security security = Security.of(authenticator);

    private Application application() {
        var app = Axiom.create();
        app.use(security.authenticate());
        app.get("/public", ctx -> ctx.identity().map(SecurityIdentity::principal).orElse("anonymous"));
        app.get("/me", ctx -> ctx.identity().orElseThrow().principal(), security.authenticated());
        app.group("/admin", admin -> {
            admin.use(security.hasRole("admin"));
            admin.delete("/notes/:id", ctx -> null, security.hasPermission("notes:delete"));
        });
        app.get("/notes", ctx -> "notes", security.hasPermission("notes:read"));
        app.get("/staff", ctx -> "staff", security.hasAnyRole("admin", "editor"));
        return app;
    }

    private static Request get(String path, String authorization) {
        var request = Request.fromTarget("GET", path);
        return authorization == null ? request : request.withHeaders(Map.of("Authorization", authorization));
    }

    private static String body(Response response) {
        return response.body() instanceof byte[] bytes ? new String(bytes, StandardCharsets.UTF_8) : String.valueOf(response.body());
    }

    @Test
    void optionalAuthenticationKeepsPublicRoutesOpenAndExposesTheIdentity() throws Exception {
        try (var client = TestClient.start(application())) {
            assertThat(client.get("/public").body()).isEqualTo("anonymous");
            assertThat(client.execute(get("/public", "Token ada")).body()).isEqualTo("ada");
            // A scheme the authenticator does not handle is no credential at all.
            assertThat(client.execute(get("/public", "Basic YWRhOnB3")).body()).isEqualTo("anonymous");
        }
    }

    @Test
    void missingCredentialsAre401WithTheChallenge() throws Exception {
        try (var client = TestClient.start(application())) {
            var response = client.get("/me");
            assertThat(response.status()).isEqualTo(401);
            assertThat(response.headers()).containsEntry("Content-Type", "application/problem+json")
                    .containsEntry("WWW-Authenticate", "Token realm=\"test\"");
            assertThat(body(response)).contains("\"code\":\"unauthorized\"");
            assertThat(client.execute(get("/me", "Token ada")).body()).isEqualTo("ada");
        }
    }

    @Test
    void invalidCredentialsAre401EvenOnPublicRoutesAndNeverEchoed() throws Exception {
        try (var client = TestClient.start(application())) {
            for (var path : new String[] {"/public", "/me", "/admin/notes/1"}) {
                var response = client.execute(get(path, "Token bad"));
                assertThat(response.status()).isEqualTo(401);
                assertThat(response.headers()).containsEntry("WWW-Authenticate", "Token realm=\"test\", error=\"invalid_token\"");
                assertThat(body(response)).contains("invalid_token").doesNotContain("Token bad");
            }
        }
    }

    @Test
    void lackingARoleOrPermissionIs403WithoutNamingIt() throws Exception {
        try (var client = TestClient.start(application())) {
            assertThat(client.execute(Request.fromTarget("DELETE", "/admin/notes/1")).status()).isEqualTo(401);
            var forbidden = client.execute(Request.fromTarget("DELETE", "/admin/notes/1")
                    .withHeaders(Map.of("Authorization", "Token ada")));
            assertThat(forbidden.status()).isEqualTo(403);
            assertThat(forbidden.headers()).doesNotContainKey("WWW-Authenticate");
            assertThat(body(forbidden)).contains("\"code\":\"forbidden\"").doesNotContain("admin");
            assertThat(client.execute(Request.fromTarget("DELETE", "/admin/notes/1")
                    .withHeaders(Map.of("Authorization", "Token admin"))).status()).isEqualTo(204);

            assertThat(client.get("/notes").status()).isEqualTo(401);
            assertThat(client.execute(get("/notes", "Token admin")).status()).isEqualTo(403);
            assertThat(client.execute(get("/notes", "Token ada")).body()).isEqualTo("notes");

            assertThat(client.get("/staff").status()).isEqualTo(401);
            assertThat(client.execute(get("/staff", "Token ada")).status()).isEqualTo(403);
            assertThat(client.execute(get("/staff", "Token admin")).body()).isEqualTo("staff");
        }
    }

    @Test
    void stackedPoliciesAuthenticateOncePerRequest() throws Exception {
        try (var client = TestClient.start(application())) {
            authenticator.calls.set(0);
            client.execute(Request.fromTarget("DELETE", "/admin/notes/1").withHeaders(Map.of("Authorization", "Token admin")));
            assertThat(authenticator.calls).hasValue(1);
        }
    }

    @Test
    void policiesWorkWithoutAGlobalAuthenticateStep() throws Exception {
        var app = Axiom.create();
        app.get("/me", ctx -> ctx.identity().orElseThrow().principal(), security.authenticated());
        try (var client = TestClient.start(app)) {
            assertThat(client.get("/me").status()).isEqualTo(401);
            assertThat(client.execute(get("/me", "Token ada")).body()).isEqualTo("ada");
        }
    }

    @Test
    void anAuthenticatorReturningNullFailsTheRequest() throws Exception {
        var broken = Security.of(new Authenticator() {
            @Override public Optional<SecurityIdentity> authenticate(Request request) { return null; }
            @Override public String challenge() { return "Token"; }
        });
        var app = Axiom.create();
        app.get("/me", ctx -> "me", broken.authenticated());
        try (var client = TestClient.start(app)) {
            assertThatIllegalStateException().isThrownBy(() -> client.get("/me")).withMessageContaining("null");
        }
    }

    @Test
    void rejectsUnsafeConfigurationWhenPoliciesAreCreated() {
        assertThatIllegalArgumentException().isThrownBy(() -> Security.of(new Authenticator() {
            @Override public Optional<SecurityIdentity> authenticate(Request request) { return Optional.empty(); }
            @Override public String challenge() { return "Bearer\r\nSet-Cookie: x=y"; }
        }));
        assertThatIllegalArgumentException().isThrownBy(() -> security.hasRole(""));
        assertThatIllegalArgumentException().isThrownBy(() -> security.hasPermission("a\nb"));
        assertThatIllegalArgumentException().isThrownBy(security::hasAnyRole);
    }
}
