package com.jsgalactic.axiom.security.jwt;

import static com.jsgalactic.axiom.security.jwt.JwtAuthenticatorTest.base;
import static com.jsgalactic.axiom.security.jwt.JwtAuthenticatorTest.rejects;
import static com.jsgalactic.axiom.security.jwt.Tokens.b64;
import static com.jsgalactic.axiom.security.jwt.Tokens.claims;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.security.spec.MGF1ParameterSpec;
import org.junit.jupiter.api.Test;

/** RSASSA-PSS and EdDSA, and the JWE decision. */
class JwtAlgorithmsTest {
    private static final JwtAuthenticator PS = base().publicKey(JwsAlgorithm.PS256, Tokens.RSA.getPublic())
            .publicKey(JwsAlgorithm.PS384, Tokens.RSA.getPublic()).publicKey(JwsAlgorithm.PS512, Tokens.RSA.getPublic()).build();
    private static final JwtAuthenticator ED = base().publicKey(JwsAlgorithm.EdDSA, Tokens.ED25519.getPublic()).build();

    private static String ps256(String claims) {
        return Tokens.signPss("{\"alg\":\"PS256\"}", claims, Tokens.RSA.getPrivate(), "SHA-256", MGF1ParameterSpec.SHA256, 32);
    }

    private static String eddsa(String claims) {
        return Tokens.sign("{\"alg\":\"EdDSA\"}", claims, Tokens.ED25519.getPrivate(), "Ed25519");
    }

    @Test
    void acceptsPssTokensForEveryDigest() {
        assertThat(PS.verify(ps256(claims(""))).principal()).isEqualTo("ada");
        assertThat(PS.verify(Tokens.signPss("{\"alg\":\"PS384\"}", claims(""), Tokens.RSA.getPrivate(), "SHA-384",
                MGF1ParameterSpec.SHA384, 48)).principal()).isEqualTo("ada");
        assertThat(PS.verify(Tokens.signPss("{\"alg\":\"PS512\"}", claims(""), Tokens.RSA.getPrivate(), "SHA-512",
                MGF1ParameterSpec.SHA512, 64)).principal()).isEqualTo("ada");
    }

    @Test
    void acceptsEdDsaTokens() {
        assertThat(ED.verify(eddsa(claims(""))).principal()).isEqualTo("ada");
    }

    @Test
    void rejectsForgedAndWrongKeyPssTokens() {
        var parts = ps256(claims("")).split("\\.");
        rejects(PS, parts[0] + "." + b64(claims("\"roles\":[\"admin\"]")) + "." + parts[2], "Signature does not verify");
        var other = Tokens.generate("RSA", 2048, null);
        rejects(PS, Tokens.signPss("{\"alg\":\"PS256\"}", claims(""), other.getPrivate(), "SHA-256", MGF1ParameterSpec.SHA256, 32),
                "Signature");
        rejects(PS, parts[0] + "." + parts[1] + "." + parts[2].substring(0, parts[2].length() - 4), "ignature");
        rejects(PS, parts[0] + "." + parts[1] + ".AAAA", "Signature");
    }

    @Test
    void enforcesTheSaltLengthAndHashesOfRfc7518() {
        // Valid RSASSA-PSS signatures, but not with the parameters the algorithm names.
        rejects(PS, Tokens.signPss("{\"alg\":\"PS256\"}", claims(""), Tokens.RSA.getPrivate(), "SHA-256", MGF1ParameterSpec.SHA256, 20),
                "Signature");
        rejects(PS, Tokens.signPss("{\"alg\":\"PS256\"}", claims(""), Tokens.RSA.getPrivate(), "SHA-256", MGF1ParameterSpec.SHA256, 0),
                "Signature");
        rejects(PS, Tokens.signPss("{\"alg\":\"PS256\"}", claims(""), Tokens.RSA.getPrivate(), "SHA-384", MGF1ParameterSpec.SHA384, 48),
                "Signature");
        rejects(PS, Tokens.signPss("{\"alg\":\"PS256\"}", claims(""), Tokens.RSA.getPrivate(), "SHA-256", MGF1ParameterSpec.SHA1, 32),
                "Signature");
    }

    @Test
    void rejectsForgedWrongKeyAndMalformedEdDsaTokens() {
        var parts = eddsa(claims("")).split("\\.");
        rejects(ED, parts[0] + "." + b64(claims("\"roles\":[\"admin\"]")) + "." + parts[2], "Signature does not verify");
        var other = Tokens.generate("Ed25519", 0, null);
        rejects(ED, Tokens.sign("{\"alg\":\"EdDSA\"}", claims(""), other.getPrivate(), "Ed25519"), "Signature");
        rejects(ED, parts[0] + "." + parts[1] + "." + parts[2].substring(0, parts[2].length() - 4), "ignature"); // short signature
        rejects(ED, parts[0] + "." + parts[1] + "." + b64(new byte[64]), "Signature"); // all zero
        rejects(ED, parts[0] + "." + parts[1] + "." + b64(new byte[65]), "Signature"); // too long
        // An Ed448 signature under an EdDSA header is refused (this module verifies Ed25519 only).
        rejects(ED, Tokens.sign("{\"alg\":\"EdDSA\"}", claims(""), Tokens.ED448.getPrivate(), "Ed448"), "Signature");
    }

    @Test
    void rejectsAlgorithmConfusionWithTheNewAlgorithms() {
        // A PS256 signature never verifies as RS256, and vice versa, even with the same RSA key registered for both.
        var both = base().publicKey("k-ps", JwsAlgorithm.PS256, Tokens.RSA.getPublic())
                .publicKey("k-rs", JwsAlgorithm.RS256, Tokens.RSA.getPublic()).build();
        rejects(both, Tokens.signPss("{\"alg\":\"PS256\",\"kid\":\"k-rs\"}", claims(""), Tokens.RSA.getPrivate(), "SHA-256",
                MGF1ParameterSpec.SHA256, 32), "registered for another algorithm");
        rejects(both, Tokens.sign("{\"alg\":\"RS256\",\"kid\":\"k-ps\"}", claims(""), Tokens.RSA.getPrivate(), "SHA256withRSA"),
                "registered for another algorithm");
        var pssOnly = base().publicKey(JwsAlgorithm.PS256, Tokens.RSA.getPublic()).build();
        rejects(pssOnly, Tokens.sign("{\"alg\":\"RS256\"}", claims(""), Tokens.RSA.getPrivate(), "SHA256withRSA"), "Algorithm is not allowed");
        var rsOnly = base().publicKey(JwsAlgorithm.RS256, Tokens.RSA.getPublic()).build();
        rejects(rsOnly, ps256(claims("")), "Algorithm is not allowed");
        // A PS256 header over an RS256 signature fails on the signature, with the key registered for PS256.
        rejects(pssOnly, Tokens.sign("{\"alg\":\"PS256\"}", claims(""), Tokens.RSA.getPrivate(), "SHA256withRSA"), "Signature");
        // A public key's bytes are never an HMAC secret, for either new key type.
        rejects(ED, Tokens.hmac("{\"alg\":\"HS256\"}", claims(""), Tokens.ED25519.getPublic().getEncoded(), "HmacSHA256"),
                "Algorithm is not allowed");
        rejects(pssOnly, Tokens.hmac("{\"alg\":\"HS256\"}", claims(""), Tokens.RSA.getPublic().getEncoded(), "HmacSHA256"),
                "Algorithm is not allowed");
        // EdDSA and ES256 keys are not interchangeable.
        var ec = base().publicKey("e", JwsAlgorithm.ES256, Tokens.EC256.getPublic()).build();
        rejects(ec, Tokens.sign("{\"alg\":\"EdDSA\",\"kid\":\"e\"}", claims(""), Tokens.ED25519.getPrivate(), "Ed25519"),
                "Algorithm is not allowed");
        var mixed = base().publicKey("e", JwsAlgorithm.ES256, Tokens.EC256.getPublic())
                .publicKey("d", JwsAlgorithm.EdDSA, Tokens.ED25519.getPublic()).build();
        rejects(mixed, Tokens.sign("{\"alg\":\"EdDSA\",\"kid\":\"e\"}", claims(""), Tokens.ED25519.getPrivate(), "Ed25519"),
                "registered for another algorithm");
        rejects(mixed, Tokens.sign("{\"alg\":\"none\",\"kid\":\"d\"}", claims(""), Tokens.ED25519.getPrivate(), "Ed25519"),
                "Algorithm is not allowed");
    }

    @Test
    void checksKeysAgainstTheirAlgorithm() {
        var builder = JwtAuthenticator.builder();
        assertThatIllegalArgumentException().isThrownBy(() -> builder.publicKey(JwsAlgorithm.PS256, Tokens.EC256.getPublic()));
        assertThatIllegalArgumentException().isThrownBy(() -> builder.publicKey(JwsAlgorithm.PS256, Tokens.ED25519.getPublic()));
        assertThatIllegalArgumentException().isThrownBy(() -> builder.publicKey(JwsAlgorithm.PS512, Tokens.generate("RSA", 1024, null).getPublic()));
        assertThatIllegalArgumentException().isThrownBy(() -> builder.publicKey(JwsAlgorithm.EdDSA, Tokens.RSA.getPublic()));
        assertThatIllegalArgumentException().isThrownBy(() -> builder.publicKey(JwsAlgorithm.EdDSA, Tokens.EC256.getPublic()));
        assertThatIllegalArgumentException().isThrownBy(() -> builder.publicKey(JwsAlgorithm.EdDSA, Tokens.ED448.getPublic()));
        assertThatIllegalArgumentException().isThrownBy(() -> builder.publicKey(JwsAlgorithm.RS256, Tokens.ED25519.getPublic()));
        assertThatIllegalArgumentException().isThrownBy(() -> builder.publicKey(JwsAlgorithm.ES256, Tokens.ED25519.getPublic()));
        assertThatIllegalArgumentException().isThrownBy(() -> builder.hmacKey(JwsAlgorithm.PS256, new byte[64]));
        assertThatIllegalArgumentException().isThrownBy(() -> builder.hmacKey(JwsAlgorithm.EdDSA, new byte[64]));
    }

    // Encrypted tokens (JWE) are a documented non-goal: they are always rejected with 401 invalid_token.

    @Test
    void rejectsEncryptedTokensInCompactSerialization() {
        var jwe = String.join(".", b64("{\"alg\":\"RSA-OAEP\",\"enc\":\"A256GCM\"}"), b64("key"), b64("iv"), b64("ciphertext"), b64("tag"));
        rejects(PS, jwe, "three-part");
        rejects(ED, jwe, "three-part");
        // A signed token whose header claims encryption is refused as well, with a key registered for its algorithm.
        rejects(PS, Tokens.signPss("{\"alg\":\"PS256\",\"enc\":\"A256GCM\"}", claims(""), Tokens.RSA.getPrivate(), "SHA-256",
                MGF1ParameterSpec.SHA256, 32), "Encrypted");
        rejects(ED, Tokens.sign("{\"alg\":\"EdDSA\",\"enc\":\"A256GCM\"}", claims(""), Tokens.ED25519.getPrivate(), "Ed25519"), "Encrypted");
        // Key-management algorithms are not signature algorithms.
        for (var alg : new String[] {"dir", "RSA-OAEP", "RSA-OAEP-256", "A128KW", "A256GCMKW", "ECDH-ES"}) {
            rejects(PS, b64("{\"alg\":\"" + alg + "\",\"enc\":\"A256GCM\"}") + "." + b64(claims("")) + "." + b64("x"), "Algorithm is not allowed");
        }
    }
}
