package com.jsgalactic.axiom.security.jwt;

import static com.jsgalactic.axiom.security.jwt.Jwks.bytes;
import static com.jsgalactic.axiom.security.jwt.Jwks.set;
import static com.jsgalactic.axiom.security.jwt.JwtAuthenticatorTest.rejects;
import static com.jsgalactic.axiom.security.jwt.Tokens.NOW;
import static com.jsgalactic.axiom.security.jwt.Tokens.claims;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

import com.jsgalactic.axiom.error.UnauthorizedException;
import java.io.IOException;
import java.security.KeyPair;
import java.security.interfaces.ECPublicKey;
import java.security.interfaces.EdECPublicKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.MGF1ParameterSpec;
import java.time.Duration;
import java.util.ArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/** Key sets through an injected source: parsing, binding, caching, rotation and failure handling. */
class JwksTest {
    private static final KeyPair OTHER_RSA = Tokens.generate("RSA", 2048, null);

    /** A source whose document and failures the test controls, counting fetches. */
    private static final class Source implements JwksSource {
        final AtomicInteger fetches = new AtomicInteger();
        volatile byte[] document;
        volatile IOException failure;

        Source(String json) {
            document = bytes(json);
        }

        @Override
        public byte[] fetch(int maxBytes) throws IOException {
            fetches.incrementAndGet();
            if (failure != null) { throw failure; }
            return document;
        }
    }

    private final Jwks.TestClock clock = new Jwks.TestClock(NOW);

    private JwtAuthenticator.Builder builder() {
        return JwtAuthenticator.builder().issuer("https://issuer.test").audience("notes").clock(clock);
    }

    private static String rsaJwk(String kid, String alg) {
        return Jwks.rsa(kid, alg, (RSAPublicKey) Tokens.RSA.getPublic());
    }

    /** Valid for a day, so tests can move the clock. */
    private static String longLived() {
        return claims("").replace("\"exp\":" + (NOW + 300), "\"exp\":" + (NOW + 86_400));
    }

    private static String rs256(String kid) {
        return Tokens.sign("{\"alg\":\"RS256\",\"kid\":\"" + kid + "\"}", longLived(), Tokens.RSA.getPrivate(), "SHA256withRSA");
    }

    @Test
    void verifiesTokensWithKeysOfEveryTypeFromTheSet() {
        var source = new Source(set(rsaJwk("rs", "RS256"), Jwks.rsa("ps", "PS256", (RSAPublicKey) Tokens.RSA.getPublic()),
                Jwks.ec("es256", "ES256", "P-256", 32, (ECPublicKey) Tokens.EC256.getPublic()),
                Jwks.ec("es384", "ES384", "P-384", 48, (ECPublicKey) Tokens.EC384.getPublic()),
                Jwks.ec("es512", "ES512", "P-521", 66, (ECPublicKey) Tokens.EC521.getPublic()),
                Jwks.ed25519("ed", "EdDSA", (EdECPublicKey) Tokens.ED25519.getPublic())));
        var jwt = builder().jwks(source).build();
        assertThat(source.fetches).hasValue(0); // lazy: nothing is fetched at build time
        assertThat(jwt.verify(rs256("rs")).principal()).isEqualTo("ada");
        assertThat(jwt.verify(Tokens.signPss("{\"alg\":\"PS256\",\"kid\":\"ps\"}", claims(""), Tokens.RSA.getPrivate(), "SHA-256",
                MGF1ParameterSpec.SHA256, 32)).principal()).isEqualTo("ada");
        assertThat(jwt.verify(Tokens.sign("{\"alg\":\"ES256\",\"kid\":\"es256\"}", claims(""), Tokens.EC256.getPrivate(),
                "SHA256withECDSAinP1363Format")).principal()).isEqualTo("ada");
        assertThat(jwt.verify(Tokens.sign("{\"alg\":\"ES384\",\"kid\":\"es384\"}", claims(""), Tokens.EC384.getPrivate(),
                "SHA384withECDSAinP1363Format")).principal()).isEqualTo("ada");
        assertThat(jwt.verify(Tokens.sign("{\"alg\":\"ES512\",\"kid\":\"es512\"}", claims(""), Tokens.EC521.getPrivate(),
                "SHA512withECDSAinP1363Format")).principal()).isEqualTo("ada");
        assertThat(jwt.verify(Tokens.sign("{\"alg\":\"EdDSA\",\"kid\":\"ed\"}", claims(""), Tokens.ED25519.getPrivate(), "Ed25519"))
                .principal()).isEqualTo("ada");
        assertThat(source.fetches).hasValue(1); // one fetch served them all
    }

    @Test
    void bindsEveryKeyToItsDeclaredAlgorithm() {
        var source = new Source(set(rsaJwk("rs", "RS256"), Jwks.rsa("ps", "PS256", (RSAPublicKey) Tokens.RSA.getPublic())));
        var jwt = builder().jwks(source).build();
        // The same RSA key under two key IDs: each only for its own algorithm.
        rejects(jwt, Tokens.sign("{\"alg\":\"RS256\",\"kid\":\"ps\"}", claims(""), Tokens.RSA.getPrivate(), "SHA256withRSA"),
                "registered for another algorithm");
        rejects(jwt, Tokens.signPss("{\"alg\":\"PS256\",\"kid\":\"rs\"}", claims(""), Tokens.RSA.getPrivate(), "SHA-256",
                MGF1ParameterSpec.SHA256, 32), "registered for another algorithm");
        // Algorithm confusion with HMAC: the RSA key bytes as an HMAC secret, under the RSA key's kid.
        var secret = Tokens.RSA.getPublic().getEncoded();
        rejects(jwt, Tokens.hmac("{\"alg\":\"HS256\",\"kid\":\"rs\"}", claims(""), secret, "HmacSHA256"), "registered for another algorithm");
        rejects(jwt, Tokens.hmac("{\"alg\":\"HS256\"}", claims(""), secret, "HmacSHA256"), "Algorithm is not allowed");
        // A key of another curve or type under an ES or EdDSA header.
        rejects(jwt, Tokens.sign("{\"alg\":\"EdDSA\",\"kid\":\"rs\"}", claims(""), Tokens.ED25519.getPrivate(), "Ed25519"),
                "registered for another algorithm");
        rejects(jwt, rs256("unknown"), "No key");
        rejects(jwt, Tokens.sign("{\"alg\":\"RS256\"}", claims(""), Tokens.RSA.getPrivate(), "SHA256withRSA"), "Algorithm is not allowed");
    }

    @Test
    void tokensWithoutAKidAreNotVerifiedWithKeySetKeys() {
        var jwt = builder().jwks(new Source(set(rsaJwk("rs", "RS256")))).build();
        rejects(jwt, Tokens.sign("{\"alg\":\"RS256\"}", claims(""), Tokens.RSA.getPrivate(), "SHA256withRSA"), "Algorithm is not allowed");
        // With a static key for the algorithm, the missing kid selects that key, never a key set key.
        var mixed = builder().publicKey(JwsAlgorithm.RS256, OTHER_RSA.getPublic()).jwks(new Source(set(rsaJwk("rs", "RS256")))).build();
        rejects(mixed, Tokens.sign("{\"alg\":\"RS256\"}", claims(""), Tokens.RSA.getPrivate(), "SHA256withRSA"), "Signature");
    }

    @Test
    void staticKeysWinOverKeySetKeysWithTheSameKeyId() {
        var jwt = builder().publicKey("rs", JwsAlgorithm.RS256, OTHER_RSA.getPublic()).jwks(new Source(set(rsaJwk("rs", "RS256")))).build();
        rejects(jwt, rs256("rs"), "Signature"); // the key set's "rs" cannot shadow the configured one
    }

    @Test
    void skipsKeysThatAreNotAcceptableVerificationKeysButKeepsTheRest() {
        var weak = Tokens.generate("RSA", 1024, null);
        var ed448 = "{\"kty\":\"OKP\",\"kid\":\"ed448\",\"alg\":\"EdDSA\",\"crv\":\"Ed448\",\"x\":\"" + Tokens.b64(new byte[57]) + "\"}";
        var good = (ECPublicKey) Tokens.EC256.getPublic();
        var badLength = Jwks.ec("short", "ES256", "P-256", 32, good).replaceFirst("\"x\":\"[^\"]+\"", "\"x\":\"" + Tokens.b64(new byte[31]) + "\"");
        var offCurve = Jwks.ec("off", "ES256", "P-256", 32, good).replaceFirst("\"y\":\"[^\"]+\"", "\"y\":\"" + Tokens.b64(new byte[32]) + "\"");
        var wrongCurve = Jwks.ec("curve", "ES256", "P-384", 32, good);
        var source = new Source(set(
                rsaJwk("noalg", null), // no alg and no default: skipped
                rsaJwk("use-enc", "RS256").replace("\"use\":\"sig\"", "\"use\":\"enc\""),
                rsaJwk("ops", "RS256").replace("\"use\":\"sig\"", "\"key_ops\":[\"encrypt\"]"),
                rsaJwk("noops", "RS256").replace("\"use\":\"sig\"", "\"key_ops\":\"verify\""),
                Jwks.rsa("weak", "RS256", (RSAPublicKey) weak.getPublic()),
                "{\"kty\":\"oct\",\"kid\":\"hmac\",\"alg\":\"HS256\",\"k\":\"" + Tokens.b64(Tokens.SECRET) + "\"}",
                "{\"kty\":\"oct\",\"kid\":\"hmac2\",\"k\":\"" + Tokens.b64(Tokens.SECRET) + "\"}",
                rsaJwk("hs-alg", "HS256"), rsaJwk("none", "none"), rsaJwk("family", "ES256"),
                rsaJwk("alg-number", "RS256").replace("\"RS256\"", "5"),
                ed448, badLength, offCurve, wrongCurve,
                "{\"kty\":\"RSA\",\"kid\":\"nomodulus\",\"alg\":\"RS256\"}",
                "{\"kty\":\"RSA\",\"alg\":\"RS256\",\"n\":\"AQAB\",\"e\":\"AQAB\"}", // no kid
                "7", "{\"kid\":\"nokty\"}",
                rsaJwk("good", "RS256")));
        var jwt = builder().jwks(source).build();
        for (var kid : new String[] {"noalg", "use-enc", "ops", "noops", "weak", "hmac", "hmac2", "hs-alg", "none", "family", "alg-number",
                "ed448", "short", "off", "curve", "nomodulus", "nokty"}) {
            rejects(jwt, rs256(kid), "No key");
        }
        assertThat(jwt.verify(rs256("good")).principal()).isEqualTo("ada");
    }

    @Test
    void aDefaultAlgorithmCoversKeysWithoutAlgAndStillRequiresTheKeyTypeToFit() {
        var source = new Source(set(rsaJwk("rs", null), Jwks.ec("ec", null, "P-256", 32, (ECPublicKey) Tokens.EC256.getPublic())));
        var jwt = builder().jwks(source, JwksOptions.defaults().defaultAlgorithm(JwsAlgorithm.RS256)).build();
        assertThat(jwt.verify(rs256("rs")).principal()).isEqualTo("ada");
        rejects(jwt, Tokens.sign("{\"alg\":\"ES256\",\"kid\":\"ec\"}", claims(""), Tokens.EC256.getPrivate(), "SHA256withECDSAinP1363Format"),
                "No key"); // an EC key cannot be taken as RS256
        // A declared alg always wins over the default.
        var declared = new Source(set(rsaJwk("ps", "PS256")));
        var other = builder().jwks(declared, JwksOptions.defaults().defaultAlgorithm(JwsAlgorithm.RS256)).build();
        rejects(other, rs256("ps"), "registered for another algorithm");
        assertThatIllegalArgumentException().isThrownBy(() -> JwksOptions.defaults().defaultAlgorithm(JwsAlgorithm.HS256));
    }

    @Test
    void refetchesOnAnUnknownKeyIdOncePerMinimumInterval() {
        var source = new Source(set(rsaJwk("old", "RS256")));
        var jwt = builder().jwks(source).build();
        assertThat(jwt.verify(rs256("old")).principal()).isEqualTo("ada");
        assertThat(source.fetches).hasValue(1);

        source.document = bytes(set(rsaJwk("old", "RS256"), rsaJwk("new", "RS256")));
        rejects(jwt, rs256("new"), "No key"); // only 0 seconds passed: the fetch is rate limited
        assertThat(source.fetches).hasValue(1);
        clock.advance(Duration.ofSeconds(29));
        rejects(jwt, rs256("new"), "No key");
        assertThat(source.fetches).hasValue(1);
        clock.advance(Duration.ofSeconds(1));
        assertThat(jwt.verify(rs256("new")).principal()).isEqualTo("ada"); // rotated in without a restart
        assertThat(source.fetches).hasValue(2);
        rejects(jwt, rs256("nobody"), "No key");
        assertThat(source.fetches).hasValue(2); // limited again
    }

    @Test
    void streamsOfRandomKeyIdsCauseOneFetchPerInterval() {
        var source = new Source(set(rsaJwk("a", "RS256")));
        var jwt = builder().jwks(source).build();
        for (int i = 0; i < 200; i++) { rejects(jwt, rs256("random-" + i), "No key"); }
        assertThat(source.fetches).hasValue(1);
        clock.advance(Duration.ofSeconds(30));
        for (int i = 0; i < 200; i++) { rejects(jwt, rs256("again-" + i), "No key"); }
        assertThat(source.fetches).hasValue(2);
    }

    @Test
    void rotatesKeysOutAndForgedTokensNeverCauseFetches() {
        var source = new Source(set(rsaJwk("k1", "RS256")));
        var jwt = builder().jwks(source).build();
        assertThat(jwt.verify(rs256("k1")).principal()).isEqualTo("ada");
        // A forged token for a known key ID verifies against the cached set and fetches nothing.
        var forged = Tokens.sign("{\"alg\":\"RS256\",\"kid\":\"k1\"}", claims(""), OTHER_RSA.getPrivate(), "SHA256withRSA");
        rejects(jwt, forged, "Signature");
        assertThat(source.fetches).hasValue(1);

        source.document = bytes(set(Jwks.rsa("k2", "RS256", (RSAPublicKey) OTHER_RSA.getPublic())));
        clock.advance(Duration.ofMinutes(10)); // the refresh interval
        var k2 = Tokens.sign("{\"alg\":\"RS256\",\"kid\":\"k2\"}", longLived(), OTHER_RSA.getPrivate(), "SHA256withRSA");
        assertThat(jwt.verify(k2).principal()).isEqualTo("ada");
        rejects(jwt, rs256("k1"), "No key"); // the retired key no longer verifies
    }

    @Test
    void refreshesStaleSetsOnUseAndKeepsServingTheOldSetMeanwhile() {
        var source = new Source(set(rsaJwk("k1", "RS256")));
        var jwt = builder().jwks(source).build();
        assertThat(jwt.verify(rs256("k1")).principal()).isEqualTo("ada");
        clock.advance(Duration.ofMinutes(9).plusSeconds(59));
        assertThat(jwt.verify(rs256("k1")).principal()).isEqualTo("ada");
        assertThat(source.fetches).hasValue(1);
        clock.advance(Duration.ofSeconds(1));
        assertThat(jwt.verify(rs256("k1")).principal()).isEqualTo("ada");
        assertThat(source.fetches).hasValue(2);
    }

    @Test
    void keepsTheLastGoodSetWhenAFetchFailsAndDropsItAfterTheMaximumStaleness() {
        var source = new Source(set(rsaJwk("k1", "RS256")));
        var jwt = builder().jwks(source, JwksOptions.defaults().maxStale(Duration.ofHours(1))).build();
        assertThat(jwt.verify(rs256("k1")).principal()).isEqualTo("ada");

        source.failure = new IOException("endpoint down");
        clock.advance(Duration.ofMinutes(11));
        assertThat(jwt.verify(rs256("k1")).principal()).isEqualTo("ada"); // refresh failed; last good set kept
        assertThat(source.fetches).hasValue(2);
        clock.advance(Duration.ofSeconds(5));
        assertThat(jwt.verify(rs256("k1")).principal()).isEqualTo("ada");
        assertThat(source.fetches).hasValue(2); // retries are rate limited too
        clock.advance(Duration.ofMinutes(50));
        rejects(jwt, rs256("k1"), "endpoint down"); // beyond maxStale: fail closed, with the reason for logs
        source.failure = null;
        clock.advance(Duration.ofSeconds(30));
        assertThat(jwt.verify(rs256("k1")).principal()).isEqualTo("ada"); // recovered
    }

    @Test
    void anUnreachableEndpointAtStartRejectsTokensAndRecovers() {
        var source = new Source(set(rsaJwk("k1", "RS256")));
        source.failure = new IOException("connect refused");
        var jwt = builder().jwks(source).build();
        rejects(jwt, rs256("k1"), "connect refused");
        assertThat(jwt.refreshKeys()).isFalse();
        source.failure = null;
        assertThat(jwt.refreshKeys()).isTrue(); // an explicit refresh ignores the rate limit
        assertThat(jwt.verify(rs256("k1")).principal()).isEqualTo("ada");
        assertThat(builder().publicKey(JwsAlgorithm.RS256, Tokens.RSA.getPublic()).build().refreshKeys()).isFalse();
    }

    @Test
    void rejectsUnusableDocumentsAndKeepsTheLastGoodSet() {
        var source = new Source(set(rsaJwk("k1", "RS256")));
        var jwt = builder().jwks(source).build();
        assertThat(jwt.refreshKeys()).isTrue();
        var duplicate = set(rsaJwk("a", "RS256"), rsaJwk("a", "RS256"));
        var tooMany = new ArrayList<String>();
        for (int i = 0; i < 101; i++) { tooMany.add(rsaJwk("k" + i, "RS256")); }
        var bad = new String[] {"", "[]", "7", "{}", "{\"keys\":{}}", "{\"keys\":[]}", "{\"keys\":[7]}", "{\"keys\":[{\"kid\":\"x\"}]}",
                "{\"keys\":[],\"keys\":[]}", "{\"keys\": [", set(rsaJwk("a", "RS256")) + " x", duplicate,
                set(tooMany.toArray(String[]::new)), "ÿþ", "{\"keys\":" + "[".repeat(40) + "]".repeat(40) + "}"};
        for (var document : bad) {
            source.document = bytes(document);
            assertThat(jwt.refreshKeys()).as(document).isFalse();
            assertThat(jwt.verify(rs256("k1")).principal()).isEqualTo("ada"); // still the last good set
        }
        source.document = null;
        assertThat(jwt.refreshKeys()).isFalse();
        source.document = new byte[70_000];
        assertThat(jwt.refreshKeys()).isFalse(); // over the 64 KiB default, whatever the source did
        assertThat(jwt.verify(rs256("k1")).principal()).isEqualTo("ada");
    }

    @Test
    void passesTheConfiguredLimitToTheSource() {
        var limit = new AtomicInteger();
        var jwt = builder().jwks(max -> { limit.set(max); return bytes(set(rsaJwk("k1", "RS256"))); },
                JwksOptions.defaults().maxBytes(2048)).build();
        assertThat(jwt.verify(rs256("k1")).principal()).isEqualTo("ada");
        assertThat(limit).hasValue(2048);
        var padded = bytes(set(rsaJwk("k1", "RS256")).replaceFirst("\\}$", ",\"pad\":\"" + "x".repeat(1100) + "\"}"));
        var tight = builder().jwks(max -> padded, JwksOptions.defaults().maxBytes(1024)).build();
        rejects(tight, rs256("k1"), "longer than 1024"); // enforced even if the source ignores the limit
    }

    @Test
    void aBurstOfUnknownKeyIdsWaitsForOneFetch() throws Exception {
        var fetches = new AtomicInteger();
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        JwksSource slow = max -> {
            fetches.incrementAndGet();
            entered.countDown();
            try {
                release.await();
            } catch (InterruptedException interrupted) {
                throw new IOException(interrupted);
            }
            return bytes(set(rsaJwk("k1", "RS256")));
        };
        var jwt = builder().jwks(slow).build();
        int threads = 8;
        try (var executor = Executors.newFixedThreadPool(threads)) {
            var futures = new ArrayList<Future<String>>();
            for (int i = 0; i < threads; i++) { futures.add(executor.submit(() -> jwt.verify(rs256("k1")).principal())); }
            entered.await();
            release.countDown();
            for (var future : futures) { assertThat(future.get()).isEqualTo("ada"); }
        }
        assertThat(fetches).hasValue(1);
    }

    @Test
    void aBadSourceRuntimeExceptionIsAFetchFailureNotACrash() {
        var failure = new AtomicReference<RuntimeException>(new IllegalStateException("boom"));
        var jwt = builder().jwks(max -> {
            if (failure.get() != null) { throw failure.get(); }
            return bytes(set(rsaJwk("k1", "RS256")));
        }).build();
        rejects(jwt, rs256("k1"), "boom");
        failure.set(null);
        assertThat(jwt.refreshKeys()).isTrue();
    }

    @Test
    void configurationIsValidated() {
        var source = new Source(set(rsaJwk("k1", "RS256")));
        var builder = builder().jwks(source);
        assertThatIllegalStateException().isThrownBy(() -> builder.jwks(source)).withMessageContaining("already");
        assertThat(builder().jwks(source).build()).isNotNull(); // a key set alone is a key source
        assertThatIllegalStateException().isThrownBy(() -> JwtAuthenticator.builder().issuer("i").audience("a").build());
        var options = JwksOptions.defaults();
        assertThatIllegalArgumentException().isThrownBy(() -> options.maxBytes(1023));
        assertThatIllegalArgumentException().isThrownBy(() -> options.maxBytes(1_048_577));
        assertThatIllegalArgumentException().isThrownBy(() -> options.refreshInterval(Duration.ZERO));
        assertThatIllegalArgumentException().isThrownBy(() -> options.refreshInterval(Duration.ofHours(25)));
        assertThatIllegalArgumentException().isThrownBy(() -> options.refreshInterval(Duration.ofMillis(1500)));
        assertThatIllegalArgumentException().isThrownBy(() -> options.minRefreshInterval(Duration.ZERO));
        assertThatIllegalArgumentException().isThrownBy(() -> options.maxStale(Duration.ofDays(8)));
        assertThatIllegalArgumentException().isThrownBy(() -> JwtAuthenticator.builder().jwks(source, options.minRefreshInterval(Duration.ofMinutes(11))));
        assertThatIllegalArgumentException().isThrownBy(() -> JwtAuthenticator.builder().jwks(source, options.maxStale(Duration.ofMinutes(5))));
        org.assertj.core.api.Assertions.assertThatNullPointerException().isThrownBy(() -> JwtAuthenticator.builder().jwks(null));
    }

    @Test
    void anExpiredTokenIsRejectedEvenWithAValidKeySetKey() {
        var jwt = builder().jwks(new Source(set(rsaJwk("k1", "RS256")))).build();
        var expired = Tokens.sign("{\"alg\":\"RS256\",\"kid\":\"k1\"}", claims("").replace("\"exp\":" + (NOW + 300), "\"exp\":" + (NOW - 100)),
                Tokens.RSA.getPrivate(), "SHA256withRSA");
        rejects(jwt, expired, "expired");
        assertThat(UnauthorizedException.class).isNotNull();
    }
}
