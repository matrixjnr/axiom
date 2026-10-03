package com.jsgalactic.axiom.security.jwt;

import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.spec.ECGenParameterSpec;
import java.security.spec.MGF1ParameterSpec;
import java.security.spec.PSSParameterSpec;
import java.util.Base64;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/** Mints test tokens with the JDK, independently of the code under test. */
final class Tokens {
    static final byte[] SECRET = "0123456789abcdef0123456789abcdef".getBytes(StandardCharsets.US_ASCII);
    static final KeyPair RSA = generate("RSA", 2048, null);
    static final KeyPair EC256 = generate("EC", 0, "secp256r1");
    static final KeyPair EC384 = generate("EC", 0, "secp384r1");
    static final KeyPair EC521 = generate("EC", 0, "secp521r1");
    static final KeyPair ED25519 = generate("Ed25519", 0, null);
    static final KeyPair ED448 = generate("Ed448", 0, null);
    /** 2026-10-03T12:00:00Z, the fixed clock of the tests. */
    static final long NOW = 1_791_028_800L;

    private Tokens() {}

    static KeyPair generate(String algorithm, int bits, String curve) {
        try {
            var generator = KeyPairGenerator.getInstance(algorithm);
            if (curve != null) {
                generator.initialize(new ECGenParameterSpec(curve));
            } else if (bits > 0) {
                generator.initialize(bits);
            }
            return generator.generateKeyPair();
        } catch (Exception impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    static String b64(String json) {
        return b64(json.getBytes(StandardCharsets.UTF_8));
    }

    static String b64(byte[] bytes) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    /** Claims valid for the default test configuration, with extra members appended. */
    static String claims(String extra) {
        return "{\"iss\":\"https://issuer.test\",\"aud\":\"notes\",\"sub\":\"ada\",\"exp\":" + (NOW + 300)
                + ",\"iat\":" + (NOW - 10) + (extra.isEmpty() ? "" : "," + extra) + "}";
    }

    static String hmac(String header, String claims, byte[] secret, String jca) {
        var input = b64(header) + "." + b64(claims);
        try {
            var mac = Mac.getInstance(jca);
            mac.init(new SecretKeySpec(secret, jca));
            return input + "." + b64(mac.doFinal(input.getBytes(StandardCharsets.US_ASCII)));
        } catch (Exception impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    static String hs256(String claims) {
        return hmac("{\"alg\":\"HS256\",\"typ\":\"JWT\"}", claims, SECRET, "HmacSHA256");
    }

    /** Signs with RSASSA-PSS using the given hash, MGF1 hash and salt length. */
    static String signPss(String header, String claims, PrivateKey key, String digest, MGF1ParameterSpec mgf, int salt) {
        var input = b64(header) + "." + b64(claims);
        try {
            var signature = Signature.getInstance("RSASSA-PSS");
            signature.setParameter(new PSSParameterSpec(digest, "MGF1", mgf, salt, 1));
            signature.initSign(key);
            signature.update(input.getBytes(StandardCharsets.US_ASCII));
            return input + "." + b64(signature.sign());
        } catch (Exception impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    static String sign(String header, String claims, PrivateKey key, String jca) {
        var input = b64(header) + "." + b64(claims);
        try {
            var signature = Signature.getInstance(jca);
            signature.initSign(key);
            signature.update(input.getBytes(StandardCharsets.US_ASCII));
            return input + "." + b64(signature.sign());
        } catch (Exception impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}
