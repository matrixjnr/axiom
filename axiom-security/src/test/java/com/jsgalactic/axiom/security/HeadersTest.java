package com.jsgalactic.axiom.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import com.jsgalactic.axiom.Axiom;
import com.jsgalactic.axiom.error.ForbiddenException;
import com.jsgalactic.axiom.test.TestClient;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Header redaction and the security headers middleware. */
class HeadersTest {
    @Test
    void redactsCredentialHeadersCaseInsensitivelyAndKeepsOthers() {
        var headers = Map.of("authorization", "Bearer secret", "COOKIE", "session=abc", "Set-Cookie", "id=1",
                "X-Api-Key", "k", "Accept", "application/json", "X-Tenant-Secret", "t");
        var redacted = HeaderRedaction.defaults().and("x-tenant-secret").redact(headers);
        assertThat(redacted).containsEntry("Authorization", HeaderRedaction.REDACTED)
                .containsEntry("Cookie", HeaderRedaction.REDACTED).containsEntry("set-cookie", HeaderRedaction.REDACTED)
                .containsEntry("X-Api-Key", HeaderRedaction.REDACTED).containsEntry("X-Tenant-Secret", HeaderRedaction.REDACTED)
                .containsEntry("Accept", "application/json");
        assertThat(redacted.toString()).doesNotContain("secret", "abc");
        assertThat(HeaderRedaction.defaults().isSensitive("Proxy-Authorization")).isTrue();
        assertThat(HeaderRedaction.defaults().isSensitive("X-Tenant-Secret")).isFalse();
        assertThatIllegalArgumentException().isThrownBy(() -> HeaderRedaction.defaults().and("bad name"));
    }

    @Test
    void addsDefaultHeadersWithoutOverridingTheHandlersOwn() throws Exception {
        var app = Axiom.create();
        app.use(SecurityHeaders.defaults());
        app.get("/api", ctx -> "api");
        app.get("/page", ctx -> ctx.text("<p>").withHeader("Content-Security-Policy", "default-src 'self'"));
        try (var client = TestClient.start(app)) {
            var api = client.get("/api");
            assertThat(api.headers()).containsEntry("X-Content-Type-Options", "nosniff")
                    .containsEntry("X-Frame-Options", "DENY").containsEntry("Referrer-Policy", "no-referrer")
                    .containsEntry("Content-Security-Policy", "default-src 'none'; frame-ancestors 'none'")
                    .containsEntry("Cross-Origin-Resource-Policy", "same-origin")
                    .doesNotContainKey("Strict-Transport-Security");
            assertThat(client.get("/page").headers()).containsEntry("Content-Security-Policy", "default-src 'self'");
            // Global middleware wrap the router's own answers.
            assertThat(client.get("/missing").headers()).containsEntry("X-Content-Type-Options", "nosniff");
        }
    }

    @Test
    void doesNotDecorateResponsesMappedFromExceptions() throws Exception {
        var app = Axiom.create();
        app.use(SecurityHeaders.defaults());
        app.get("/denied", ctx -> { throw new ForbiddenException(); });
        try (var client = TestClient.start(app)) {
            var denied = client.get("/denied");
            assertThat(denied.status()).isEqualTo(403);
            assertThat(denied.headers()).doesNotContainKey("X-Content-Type-Options"); // documented limitation
        }
    }

    @Test
    void configuresHeadersImmutably() {
        var hsts = SecurityHeaders.defaults().with("Strict-Transport-Security", "max-age=31536000");
        assertThat(hsts.headers()).containsKey("strict-transport-security");
        assertThat(SecurityHeaders.defaults().headers()).doesNotContainKey("Strict-Transport-Security");
        assertThat(hsts.without("x-frame-options").headers()).doesNotContainKey("X-Frame-Options");
        assertThatIllegalArgumentException().isThrownBy(() -> SecurityHeaders.defaults().with("X-A", "a\r\nSet-Cookie: b"));
        assertThatIllegalArgumentException().isThrownBy(() -> SecurityHeaders.defaults().with("X A", "a"));
    }
}
