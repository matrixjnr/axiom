package com.jsgalactic.axiom.security.jwt;

import java.io.IOException;
import java.math.BigInteger;
import java.security.AlgorithmParameters;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.ECFieldFp;
import java.security.spec.ECGenParameterSpec;
import java.security.spec.ECParameterSpec;
import java.security.spec.ECPoint;
import java.security.spec.ECPublicKeySpec;
import java.security.spec.EdECPoint;
import java.security.spec.EdECPublicKeySpec;
import java.security.spec.NamedParameterSpec;
import java.security.spec.RSAPublicKeySpec;
import java.time.Clock;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.locks.ReentrantLock;

/**
 * The verification keys of one issuer's JSON Web Key Set: fetched from a {@link JwksSource},
 * parsed strictly, cached, refreshed on a schedule and when a token names an unknown key ID, and
 * replaced as a whole only by a complete, valid set. Thread-safe.
 *
 * <p>Readers use an immutable snapshot and never wait for a fetch when they already hold a usable
 * key. One thread fetches at a time; the others re-check the cache after it finished instead of
 * fetching again, so a burst of unknown key IDs causes one fetch, and fetch attempts are spaced by
 * the minimum refresh interval whether they succeed or not.
 */
final class JwksKeys {
    private static final int MAX_KEYS = 100;
    private static final int MAX_RSA_BITS = 8192;
    private static final BigInteger ED25519_P = BigInteger.TWO.pow(255).subtract(BigInteger.valueOf(19));

    private record Snapshot(Map<String, JwtAuthenticator.Entry> keys, long fetchedAt) {
        static final Snapshot EMPTY = new Snapshot(Map.of(), Long.MIN_VALUE);
    }

    private final JwksSource source;
    private final JwksOptions options;
    private final Clock clock;
    private final ReentrantLock fetching = new ReentrantLock();
    private volatile Snapshot snapshot = Snapshot.EMPTY;
    private volatile long lastAttempt = Long.MIN_VALUE;
    private volatile String lastFailure;

    JwksKeys(JwksSource source, JwksOptions options, Clock clock) {
        this.source = source;
        this.options = options;
        this.clock = clock;
    }

    /** Why the most recent fetch failed, or null if it did not (or none ran). Contains no key material. */
    String lastFailure() {
        return lastFailure;
    }

    /**
     * Finds the key for a key ID, refetching the set if it is stale or does not know the ID and the
     * rate limit allows.
     *
     * @return the key bound to its declared algorithm, or null
     */
    JwtAuthenticator.Entry find(String kid) {
        long now = clock.millis();
        var current = usable(snapshot, now);
        var hit = current.keys().get(kid);
        boolean stale = current == Snapshot.EMPTY || now - current.fetchedAt() >= options.refreshInterval().toMillis();
        if (hit != null && !stale) { return hit; }
        if (!attemptAllowed(now)) {
            if (hit == null && fetching.isLocked()) {
                // The first load (or a rotation) is in flight: wait for its result instead of failing the token.
                fetching.lock();
                fetching.unlock();
                return usable(snapshot, clock.millis()).keys().get(kid);
            }
            return hit;
        }
        if (hit != null) {
            // A usable key exists: refresh opportunistically but never wait for another thread's fetch.
            if (fetching.tryLock()) {
                try {
                    if (attemptAllowed(clock.millis())) { refreshLocked(); }
                } finally {
                    fetching.unlock();
                }
            }
        } else {
            fetching.lock();
            try {
                // Another thread may have fetched while this one waited; then the rate limit says no.
                if (attemptAllowed(clock.millis())) { refreshLocked(); }
            } finally {
                fetching.unlock();
            }
        }
        return usable(snapshot, clock.millis()).keys().get(kid);
    }

    /** Fetches now, ignoring the schedule and rate limit. Returns true if a new set was installed. */
    boolean refresh() {
        fetching.lock();
        try {
            return refreshLocked();
        } finally {
            fetching.unlock();
        }
    }

    private Snapshot usable(Snapshot candidate, long now) {
        if (candidate == Snapshot.EMPTY || now - candidate.fetchedAt() >= options.maxStale().toMillis()) { return Snapshot.EMPTY; }
        return candidate;
    }

    private boolean attemptAllowed(long now) {
        long last = lastAttempt;
        return last == Long.MIN_VALUE || now - last >= options.minRefreshInterval().toMillis();
    }

    private boolean refreshLocked() {
        long now = clock.millis();
        lastAttempt = now;
        try {
            var document = source.fetch(options.maxBytes());
            if (document == null) { throw new IOException("The key set source returned nothing"); }
            if (document.length > options.maxBytes()) { throw new IOException("The key set is longer than " + options.maxBytes() + " bytes"); }
            snapshot = new Snapshot(Map.copyOf(parse(document)), now);
            lastFailure = null;
            return true;
        } catch (IOException | RuntimeException failure) {
            // The last good set stays; the reason is for the exception chain of rejected tokens.
            lastFailure = failure.getClass().getSimpleName() + ": " + failure.getMessage();
            return false;
        }
    }

    // Parsing

    /** Parses a document. Unusable individual keys are skipped; a malformed or empty set is an error. */
    private Map<String, JwtAuthenticator.Entry> parse(byte[] document) throws IOException {
        Map<String, Object> root;
        try {
            root = Json.parseObject(document);
        } catch (IllegalArgumentException malformed) {
            throw new IOException("The key set is not valid JSON: " + malformed.getMessage());
        }
        if (!(root.get("keys") instanceof List<?> keys)) { throw new IOException("The key set has no keys array"); }
        if (keys.size() > MAX_KEYS) { throw new IOException("The key set has more than " + MAX_KEYS + " keys"); }
        var result = new HashMap<String, JwtAuthenticator.Entry>();
        for (var element : keys) {
            if (!(element instanceof Map<?, ?> raw)) { continue; }
            @SuppressWarnings("unchecked") var jwk = (Map<String, Object>) raw;
            if (!(jwk.get("kid") instanceof String kid) || kid.isEmpty()) { continue; }
            var entry = key(jwk);
            if (entry == null) { continue; }
            if (result.putIfAbsent(kid, entry) != null) { throw new IOException("The key set repeats a key ID"); }
        }
        if (result.isEmpty()) { throw new IOException("The key set has no usable verification key"); }
        return result;
    }

    /** Builds one key bound to its algorithm, or null if the JWK is not an acceptable verification key. */
    private JwtAuthenticator.Entry key(Map<String, Object> jwk) {
        try {
            if (jwk.containsKey("use") && !"sig".equals(jwk.get("use"))) { return null; }
            if (jwk.containsKey("key_ops") && !(jwk.get("key_ops") instanceof List<?> ops && ops.contains("verify"))) { return null; }
            if (!(jwk.get("kty") instanceof String type)) { return null; }
            var algorithm = algorithm(jwk);
            if (algorithm == null) { return null; }
            PublicKey key = switch (type) {
                case "RSA" -> algorithm.family() == JwsAlgorithm.Family.RSA || algorithm.family() == JwsAlgorithm.Family.RSA_PSS ? rsa(jwk) : null;
                case "EC" -> algorithm.family() == JwsAlgorithm.Family.EC ? ec(jwk, algorithm) : null;
                case "OKP" -> algorithm.family() == JwsAlgorithm.Family.EDDSA ? okp(jwk) : null;
                default -> null; // Including "oct": symmetric keys are never taken from a key set.
            };
            return key == null ? null : new JwtAuthenticator.Entry(algorithm, JwtAuthenticator.Builder.asymmetric(algorithm, key));
        } catch (IllegalArgumentException | GeneralSecurityException | ClassCastException unusable) {
            return null;
        }
    }

    private JwsAlgorithm algorithm(Map<String, Object> jwk) {
        var declared = jwk.get("alg");
        if (declared == null) { return options.defaultAlgorithm(); }
        if (!(declared instanceof String name)) { return null; }
        for (var candidate : JwsAlgorithm.values()) {
            if (candidate.name().equals(name) && candidate.family() != JwsAlgorithm.Family.HMAC) { return candidate; }
        }
        return null;
    }

    private static PublicKey rsa(Map<String, Object> jwk) throws GeneralSecurityException {
        var modulus = new BigInteger(1, member(jwk, "n"));
        var exponent = new BigInteger(1, member(jwk, "e"));
        if (modulus.bitLength() > MAX_RSA_BITS || !exponent.testBit(0) || exponent.bitLength() < 2 || exponent.bitLength() > 32) {
            return null;
        }
        var key = KeyFactory.getInstance("RSA").generatePublic(new RSAPublicKeySpec(modulus, exponent));
        return key instanceof RSAPublicKey ? key : null;
    }

    private static PublicKey ec(Map<String, Object> jwk, JwsAlgorithm algorithm) throws GeneralSecurityException {
        var curve = JwtAuthenticator.Builder.curve(algorithm);
        var expectedName = switch (algorithm) {
            case ES256 -> "P-256";
            case ES384 -> "P-384";
            default -> "P-521";
        };
        if (!expectedName.equals(jwk.get("crv"))) { return null; }
        var parameters = AlgorithmParameters.getInstance("EC");
        parameters.init(new ECGenParameterSpec(curve));
        var spec = parameters.getParameterSpec(ECParameterSpec.class);
        int size = algorithm.size();
        var xBytes = member(jwk, "x");
        var yBytes = member(jwk, "y");
        if (xBytes.length != size || yBytes.length != size) { return null; } // RFC 7518: full-length coordinates.
        var x = new BigInteger(1, xBytes);
        var y = new BigInteger(1, yBytes);
        var p = ((ECFieldFp) spec.getCurve().getField()).getP();
        if (x.compareTo(p) >= 0 || y.compareTo(p) >= 0) { return null; }
        // The point must lie on the curve: y^2 = x^3 + ax + b (mod p). Rejects invalid-curve keys.
        var left = y.multiply(y).mod(p);
        var right = x.pow(3).add(spec.getCurve().getA().multiply(x)).add(spec.getCurve().getB()).mod(p);
        if (!left.equals(right)) { return null; }
        return KeyFactory.getInstance("EC").generatePublic(new ECPublicKeySpec(new ECPoint(x, y), spec));
    }

    private static PublicKey okp(Map<String, Object> jwk) throws GeneralSecurityException {
        if (!"Ed25519".equals(jwk.get("crv"))) { return null; } // Ed448 and X25519 are not accepted.
        var x = member(jwk, "x");
        if (x.length != 32) { return null; }
        // RFC 8032: little-endian y with the sign of x in the top bit.
        boolean xOdd = (x[31] & 0x80) != 0;
        var reversed = new byte[32];
        for (int i = 0; i < 32; i++) { reversed[i] = x[31 - i]; }
        reversed[0] &= 0x7f;
        var y = new BigInteger(1, reversed);
        if (y.compareTo(ED25519_P) >= 0) { return null; }
        return KeyFactory.getInstance("Ed25519").generatePublic(new EdECPublicKeySpec(NamedParameterSpec.ED25519, new EdECPoint(xOdd, y)));
    }

    private static byte[] member(Map<String, Object> jwk, String name) {
        if (!(jwk.get(name) instanceof String text)) { throw new IllegalArgumentException("Missing " + name); }
        return JwtAuthenticator.decode(text, name);
    }
}
