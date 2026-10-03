package com.jsgalactic.axiom.security.jwt;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.interfaces.ECPublicKey;
import java.security.interfaces.EdECPublicKey;
import java.security.interfaces.RSAPublicKey;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.stream.Collectors;

/** Builds JWK Set documents for tests, independently of the code under test. */
final class Jwks {
    private Jwks() {}

    static String set(String... jwks) {
        return "{\"keys\":[" + String.join(",", jwks) + "]}";
    }

    static byte[] bytes(String json) {
        return json.getBytes(StandardCharsets.UTF_8);
    }

    private static String unsigned(BigInteger value, int size) {
        var raw = value.toByteArray();
        var out = new byte[size];
        int copy = Math.min(raw.length, size);
        System.arraycopy(raw, raw.length - copy, out, size - copy, copy);
        return Tokens.b64(out);
    }

    private static String head(String kty, String kid, String alg) {
        return "\"kty\":\"" + kty + "\",\"kid\":\"" + kid + "\"" + (alg == null ? "" : ",\"alg\":\"" + alg + "\"") + ",\"use\":\"sig\"";
    }

    static String rsa(String kid, String alg, RSAPublicKey key) {
        var modulus = key.getModulus().toByteArray();
        int start = modulus[0] == 0 ? 1 : 0;
        return "{" + head("RSA", kid, alg) + ",\"n\":\"" + Tokens.b64(Arrays.copyOfRange(modulus, start, modulus.length))
                + "\",\"e\":\"" + Tokens.b64(key.getPublicExponent().toByteArray()) + "\"}";
    }

    static String ec(String kid, String alg, String crv, int size, ECPublicKey key) {
        return "{" + head("EC", kid, alg) + ",\"crv\":\"" + crv + "\",\"x\":\"" + unsigned(key.getW().getAffineX(), size)
                + "\",\"y\":\"" + unsigned(key.getW().getAffineY(), size) + "\"}";
    }

    static String ed25519(String kid, String alg, EdECPublicKey key) {
        var raw = java.util.Base64.getUrlDecoder().decode(unsigned(key.getPoint().getY(), 32)); // big-endian y
        var x = new byte[32]; // RFC 8032 encoding: little-endian y, sign of x in the top bit
        for (int i = 0; i < 32; i++) { x[i] = raw[31 - i]; }
        if (key.getPoint().isXOdd()) { x[31] |= (byte) 0x80; }
        return "{" + head("OKP", kid, alg) + ",\"crv\":\"Ed25519\",\"x\":\"" + Tokens.b64(x) + "\"}";
    }

    static String join(String... parts) {
        return Arrays.stream(parts).collect(Collectors.joining(","));
    }

    /** A clock the test moves by hand. */
    static final class TestClock extends Clock {
        private volatile Instant now;

        TestClock(long epochSeconds) {
            now = Instant.ofEpochSecond(epochSeconds);
        }

        void advance(Duration duration) {
            now = now.plus(duration);
        }

        @Override public ZoneId getZone() { return ZoneOffset.UTC; }

        @Override public Clock withZone(ZoneId zone) { return this; }

        @Override public Instant instant() { return now; }
    }
}
