package com.jsgalactic.axiom.security.jwt;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

import com.jsgalactic.axiom.Axiom;
import com.jsgalactic.axiom.http.Request;
import com.jsgalactic.axiom.security.Security;
import com.jsgalactic.axiom.test.TestClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.Test;

class JwtConfigurationTest {
    @Test
    void refusesWeakOrMismatchedKeys() {
        var builder = JwtAuthenticator.builder();
        assertThatIllegalArgumentException().isThrownBy(() -> builder.hmacKey(JwsAlgorithm.HS256, new byte[31]));
        assertThatIllegalArgumentException().isThrownBy(() -> builder.hmacKey(JwsAlgorithm.HS512, new byte[63]));
        assertThatIllegalArgumentException().isThrownBy(() -> builder.hmacKey(JwsAlgorithm.RS256, new byte[64]));
        assertThatIllegalArgumentException().isThrownBy(() -> builder.publicKey(JwsAlgorithm.HS256, Tokens.RSA.getPublic()));
        assertThatIllegalArgumentException().isThrownBy(() -> builder.publicKey(JwsAlgorithm.RS256, Tokens.EC256.getPublic()));
        assertThatIllegalArgumentException().isThrownBy(() -> builder.publicKey(JwsAlgorithm.ES256, Tokens.RSA.getPublic()));
        assertThatIllegalArgumentException().isThrownBy(() -> builder.publicKey(JwsAlgorithm.ES256, Tokens.EC384.getPublic()));
        assertThatIllegalArgumentException().isThrownBy(() -> builder.publicKey(JwsAlgorithm.ES512, Tokens.EC256.getPublic()));
        var weakRsa = Tokens.generate("RSA", 1024, null);
        assertThatIllegalArgumentException().isThrownBy(() -> builder.publicKey(JwsAlgorithm.RS256, weakRsa.getPublic()));
    }

    @Test
    void refusesAmbiguousKeysAndIncompleteConfiguration() {
        var builder = JwtAuthenticator.builder().hmacKey(JwsAlgorithm.HS256, Tokens.SECRET).hmacKey("a", JwsAlgorithm.HS256, Tokens.SECRET);
        assertThatIllegalArgumentException().isThrownBy(() -> builder.hmacKey(JwsAlgorithm.HS256, Tokens.SECRET));
        assertThatIllegalArgumentException().isThrownBy(() -> builder.publicKey("a", JwsAlgorithm.RS256, Tokens.RSA.getPublic()));
        assertThatIllegalStateException().isThrownBy(builder::build).withMessageContaining("issuer");
        assertThatIllegalStateException().isThrownBy(() -> builder.issuer("i").build()).withMessageContaining("audience");
        assertThatIllegalStateException().isThrownBy(() -> JwtAuthenticator.builder().issuer("i").audience("a").build())
                .withMessageContaining("key");
        assertThatIllegalArgumentException().isThrownBy(() -> builder.issuer(""));
    }

    @Test
    void boundsSkewTokenLengthAndRealm() {
        var builder = JwtAuthenticator.builder();
        assertThatIllegalArgumentException().isThrownBy(() -> builder.clockSkew(Duration.ofSeconds(-1)));
        assertThatIllegalArgumentException().isThrownBy(() -> builder.clockSkew(Duration.ofMinutes(5).plusSeconds(1)));
        assertThatIllegalArgumentException().isThrownBy(() -> builder.clockSkew(Duration.ofMillis(1500)));
        builder.clockSkew(Duration.ofMinutes(5));
        assertThatIllegalArgumentException().isThrownBy(() -> builder.maxTokenLength(255));
        assertThatIllegalArgumentException().isThrownBy(() -> builder.maxTokenLength(65_537));
        assertThatIllegalArgumentException().isThrownBy(() -> builder.realm("a\"b"));
        assertThatIllegalArgumentException().isThrownBy(() -> builder.realm(""));
    }

    @Test
    void answersInvalidTokensWith401ProblemJsonEndToEnd() throws Exception {
        var jwt = JwtAuthenticatorTest.base().hmacKey(JwsAlgorithm.HS256, Tokens.SECRET).build();
        var security = Security.of(jwt);
        var app = Axiom.create();
        app.get("/me", ctx -> ctx.identity().orElseThrow().principal(), security.authenticated());
        app.get("/admin", ctx -> "admin", security.hasRole("admin"));
        try (var client = TestClient.start(app)) {
            var missing = client.get("/me");
            assertThat(missing.status()).isEqualTo(401);
            assertThat(missing.headers()).containsEntry("WWW-Authenticate", "Bearer realm=\"api\"");

            var forged = "eyJhbGciOiJub25lIn0." + Tokens.b64(Tokens.claims("")) + ".";
            var rejected = client.execute(Request.get("/me").withHeaders(Map.of("Authorization", "Bearer " + forged)));
            assertThat(rejected.status()).isEqualTo(401);
            assertThat(rejected.headers()).containsEntry("WWW-Authenticate", "Bearer realm=\"api\", error=\"invalid_token\"")
                    .containsEntry("Content-Type", "application/problem+json");
            assertThat(new String((byte[]) rejected.body(), StandardCharsets.UTF_8))
                    .contains("\"code\":\"invalid_token\"").doesNotContain("eyJ", "none", "ada");

            var token = Tokens.hs256(Tokens.claims(""));
            var ok = client.execute(Request.get("/me").withHeaders(Map.of("Authorization", "Bearer " + token)));
            assertThat(ok.body()).isEqualTo("ada");
            var forbidden = client.execute(Request.get("/admin").withHeaders(Map.of("Authorization", "Bearer " + token)));
            assertThat(forbidden.status()).isEqualTo(403);
        }
    }
}
