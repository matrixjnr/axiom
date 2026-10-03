package com.jsgalactic.axiom.security.jwt;

import com.jsgalactic.axiom.context.SecurityIdentity;
import com.jsgalactic.axiom.error.UnauthorizedException;
import com.jsgalactic.axiom.http.Request;
import com.jsgalactic.axiom.security.Authenticator;
import java.nio.charset.StandardCharsets;
import java.security.AlgorithmParameters;
import java.security.GeneralSecurityException;
import java.security.Key;
import java.security.MessageDigest;
import java.security.PublicKey;
import java.security.Signature;
import java.security.interfaces.ECPublicKey;
import java.security.interfaces.EdECPublicKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.ECGenParameterSpec;
import java.security.spec.ECParameterSpec;
import java.security.spec.MGF1ParameterSpec;
import java.security.spec.NamedParameterSpec;
import java.security.spec.PSSParameterSpec;
import java.time.Clock;
import java.time.Duration;
import java.util.Base64;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Authenticates {@code Authorization: Bearer} requests carrying a signed JWT (RFC 7519, JWS
 * compact serialization), using only the JDK.
 *
 * <pre>{@code
 * var jwt = JwtAuthenticator.builder()
 *         .publicKey("2026-10", JwsAlgorithm.RS256, issuerKey)
 *         .issuer("https://login.example.com")
 *         .audience("notes-api")
 *         .build();
 * var security = Security.of(jwt);
 * }</pre>
 *
 * <p><b>Verification</b>, in this order; any failure is a 401 with code {@code invalid_token} and
 * {@code WWW-Authenticate: Bearer realm="...", error="invalid_token"}, whatever the reason, so a
 * client cannot probe which check failed (the reason is attached as the exception's cause for
 * server-side diagnosis only, and never contains token content):
 * <ol>
 * <li>The token is at most {@link Builder#maxTokenLength(int)} characters (8 KiB by default),
 * checked before anything is decoded.</li>
 * <li>Three non-empty, unpadded, canonical base64url parts. Encrypted tokens (JWE, five parts or an
 * {@code enc} header) are a documented non-goal and are rejected like any invalid token.</li>
 * <li>The header is a strict JSON object (well-formed UTF-8, no duplicate names). Its {@code alg}
 * must be an algorithm a key was registered for: the allow-list is exactly the registered
 * algorithms, so {@code none} and anything unconfigured are refused. A {@code crit} or
 * {@code enc} member is rejected; {@code typ}, if present, must be {@code JWT} or
 * {@code at+jwt}. Header-embedded keys ({@code jwk}, {@code jku}, {@code x5u}, {@code x5c}) are
 * never used.</li>
 * <li>The key is chosen by {@code kid} when the token has one, otherwise it is the key registered
 * without a key ID for the token's algorithm. The key must have been registered for exactly that
 * algorithm, so an RSA or EC public key can never be used as an HMAC secret (algorithm
 * confusion).</li>
 * <li>The signature is verified; HMAC tags are compared in constant time. RSASSA-PSS uses the
 * digest, MGF1 digest and salt length of RFC 7518, and EdDSA means Ed25519 only.</li>
 * <li>Only then is the claims set parsed. {@code exp} is required; {@code exp}, {@code nbf} and
 * {@code iat} are integer NumericDates, checked with the configured clock skew (30 seconds by
 * default, at most five minutes): the token is rejected at or after {@code exp + skew}, before
 * {@code nbf - skew}, or when issued after {@code now + skew}. {@code iss} must equal a configured
 * issuer, {@code aud} (a string or an array) must contain a configured audience, and {@code sub}
 * must be a non-empty string.</li>
 * </ol>
 * The identity's principal is {@code sub}. Roles come from the {@code roles} claim and permissions
 * from the {@code scope} claim by default; each may be a space-separated string or an array of
 * strings, at most 256 entries. Other claims are not exposed.
 *
 * <p><b>Credentials.</b> A request without an {@code Authorization} header, or with a scheme other
 * than {@code Bearer}, carries no credentials for this authenticator: {@link #authenticate} returns
 * empty. A Bearer header with a missing or malformed token is invalid (401). Repeated
 * {@code Authorization} fields are joined by the transport and therefore rejected.
 *
 * <p><b>Lifecycle.</b> Immutable and thread-safe once built; keys are fixed at build time (there
 * is no key-set fetching or rotation). The authenticator holds HMAC secrets for its lifetime.
 */
public final class JwtAuthenticator implements Authenticator {
    private static final Pattern BASE64URL = Pattern.compile("[A-Za-z0-9_-]+");
    private static final Pattern REALM = Pattern.compile("[A-Za-z0-9 ._:/-]{1,64}");
    private static final int MAX_GRANTS = 256;

    private final Map<String, Entry> keysById;
    private final Map<JwsAlgorithm, Entry> keysWithoutId;
    private final Set<String> issuers;
    private final Set<String> audiences;
    private final long skewSeconds;
    private final int maxTokenLength;
    private final String rolesClaim;
    private final String permissionsClaim;
    private final Clock clock;
    private final String challenge;
    private final String invalidChallenge;

    private JwtAuthenticator(Builder builder) {
        keysById = Map.copyOf(builder.keysById);
        keysWithoutId = Map.copyOf(builder.keysWithoutId);
        issuers = Set.copyOf(builder.issuers);
        audiences = Set.copyOf(builder.audiences);
        skewSeconds = builder.skew.toSeconds();
        maxTokenLength = builder.maxTokenLength;
        rolesClaim = builder.rolesClaim;
        permissionsClaim = builder.permissionsClaim;
        clock = builder.clock;
        challenge = "Bearer realm=\"" + builder.realm + "\"";
        invalidChallenge = challenge + ", error=\"invalid_token\"";
    }

    /**
     * Starts a configuration. At least one key, one issuer and one audience are required.
     *
     * @return a new builder
     */
    public static Builder builder() {
        return new Builder();
    }

    @Override
    public Optional<SecurityIdentity> authenticate(Request request) {
        var authorization = Objects.requireNonNull(request, "request").header("Authorization");
        if (authorization.isEmpty()) { return Optional.empty(); }
        var value = authorization.get().strip();
        int space = value.indexOf(' ');
        var scheme = space < 0 ? value : value.substring(0, space);
        if (!scheme.equalsIgnoreCase("Bearer")) { return Optional.empty(); }
        return Optional.of(verify(space < 0 ? "" : value.substring(space + 1).strip()));
    }

    @Override
    public String challenge() {
        return challenge;
    }

    /**
     * Verifies a compact JWS token as described above and maps it to an identity.
     *
     * @param token the token, without the {@code Bearer} prefix
     * @return the verified identity
     * @throws UnauthorizedException with code {@code invalid_token} if the token is not valid
     */
    public SecurityIdentity verify(String token) {
        Objects.requireNonNull(token, "token");
        try {
            return verifyOrThrow(token);
        } catch (IllegalArgumentException invalid) {
            var failure = new UnauthorizedException(invalidChallenge, "invalid_token");
            failure.initCause(invalid); // Server-side reason only; it never contains token content.
            throw failure;
        }
    }

    private SecurityIdentity verifyOrThrow(String token) {
        if (token.length() > maxTokenLength) { throw invalid("Token longer than " + maxTokenLength + " characters"); }
        if (token.isEmpty()) { throw invalid("Missing token"); }
        int first = token.indexOf('.');
        int second = first < 0 ? -1 : token.indexOf('.', first + 1);
        if (second < 0 || token.indexOf('.', second + 1) >= 0) { throw invalid("Token is not a three-part JWS"); }
        var header = Json.parseObject(decode(token.substring(0, first), "header"));
        var algorithm = algorithm(header);
        var key = key(header, algorithm);
        var signature = decode(token.substring(second + 1), "signature");
        var signed = token.substring(0, second).getBytes(StandardCharsets.US_ASCII);
        if (!key.verify(signed, signature)) { throw invalid("Signature does not verify"); }
        var claims = Json.parseObject(decode(token.substring(first + 1, second), "payload"));
        return identity(claims);
    }

    private JwsAlgorithm algorithm(Map<String, Object> header) {
        if (!(header.get("alg") instanceof String name)) { throw invalid("Header has no alg"); }
        JwsAlgorithm algorithm = null;
        for (var candidate : JwsAlgorithm.values()) {
            if (candidate.name().equals(name)) { algorithm = candidate; }
        }
        if (algorithm == null || !allowed(algorithm)) { throw invalid("Algorithm is not allowed"); }
        if (header.containsKey("crit")) { throw invalid("Critical header extensions are not supported"); }
        if (header.containsKey("enc")) { throw invalid("Encrypted tokens are not supported"); }
        var type = header.get("typ");
        if (type != null && !(type instanceof String text && (text.equalsIgnoreCase("JWT") || text.equalsIgnoreCase("at+jwt")
                || text.equalsIgnoreCase("application/at+jwt")))) {
            throw invalid("Unsupported typ");
        }
        return algorithm;
    }

    private boolean allowed(JwsAlgorithm algorithm) {
        if (keysWithoutId.containsKey(algorithm)) { return true; }
        for (var entry : keysById.values()) {
            if (entry.algorithm() == algorithm) { return true; }
        }
        return false;
    }

    private Entry key(Map<String, Object> header, JwsAlgorithm algorithm) {
        var kid = header.get("kid");
        Entry entry;
        if (kid == null) {
            entry = keysWithoutId.get(algorithm);
        } else if (kid instanceof String id) {
            entry = keysById.get(id);
        } else {
            throw invalid("kid is not a string");
        }
        if (entry == null) { throw invalid("No key for the token's kid and algorithm"); }
        if (entry.algorithm() != algorithm) { throw invalid("The key is registered for another algorithm"); }
        return entry;
    }

    private SecurityIdentity identity(Map<String, Object> claims) {
        long now = clock.instant().getEpochSecond();
        long expires = numericDate(claims, "exp", true);
        if (now >= expires + skewSeconds) { throw invalid("Token expired"); }
        long notBefore = numericDate(claims, "nbf", false);
        if (notBefore >= 0 && now + skewSeconds < notBefore) { throw invalid("Token not yet valid"); }
        long issuedAt = numericDate(claims, "iat", false);
        if (issuedAt >= 0 && issuedAt > now + skewSeconds) { throw invalid("Token issued in the future"); }
        if (!(claims.get("iss") instanceof String issuer) || !issuers.contains(issuer)) { throw invalid("Issuer not accepted"); }
        var audience = claims.get("aud");
        boolean audienceMatches = audience instanceof String single ? audiences.contains(single)
                : audience instanceof List<?> list && list.stream().allMatch(String.class::isInstance)
                        && list.stream().anyMatch(audiences::contains);
        if (!audienceMatches) { throw invalid("Audience not accepted"); }
        if (!(claims.get("sub") instanceof String subject) || subject.isEmpty()) { throw invalid("Missing subject"); }
        var roles = grants(claims, rolesClaim);
        var permissions = grants(claims, permissionsClaim);
        try {
            return new SecurityIdentity(subject, roles, permissions);
        } catch (IllegalArgumentException | NullPointerException unusable) {
            throw invalid("Subject or grants are not usable names");
        }
    }

    private static long numericDate(Map<String, Object> claims, String name, boolean required) {
        var value = claims.get(name);
        if (value == null) {
            if (required) { throw invalid("Missing " + name); }
            return -1;
        }
        long seconds = value instanceof Json.JsonNumber number ? number.nonNegativeInteger() : -1;
        if (seconds < 0) { throw invalid(name + " is not an integer NumericDate"); }
        return seconds;
    }

    private static Set<String> grants(Map<String, Object> claims, String name) {
        var value = claims.get(name);
        var result = new LinkedHashSet<String>();
        if (value == null) { return result; }
        if (value instanceof String text) {
            for (var part : text.split(" ")) {
                if (!part.isEmpty()) { result.add(part); }
            }
        } else if (value instanceof List<?> list) {
            for (var element : list) {
                if (!(element instanceof String text)) { throw invalid(name + " holds a non-string value"); }
                result.add(text);
            }
        } else {
            throw invalid(name + " is neither a string nor an array");
        }
        if (result.size() > MAX_GRANTS) { throw invalid(name + " has more than " + MAX_GRANTS + " entries"); }
        return result;
    }

    private static byte[] decode(String part, String name) {
        if (part.isEmpty() || !BASE64URL.matcher(part).matches() || part.length() % 4 == 1) {
            throw invalid("The " + name + " is not unpadded base64url");
        }
        var bytes = Base64.getUrlDecoder().decode(part);
        // Rejects non-zero trailing bits, so every token has exactly one encoding.
        if (!Base64.getUrlEncoder().withoutPadding().encodeToString(bytes).equals(part)) {
            throw invalid("The " + name + " is not canonical base64url");
        }
        return bytes;
    }

    private static IllegalArgumentException invalid(String reason) {
        return new IllegalArgumentException(reason);
    }

    /** A verification key bound to one algorithm. */
    private record Entry(JwsAlgorithm algorithm, Key key) {
        boolean verify(byte[] signed, byte[] signature) {
            try {
                if (algorithm.family() == JwsAlgorithm.Family.HMAC) {
                    var mac = Mac.getInstance(algorithm.jcaName());
                    mac.init(key);
                    return MessageDigest.isEqual(mac.doFinal(signed), signature);
                }
                if (algorithm.family() == JwsAlgorithm.Family.EC && signature.length != 2 * algorithm.size()) {
                    return false;
                }
                if (algorithm.family() == JwsAlgorithm.Family.EDDSA && signature.length != algorithm.size()) {
                    return false;
                }
                var verifier = Signature.getInstance(algorithm.jcaName());
                if (algorithm.family() == JwsAlgorithm.Family.RSA_PSS) { verifier.setParameter(pss(algorithm)); }
                verifier.initVerify((PublicKey) key);
                verifier.update(signed);
                return verifier.verify(signature);
            } catch (GeneralSecurityException | IllegalArgumentException unverifiable) {
                return false;
            }
        }

        /** RFC 7518 section 3.5: the digest, MGF1 with the same digest, and a salt as long as the digest. */
        private static PSSParameterSpec pss(JwsAlgorithm algorithm) {
            var digest = switch (algorithm) {
                case PS256 -> "SHA-256";
                case PS384 -> "SHA-384";
                default -> "SHA-512";
            };
            var mgf = switch (algorithm) {
                case PS256 -> MGF1ParameterSpec.SHA256;
                case PS384 -> MGF1ParameterSpec.SHA384;
                default -> MGF1ParameterSpec.SHA512;
            };
            return new PSSParameterSpec(digest, "MGF1", mgf, algorithm.size(), 1);
        }
    }

    /**
     * Configures a {@link JwtAuthenticator}. Not thread-safe; build once at startup.
     */
    public static final class Builder {
        private final Map<String, Entry> keysById = new HashMap<>();
        private final Map<JwsAlgorithm, Entry> keysWithoutId = new HashMap<>();
        private final Set<String> issuers = new LinkedHashSet<>();
        private final Set<String> audiences = new LinkedHashSet<>();
        private Duration skew = Duration.ofSeconds(30);
        private int maxTokenLength = 8192;
        private String rolesClaim = "roles";
        private String permissionsClaim = "scope";
        private Clock clock = Clock.systemUTC();
        private String realm = "api";

        private Builder() {}

        /**
         * Registers an HMAC secret used for tokens without a {@code kid}.
         *
         * @param algorithm HS256, HS384 or HS512
         * @param secret at least 32, 48 or 64 bytes respectively; copied
         * @return this builder
         * @throws IllegalArgumentException for a non-HMAC algorithm, a short secret, or a second key
         *         without key ID for the algorithm
         */
        public Builder hmacKey(JwsAlgorithm algorithm, byte[] secret) {
            return add(null, algorithm, hmac(algorithm, secret));
        }

        /**
         * Registers an HMAC secret for tokens whose {@code kid} is the key ID.
         *
         * @param keyId key ID, unique within this builder
         * @param algorithm HS256, HS384 or HS512
         * @param secret at least 32, 48 or 64 bytes respectively; copied
         * @return this builder
         * @throws IllegalArgumentException for a non-HMAC algorithm, a short secret or a duplicate
         *         key ID
         */
        public Builder hmacKey(String keyId, JwsAlgorithm algorithm, byte[] secret) {
            return add(Objects.requireNonNull(keyId, "keyId"), algorithm, hmac(algorithm, secret));
        }

        /**
         * Registers a public key used for tokens without a {@code kid}.
         *
         * @param algorithm an RS, PS, ES or EdDSA algorithm matching the key
         * @param key RSA key of at least 2048 bits (RS and PS), EC key on the algorithm's curve (ES)
         *        or Ed25519 key (EdDSA)
         * @return this builder
         * @throws IllegalArgumentException if the key does not fit the algorithm, or for a second
         *         key without key ID for the algorithm
         */
        public Builder publicKey(JwsAlgorithm algorithm, PublicKey key) {
            return add(null, algorithm, asymmetric(algorithm, key));
        }

        /**
         * Registers a public key for tokens whose {@code kid} is the key ID.
         *
         * @param keyId key ID, unique within this builder
         * @param algorithm an RS, PS, ES or EdDSA algorithm matching the key
         * @param key RSA key of at least 2048 bits (RS and PS), EC key on the algorithm's curve (ES)
         *        or Ed25519 key (EdDSA)
         * @return this builder
         * @throws IllegalArgumentException if the key does not fit the algorithm or for a duplicate
         *         key ID
         */
        public Builder publicKey(String keyId, JwsAlgorithm algorithm, PublicKey key) {
            return add(Objects.requireNonNull(keyId, "keyId"), algorithm, asymmetric(algorithm, key));
        }

        /**
         * Accepts tokens whose {@code iss} equals this value. Call again to accept several issuers.
         *
         * @param issuer exact issuer
         * @return this builder
         */
        public Builder issuer(String issuer) {
            issuers.add(requireText(issuer, "issuer"));
            return this;
        }

        /**
         * Accepts tokens whose {@code aud} contains this value. Call again to accept several.
         *
         * @param audience exact audience, usually this API's identifier
         * @return this builder
         */
        public Builder audience(String audience) {
            audiences.add(requireText(audience, "audience"));
            return this;
        }

        /**
         * Sets the tolerated clock difference for {@code exp}, {@code nbf} and {@code iat}.
         *
         * @param skew zero to five minutes, whole seconds; 30 seconds by default
         * @return this builder
         */
        public Builder clockSkew(Duration skew) {
            Objects.requireNonNull(skew, "skew");
            if (skew.isNegative() || skew.compareTo(Duration.ofMinutes(5)) > 0 || skew.getNano() != 0) {
                throw new IllegalArgumentException("Clock skew is zero to five minutes in whole seconds");
            }
            this.skew = skew;
            return this;
        }

        /**
         * Sets the longest accepted token, checked before decoding.
         *
         * @param characters 256 to 65,536; 8,192 by default
         * @return this builder
         */
        public Builder maxTokenLength(int characters) {
            if (characters < 256 || characters > 65_536) {
                throw new IllegalArgumentException("The token length limit is 256 to 65536 characters");
            }
            maxTokenLength = characters;
            return this;
        }

        /**
         * Names the claim holding roles ({@code roles} by default).
         *
         * @param claim claim name
         * @return this builder
         */
        public Builder rolesClaim(String claim) {
            rolesClaim = requireText(claim, "claim");
            return this;
        }

        /**
         * Names the claim holding permissions ({@code scope} by default).
         *
         * @param claim claim name
         * @return this builder
         */
        public Builder permissionsClaim(String claim) {
            permissionsClaim = requireText(claim, "claim");
            return this;
        }

        /**
         * Sets the realm of the {@code WWW-Authenticate} challenge ({@code api} by default).
         *
         * @param realm 1 to 64 letters, digits, spaces and {@code . _ : / -}
         * @return this builder
         */
        public Builder realm(String realm) {
            if (!REALM.matcher(Objects.requireNonNull(realm, "realm")).matches()) {
                throw new IllegalArgumentException("A realm has 1 to 64 letters, digits, spaces and . _ : / -");
            }
            this.realm = realm;
            return this;
        }

        /**
         * Sets the clock for time-based claims, for tests; the system UTC clock by default.
         *
         * @param clock clock
         * @return this builder
         */
        public Builder clock(Clock clock) {
            this.clock = Objects.requireNonNull(clock, "clock");
            return this;
        }

        /**
         * Builds the immutable authenticator.
         *
         * @return the authenticator
         * @throws IllegalStateException without a key, an issuer or an audience
         */
        public JwtAuthenticator build() {
            if (keysById.isEmpty() && keysWithoutId.isEmpty()) { throw new IllegalStateException("At least one key is required"); }
            if (issuers.isEmpty()) { throw new IllegalStateException("At least one issuer is required"); }
            if (audiences.isEmpty()) { throw new IllegalStateException("At least one audience is required"); }
            return new JwtAuthenticator(this);
        }

        private Builder add(String keyId, JwsAlgorithm algorithm, Key key) {
            var entry = new Entry(algorithm, key);
            if (keyId == null) {
                if (keysWithoutId.putIfAbsent(algorithm, entry) != null) {
                    throw new IllegalArgumentException("A key without key ID is already registered for " + algorithm);
                }
            } else if (keysById.putIfAbsent(requireText(keyId, "keyId"), entry) != null) {
                throw new IllegalArgumentException("Duplicate key ID");
            }
            return this;
        }

        private static Key hmac(JwsAlgorithm algorithm, byte[] secret) {
            Objects.requireNonNull(algorithm, "algorithm");
            Objects.requireNonNull(secret, "secret");
            if (algorithm.family() != JwsAlgorithm.Family.HMAC) {
                throw new IllegalArgumentException(algorithm + " is not an HMAC algorithm");
            }
            if (secret.length < algorithm.size()) {
                throw new IllegalArgumentException(algorithm + " needs a secret of at least " + algorithm.size() + " bytes");
            }
            return new SecretKeySpec(secret.clone(), algorithm.jcaName());
        }

        private static Key asymmetric(JwsAlgorithm algorithm, PublicKey key) {
            Objects.requireNonNull(algorithm, "algorithm");
            Objects.requireNonNull(key, "key");
            switch (algorithm.family()) {
                case RSA, RSA_PSS -> {
                    if (!(key instanceof RSAPublicKey rsa)) { throw new IllegalArgumentException(algorithm + " needs an RSA public key"); }
                    if (rsa.getModulus().bitLength() < 2048) { throw new IllegalArgumentException("RSA keys need at least 2048 bits"); }
                }
                case EC -> {
                    if (!(key instanceof ECPublicKey ec) || !sameCurve(ec.getParams(), curve(algorithm))) {
                        throw new IllegalArgumentException(algorithm + " needs an EC public key on " + curve(algorithm));
                    }
                }
                case EDDSA -> {
                    if (!(key instanceof EdECPublicKey ed) || !(ed.getParams() instanceof NamedParameterSpec named)
                            || !named.getName().equals("Ed25519")) {
                        throw new IllegalArgumentException(algorithm + " needs an Ed25519 public key");
                    }
                }
                case HMAC -> throw new IllegalArgumentException(algorithm + " needs a secret, not a public key");
            }
            return key;
        }

        private static String curve(JwsAlgorithm algorithm) {
            return switch (algorithm) {
                case ES256 -> "secp256r1";
                case ES384 -> "secp384r1";
                default -> "secp521r1";
            };
        }

        private static boolean sameCurve(ECParameterSpec actual, String name) {
            try {
                var parameters = AlgorithmParameters.getInstance("EC");
                parameters.init(new ECGenParameterSpec(name));
                var expected = parameters.getParameterSpec(ECParameterSpec.class);
                return actual.getCurve().equals(expected.getCurve()) && actual.getGenerator().equals(expected.getGenerator())
                        && actual.getOrder().equals(expected.getOrder()) && actual.getCofactor() == expected.getCofactor();
            } catch (GeneralSecurityException unsupported) {
                throw new IllegalStateException("The JDK does not support curve " + name, unsupported);
            }
        }

        private static String requireText(String value, String name) {
            if (Objects.requireNonNull(value, name).isEmpty()) { throw new IllegalArgumentException(name + " is empty"); }
            return value;
        }
    }
}
