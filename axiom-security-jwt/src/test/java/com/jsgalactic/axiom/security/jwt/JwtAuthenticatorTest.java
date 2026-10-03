package com.jsgalactic.axiom.security.jwt;

import static com.jsgalactic.axiom.security.jwt.Tokens.NOW;
import static com.jsgalactic.axiom.security.jwt.Tokens.b64;
import static com.jsgalactic.axiom.security.jwt.Tokens.claims;
import static com.jsgalactic.axiom.security.jwt.Tokens.hs256;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.jsgalactic.axiom.context.SecurityIdentity;
import com.jsgalactic.axiom.error.UnauthorizedException;
import com.jsgalactic.axiom.http.Request;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class JwtAuthenticatorTest {
    private static final String HS256 = "{\"alg\":\"HS256\",\"typ\":\"JWT\"}";

    static JwtAuthenticator.Builder base() {
        return JwtAuthenticator.builder().issuer("https://issuer.test").audience("notes")
                .clock(Clock.fixed(Instant.ofEpochSecond(NOW), ZoneOffset.UTC));
    }

    private static final JwtAuthenticator HMAC = base().hmacKey(JwsAlgorithm.HS256, Tokens.SECRET).build();

    /** Asserts a 401 invalid_token whose server-side reason contains the text. */
    static void rejects(JwtAuthenticator authenticator, String token, String reason) {
        assertThatThrownBy(() -> authenticator.verify(token))
                .isInstanceOfSatisfying(UnauthorizedException.class, failure -> {
                    assertThat(failure.status()).isEqualTo(401);
                    assertThat(failure.code()).isEqualTo("invalid_token");
                    assertThat(failure.headers()).containsEntry("WWW-Authenticate", "Bearer realm=\"api\", error=\"invalid_token\"");
                    if (!token.isEmpty()) { assertThat(failure.getMessage()).doesNotContain(token); }
                })
                .cause().hasMessageContaining(reason);
    }

    @Test
    void acceptsAValidHmacTokenAndMapsTheIdentity() {
        var identity = HMAC.verify(hs256(claims("\"roles\":[\"admin\",\"reader\"],\"scope\":\"notes:read  notes:write\"")));
        assertThat(identity).isEqualTo(new SecurityIdentity("ada", Set.of("admin", "reader"), Set.of("notes:read", "notes:write")));
        assertThat(HMAC.verify(hs256(claims(""))).roles()).isEmpty();
    }

    @Test
    void readsGrantsFromConfiguredClaims() {
        var custom = base().hmacKey(JwsAlgorithm.HS256, Tokens.SECRET).rolesClaim("groups").permissionsClaim("perms").build();
        var identity = custom.verify(hs256(claims("\"groups\":\"ops\",\"perms\":[\"a\",\"b\"],\"roles\":[\"ignored\"]")));
        assertThat(identity.roles()).containsExactly("ops");
        assertThat(identity.permissions()).containsExactlyInAnyOrder("a", "b");
    }

    @Test
    void acceptsRsaAndEcTokensOnTheirOwnCurves() {
        var rsa = base().publicKey(JwsAlgorithm.RS256, Tokens.RSA.getPublic()).build();
        assertThat(rsa.verify(Tokens.sign("{\"alg\":\"RS256\"}", claims(""), Tokens.RSA.getPrivate(), "SHA256withRSA")).principal())
                .isEqualTo("ada");
        var rs512 = base().publicKey(JwsAlgorithm.RS512, Tokens.RSA.getPublic()).build();
        assertThat(rs512.verify(Tokens.sign("{\"alg\":\"RS512\"}", claims(""), Tokens.RSA.getPrivate(), "SHA512withRSA")).principal())
                .isEqualTo("ada");
        var ec = base().publicKey(JwsAlgorithm.ES256, Tokens.EC256.getPublic())
                .publicKey(JwsAlgorithm.ES384, Tokens.EC384.getPublic()).publicKey(JwsAlgorithm.ES512, Tokens.EC521.getPublic()).build();
        assertThat(ec.verify(Tokens.sign("{\"alg\":\"ES256\"}", claims(""), Tokens.EC256.getPrivate(),
                "SHA256withECDSAinP1363Format")).principal()).isEqualTo("ada");
        assertThat(ec.verify(Tokens.sign("{\"alg\":\"ES384\"}", claims(""), Tokens.EC384.getPrivate(),
                "SHA384withECDSAinP1363Format")).principal()).isEqualTo("ada");
        assertThat(ec.verify(Tokens.sign("{\"alg\":\"ES512\"}", claims(""), Tokens.EC521.getPrivate(),
                "SHA512withECDSAinP1363Format")).principal()).isEqualTo("ada");
        // ECDSA signatures in DER instead of the JWS fixed-length format are refused.
        rejects(ec, Tokens.sign("{\"alg\":\"ES256\"}", claims(""), Tokens.EC256.getPrivate(), "SHA256withECDSA"),
                "Signature does not verify");
    }

    @Test
    void selectsKeysByKeyId() {
        var other = "fedcba9876543210fedcba9876543210".getBytes(StandardCharsets.US_ASCII);
        var keyed = base().hmacKey("old", JwsAlgorithm.HS256, Tokens.SECRET).hmacKey("new", JwsAlgorithm.HS256, other).build();
        assertThat(keyed.verify(Tokens.hmac("{\"alg\":\"HS256\",\"kid\":\"new\"}", claims(""), other, "HmacSHA256")).principal())
                .isEqualTo("ada");
        rejects(keyed, Tokens.hmac("{\"alg\":\"HS256\",\"kid\":\"old\"}", claims(""), other, "HmacSHA256"), "Signature");
        rejects(keyed, Tokens.hmac("{\"alg\":\"HS256\",\"kid\":\"gone\"}", claims(""), other, "HmacSHA256"), "No key");
        rejects(keyed, hs256(claims("")), "No key"); // no kid and no key without one
        rejects(keyed, Tokens.hmac("{\"alg\":\"HS256\",\"kid\":7}", claims(""), other, "HmacSHA256"), "kid is not a string");
    }

    @Test
    void rejectsForgedTokens() {
        var token = hs256(claims(""));
        var parts = token.split("\\.");
        var tampered = parts[0] + "." + b64(claims("\"roles\":[\"admin\"]")) + "." + parts[2];
        rejects(HMAC, tampered, "Signature does not verify");
        var wrongKey = Tokens.hmac(HS256, claims(""), "ffffffffffffffffffffffffffffffff".getBytes(StandardCharsets.US_ASCII), "HmacSHA256");
        rejects(HMAC, wrongKey, "Signature does not verify");
        rejects(HMAC, parts[0] + "." + parts[1] + "." + parts[2].substring(0, 20), "Signature does not verify");
        var rsa = base().publicKey(JwsAlgorithm.RS256, Tokens.RSA.getPublic()).build();
        var otherRsa = Tokens.generate("RSA", 2048, null);
        rejects(rsa, Tokens.sign("{\"alg\":\"RS256\"}", claims(""), otherRsa.getPrivate(), "SHA256withRSA"), "Signature");
    }

    @ParameterizedTest
    @ValueSource(strings = {"none", "None", "NONE", "HS512", "RS256", "ES256", "PS256", "EdDSA", "hs256", ""})
    void rejectsAlgorithmsOutsideTheAllowList(String algorithm) {
        var header = "{\"alg\":\"" + algorithm + "\"}";
        rejects(HMAC, b64(header) + "." + b64(claims("")) + ".", "Algorithm is not allowed"); // unsigned
        rejects(HMAC, Tokens.hmac(header, claims(""), Tokens.SECRET, "HmacSHA256"), "Algorithm is not allowed");
    }

    @Test
    void rejectsAlgorithmConfusionBetweenPublicKeysAndHmac() {
        // Classic attack: sign HS256 with the RSA public key's bytes as the secret.
        var publicBytes = Tokens.RSA.getPublic().getEncoded();
        var rsaOnly = base().publicKey(JwsAlgorithm.RS256, Tokens.RSA.getPublic()).build();
        rejects(rsaOnly, Tokens.hmac(HS256, claims(""), publicBytes, "HmacSHA256"), "Algorithm is not allowed");
        // With an HMAC key configured too, a kid pointing at the RSA key still cannot select HMAC.
        var mixed = base().publicKey("rsa", JwsAlgorithm.RS256, Tokens.RSA.getPublic())
                .hmacKey(JwsAlgorithm.HS256, Tokens.SECRET).build();
        rejects(mixed, Tokens.hmac("{\"alg\":\"HS256\",\"kid\":\"rsa\"}", claims(""), publicBytes, "HmacSHA256"),
                "registered for another algorithm");
        // And an RS256 header cannot reach the key-less HMAC secret.
        rejects(mixed, Tokens.sign("{\"alg\":\"RS256\"}", claims(""), Tokens.RSA.getPrivate(), "SHA256withRSA"), "No key");
    }

    @Test
    void enforcesTimeClaimsWithBoundedSkew() {
        rejects(HMAC, hs256(claims("").replace("\"exp\":" + (NOW + 300), "\"exp\":" + (NOW - 30))), "expired");
        assertThat(HMAC.verify(hs256(claims("").replace("\"exp\":" + (NOW + 300), "\"exp\":" + (NOW - 29)))).principal())
                .isEqualTo("ada"); // inside the 30-second skew
        var strict = base().hmacKey(JwsAlgorithm.HS256, Tokens.SECRET).clockSkew(Duration.ZERO).build();
        rejects(strict, hs256(claims("").replace("\"exp\":" + (NOW + 300), "\"exp\":" + NOW)), "expired");
        rejects(HMAC, hs256(claims("\"nbf\":" + (NOW + 31))), "not yet valid");
        assertThat(HMAC.verify(hs256(claims("\"nbf\":" + (NOW + 30)))).principal()).isEqualTo("ada");
        rejects(HMAC, hs256(claims("").replace("\"iat\":" + (NOW - 10), "\"iat\":" + (NOW + 31))), "issued in the future");
        rejects(HMAC, hs256(claims("").replace(",\"exp\":" + (NOW + 300), "")), "Missing exp");
        rejects(HMAC, hs256(claims("").replace("\"exp\":" + (NOW + 300), "\"exp\":\"" + (NOW + 300) + "\"")), "exp is not");
        rejects(HMAC, hs256(claims("").replace("\"exp\":" + (NOW + 300), "\"exp\":" + (NOW + 300) + ".5")), "exp is not");
        rejects(HMAC, hs256(claims("").replace("\"exp\":" + (NOW + 300), "\"exp\":1e30")), "exp is not");
        rejects(HMAC, hs256(claims("\"nbf\":-1")), "nbf is not");
    }

    @Test
    void enforcesIssuerAudienceAndSubject() {
        rejects(HMAC, hs256(claims("").replace("https://issuer.test", "https://evil.test")), "Issuer");
        rejects(HMAC, hs256(claims("").replace("\"iss\":\"https://issuer.test\",", "")), "Issuer");
        rejects(HMAC, hs256(claims("").replace("\"aud\":\"notes\"", "\"aud\":\"billing\"")), "Audience");
        rejects(HMAC, hs256(claims("").replace("\"aud\":\"notes\",", "")), "Audience");
        rejects(HMAC, hs256(claims("").replace("\"aud\":\"notes\"", "\"aud\":[\"notes\",7]")), "Audience");
        assertThat(HMAC.verify(hs256(claims("").replace("\"aud\":\"notes\"", "\"aud\":[\"billing\",\"notes\"]"))).principal())
                .isEqualTo("ada");
        rejects(HMAC, hs256(claims("").replace("\"sub\":\"ada\",", "")), "subject");
        rejects(HMAC, hs256(claims("").replace("\"sub\":\"ada\"", "\"sub\":\"\"")), "subject");
        rejects(HMAC, hs256(claims("").replace("\"sub\":\"ada\"", "\"sub\":\"a\\u0000b\"")), "usable names");
        rejects(HMAC, hs256(claims("\"roles\":{\"a\":1}")), "roles is neither");
        rejects(HMAC, hs256(claims("\"roles\":[1]")), "non-string");
        var many = new StringBuilder("\"scope\":\"");
        for (int i = 0; i < 257; i++) { many.append('p').append(i).append(' '); }
        rejects(HMAC, hs256(claims(many.append('"').toString())), "more than 256");
    }

    @Test
    void rejectsMalformedTokens() {
        var token = hs256(claims(""));
        var parts = token.split("\\.");
        rejects(HMAC, "", "Missing token");
        rejects(HMAC, parts[0] + "." + parts[1], "three-part");
        rejects(HMAC, token + ".x.y", "three-part");                // JWE compact serialization has five parts
        rejects(HMAC, "." + parts[1] + "." + parts[2], "header is not");
        rejects(HMAC, parts[0] + ".." + parts[2], "Signature"); // payload is covered by the signature first
        rejects(HMAC, parts[0] + "=." + parts[1] + "." + parts[2], "header is not");
        rejects(HMAC, parts[0].replace('-', '+') + "x." + parts[1] + "." + parts[2], "header is not");
        rejects(HMAC, "eyJhbGciOiJIUzI1NiJ9" + "B" + "." + parts[1] + "." + parts[2], "header is not"); // length 4n+1
        // Non-canonical base64url: the last character carries non-zero unused bits.
        var header = b64("{\"alg\":\"HS256\" }"); // 16 bytes: the last character has four unused bits
        var alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_";
        var last = alphabet.indexOf(header.charAt(header.length() - 1));
        var flipped = header.substring(0, header.length() - 1) + alphabet.charAt(last | 1);
        rejects(HMAC, flipped + "." + parts[1] + "." + parts[2], "canonical");
        rejects(HMAC, Tokens.hmac("{\"alg\":\"HS256\"", claims(""), Tokens.SECRET, "HmacSHA256"), "Unexpected end");
        rejects(HMAC, Tokens.hmac("[\"HS256\"]", claims(""), Tokens.SECRET, "HmacSHA256"), "not an object");
        rejects(HMAC, Tokens.hmac(HS256, "{\"sub\":\"ada\"} x", Tokens.SECRET, "HmacSHA256"), "Trailing");
        rejects(HMAC, Tokens.hmac(HS256, claims("\"x\":tru"), Tokens.SECRET, "HmacSHA256"), "literal");
        rejects(HMAC, Tokens.hmac(HS256, claims("\"x\":\"\\ud800\""), Tokens.SECRET, "HmacSHA256"), "surrogate");
        rejects(HMAC, Tokens.hmac(HS256, claims("\"x\":01"), Tokens.SECRET, "HmacSHA256"), "Malformed JSON");
        rejects(HMAC, Tokens.hmac(HS256, claims("\"x\":" + "[".repeat(40) + "]".repeat(40)), Tokens.SECRET, "HmacSHA256"),
                "nested too deeply");
        var invalidUtf8 = b64(new byte[] {'{', '"', 'a', '"', ':', '"', (byte) 0xC3, '"', '}'});
        rejects(HMAC, invalidUtf8 + "." + parts[1] + "." + parts[2], "UTF-8");
    }

    @Test
    void rejectsDuplicateMembersSoNoParserDifferentialCanBeExploited() {
        rejects(HMAC, Tokens.hmac("{\"alg\":\"HS256\",\"alg\":\"none\"}", claims(""), Tokens.SECRET, "HmacSHA256"), "Duplicate");
        rejects(HMAC, Tokens.hmac(HS256, claims("\"sub\":\"root\""), Tokens.SECRET, "HmacSHA256"), "Duplicate");
    }

    @Test
    void rejectsUnsupportedHeaderParameters() {
        rejects(HMAC, Tokens.hmac("{\"alg\":\"HS256\",\"crit\":[\"exp\"],\"exp\":1}", claims(""), Tokens.SECRET, "HmacSHA256"),
                "Critical");
        rejects(HMAC, Tokens.hmac("{\"alg\":\"HS256\",\"enc\":\"A256GCM\"}", claims(""), Tokens.SECRET, "HmacSHA256"), "Encrypted");
        rejects(HMAC, Tokens.hmac("{\"alg\":\"HS256\",\"typ\":\"JOSE+JSON\"}", claims(""), Tokens.SECRET, "HmacSHA256"), "typ");
        rejects(HMAC, Tokens.hmac("{\"alg\":\"HS256\",\"typ\":1}", claims(""), Tokens.SECRET, "HmacSHA256"), "typ");
        rejects(HMAC, Tokens.hmac("{\"typ\":\"JWT\"}", claims(""), Tokens.SECRET, "HmacSHA256"), "no alg");
        rejects(HMAC, Tokens.hmac("{\"alg\":[\"HS256\"]}", claims(""), Tokens.SECRET, "HmacSHA256"), "no alg");
        assertThat(HMAC.verify(Tokens.hmac("{\"alg\":\"HS256\",\"typ\":\"at+jwt\"}", claims(""), Tokens.SECRET, "HmacSHA256"))
                .principal()).isEqualTo("ada");
        // An embedded key is never trusted, even when the token is signed with it.
        var attacker = Tokens.generate("RSA", 2048, null);
        var rsa = base().publicKey(JwsAlgorithm.RS256, Tokens.RSA.getPublic()).build();
        rejects(rsa, Tokens.sign("{\"alg\":\"RS256\",\"jwk\":{\"kty\":\"RSA\"}}", claims(""), attacker.getPrivate(),
                "SHA256withRSA"), "Signature");
    }

    @Test
    void rejectsOversizedTokensBeforeDecoding() {
        var small = base().hmacKey(JwsAlgorithm.HS256, Tokens.SECRET).maxTokenLength(256).build();
        var big = hs256(claims("\"pad\":\"" + "x".repeat(300) + "\""));
        rejects(small, big, "longer than 256");
        rejects(HMAC, "!".repeat(8193), "longer than 8192"); // garbage is never parsed
        assertThat(HMAC.verify(big).principal()).isEqualTo("ada");
    }

    @Test
    void readsBearerCredentialsOnly() {
        var token = hs256(claims(""));
        assertThat(HMAC.authenticate(Request.get("/"))).isEmpty();
        assertThat(HMAC.authenticate(with("Basic YWRhOnB3"))).isEmpty();
        assertThat(HMAC.authenticate(with("Bearer " + token))).map(SecurityIdentity::principal).contains("ada");
        assertThat(HMAC.authenticate(with("bearer   " + token + " "))).map(SecurityIdentity::principal).contains("ada");
        assertThatThrownBy(() -> HMAC.authenticate(with("Bearer"))).isInstanceOf(UnauthorizedException.class);
        assertThatThrownBy(() -> HMAC.authenticate(with("Bearer " + token + ", Bearer " + token)))
                .isInstanceOf(UnauthorizedException.class);
        assertThat(HMAC.challenge()).isEqualTo("Bearer realm=\"api\"");
        assertThat(base().hmacKey(JwsAlgorithm.HS256, Tokens.SECRET).realm("notes api").build().challenge())
                .isEqualTo("Bearer realm=\"notes api\"");
    }

    private static Request with(String authorization) {
        return Request.get("/").withHeaders(Map.of("Authorization", authorization));
    }
}
