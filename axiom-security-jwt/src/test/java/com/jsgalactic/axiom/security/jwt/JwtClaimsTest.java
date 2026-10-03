package com.jsgalactic.axiom.security.jwt;

import static com.jsgalactic.axiom.security.jwt.JwtAuthenticatorTest.base;
import static com.jsgalactic.axiom.security.jwt.JwtAuthenticatorTest.rejects;
import static com.jsgalactic.axiom.security.jwt.Tokens.NOW;
import static com.jsgalactic.axiom.security.jwt.Tokens.claims;
import static com.jsgalactic.axiom.security.jwt.Tokens.hs256;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/** Additional claims on the identity, and the token check hook for revocation and jti replay. */
class JwtClaimsTest {
    private static JwtAuthenticator.Builder hmac() {
        return base().hmacKey(JwsAlgorithm.HS256, Tokens.SECRET);
    }

    @Test
    void exposesOnlyTheNamedScalarClaimsAsAttributes() {
        var jwt = hmac().exposeClaims("tenant", "email_verified", "level", "missing", "nothing").build();
        var identity = jwt.verify(hs256(claims("\"tenant\":\"acme\",\"email_verified\":true,\"level\":3,\"secret\":\"hidden\",\"nothing\":null")));
        assertThat(identity.attributes()).containsOnly(Map.entry("tenant", "acme"), Map.entry("email_verified", "true"), Map.entry("level", "3"));
        assertThat(identity.attribute("secret")).isEmpty();
        assertThat(hmac().build().verify(hs256(claims("\"tenant\":\"acme\""))).attributes()).isEmpty();
    }

    @Test
    void rejectsExposedClaimsThatAreNotScalarOrNotUsable() {
        var jwt = hmac().exposeClaims("tenant").build();
        rejects(jwt, hs256(claims("\"tenant\":[\"a\"]")), "not a scalar");
        rejects(jwt, hs256(claims("\"tenant\":{\"a\":1}")), "not a scalar");
        rejects(jwt, hs256(claims("\"tenant\":\"" + "x".repeat(2049) + "\"")), "not usable");
        rejects(jwt, hs256(claims("\"tenant\":\"a\\nb\"")), "not usable");
        assertThatIllegalArgumentException().isThrownBy(() -> hmac().exposeClaims(""));
        var many = new String[65];
        for (int i = 0; i < many.length; i++) { many[i] = "c" + i; }
        assertThatIllegalArgumentException().isThrownBy(() -> hmac().exposeClaims(many));
    }

    @Test
    void mapsClaimsWithApplicationCodeWithoutExposingAJsonType() {
        var jwt = hmac().exposeClaims("tenant").attributes(claims -> Map.of(
                "tenant", claims.string("org").orElse("none"), // replaces the exposed value
                "groups", String.join(",", claims.strings("groups")),
                "age", String.valueOf(claims.number("age").orElse(-1)),
                "admin", String.valueOf(claims.bool("admin").orElse(false)),
                "issuer", claims.issuer(), "expires", claims.expiresAt().toString())).build();
        var identity = jwt.verify(hs256(claims("\"tenant\":\"old\",\"org\":\"acme\",\"groups\":[\"a\",\"b\"],\"age\":41,\"admin\":true")));
        assertThat(identity.attributes()).containsEntry("tenant", "acme").containsEntry("groups", "a,b").containsEntry("age", "41")
                .containsEntry("admin", "true").containsEntry("issuer", "https://issuer.test")
                .containsEntry("expires", Instant.ofEpochSecond(NOW + 300).toString());
    }

    @Test
    void aWronglyTypedClaimInAMapperIsAnInvalidTokenAndABadMapperIsNotMasked() {
        var jwt = hmac().attributes(claims -> Map.of("org", claims.string("org").orElse(""))).build();
        rejects(jwt, hs256(claims("\"org\":7")), "not a string");
        rejects(hmac().attributes(claims -> Map.of("a\nb", "v")).build(), hs256(claims("")), "not usable");
        var broken = hmac().attributes(claims -> { throw new IllegalStateException("bug"); }).build();
        assertThatThrownBy(() -> broken.verify(hs256(claims(""))))
                .isInstanceOf(IllegalStateException.class).hasMessage("bug");
        var returnsNull = hmac().attributes(claims -> null).build();
        assertThatThrownBy(() -> returnsNull.verify(hs256(claims("")))).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void claimsAccessorsAreTypedAndHideValuesInToString() {
        var seen = new JwtClaims[1];
        hmac().tokenCheck(claims -> { seen[0] = claims; return true; }).build()
                .verify(hs256(claims("\"s\":\"v\",\"n\":12,\"b\":false,\"l\":[\"x\",\"y\"],\"z\":null,\"o\":{},\"jti\":\"id-1\",\"big\":1234567890123456")));
        var c = seen[0];
        assertThat(c.names()).contains("s", "n", "b", "l", "z", "o", "jti");
        assertThat(c.has("z")).isTrue();
        assertThat(c.has("absent")).isFalse();
        assertThat(c.string("s")).contains("v");
        assertThat(c.string("z")).isEmpty();
        assertThat(c.string("absent")).isEmpty();
        assertThat(c.strings("l")).containsExactly("x", "y");
        assertThat(c.strings("s")).containsExactly("v");
        assertThat(c.strings("absent")).isEmpty();
        assertThat(c.number("n")).hasValue(12);
        assertThat(c.bool("b")).contains(false);
        assertThat(c.id()).contains("id-1");
        assertThat(c.subject()).isEqualTo("ada");
        assertThat(c.text("n")).contains("12");
        assertThat(c.text("b")).contains("false");
        assertThatIllegalArgumentException().isThrownBy(() -> c.string("n"));
        assertThatIllegalArgumentException().isThrownBy(() -> c.strings("o"));
        assertThatIllegalArgumentException().isThrownBy(() -> c.strings("n"));
        assertThatIllegalArgumentException().isThrownBy(() -> c.number("s"));
        assertThatIllegalArgumentException().isThrownBy(() -> c.number("big")); // more than 15 digits
        assertThatIllegalArgumentException().isThrownBy(() -> c.bool("s"));
        assertThatIllegalArgumentException().isThrownBy(() -> c.text("l"));
        assertThat(c.toString()).doesNotContain("ada", "id-1");
    }

    @Test
    void tokenChecksRunAfterEveryOtherVerificationAndRejectWith401() {
        var calls = new AtomicInteger();
        var jwt = hmac().tokenCheck(claims -> { calls.incrementAndGet(); return !claims.subject().equals("mallory"); }).build();
        assertThat(jwt.verify(hs256(claims(""))).principal()).isEqualTo("ada");
        assertThat(calls).hasValue(1);
        rejects(jwt, hs256(claims("").replace("\"sub\":\"ada\"", "\"sub\":\"mallory\"")), "token check");
        // Forged, expired and wrongly addressed tokens never reach the check.
        calls.set(0);
        rejects(jwt, hs256(claims("")).replaceAll("..$", "AA"), "ignature");
        rejects(jwt, hs256(claims("").replace("\"exp\":" + (NOW + 300), "\"exp\":" + (NOW - 100))), "expired");
        rejects(jwt, hs256(claims("").replace("\"aud\":\"notes\"", "\"aud\":\"other\"")), "Audience");
        assertThat(calls).hasValue(0);
    }

    @Test
    void detectsJtiReplayAndRevocation() {
        var seen = ConcurrentHashMap.<String>newKeySet();
        Set<String> revoked = Set.of("revoked-1");
        var jwt = hmac()
                .tokenCheck(claims -> claims.id().map(id -> !revoked.contains(id)).orElse(false))
                .tokenCheck(claims -> seen.add(claims.id().orElseThrow())) // last: it records the jti
                .build();
        var first = hs256(claims("\"jti\":\"t-1\""));
        assertThat(jwt.verify(first).principal()).isEqualTo("ada");
        rejects(jwt, first, "token check"); // replay
        assertThat(jwt.verify(hs256(claims("\"jti\":\"t-2\""))).principal()).isEqualTo("ada");
        rejects(jwt, hs256(claims("\"jti\":\"revoked-1\"")), "token check");
        rejects(jwt, hs256(claims("")), "token check"); // no jti at all
        assertThat(seen).containsExactlyInAnyOrder("t-1", "t-2"); // a revoked token was not recorded
    }

    @Test
    void aReplayRecordIsAtomicUnderConcurrentUse() throws Exception {
        var seen = ConcurrentHashMap.<String>newKeySet();
        var jwt = hmac().tokenCheck(claims -> seen.add(claims.id().orElseThrow())).build();
        var token = hs256(claims("\"jti\":\"once\""));
        int threads = 16;
        var start = new CountDownLatch(1);
        var accepted = new AtomicInteger();
        try (var executor = Executors.newFixedThreadPool(threads)) {
            var futures = new java.util.ArrayList<java.util.concurrent.Future<?>>();
            for (int i = 0; i < threads; i++) {
                futures.add(executor.submit(() -> {
                    start.await();
                    try {
                        jwt.verify(token);
                        accepted.incrementAndGet();
                    } catch (com.jsgalactic.axiom.error.UnauthorizedException replayed) {
                        // expected for every thread but one
                    }
                    return null;
                }));
            }
            start.countDown();
            for (var future : futures) { future.get(); }
        }
        assertThat(accepted).hasValue(1);
    }

    @Test
    void aFailingCheckFailsClosedInsteadOfAcceptingTheToken() {
        var jwt = hmac().tokenCheck(claims -> { throw new IllegalStateException("revocation store down"); }).build();
        assertThatThrownBy(() -> jwt.verify(hs256(claims(""))))
                .isInstanceOf(IllegalStateException.class).hasMessage("revocation store down");
        // An accessor on a wrongly typed claim is the token's fault: 401.
        var typed = hmac().tokenCheck(claims -> claims.id().isPresent()).build();
        rejects(typed, hs256(claims("\"jti\":5")), "not a string");
    }

    @Test
    void checkAndMapperRegistrationRejectsNull() {
        org.assertj.core.api.Assertions.assertThatNullPointerException().isThrownBy(() -> hmac().tokenCheck(null));
        org.assertj.core.api.Assertions.assertThatNullPointerException().isThrownBy(() -> hmac().attributes(null));
        assertThat(List.of(1)).hasSize(1);
    }
}
