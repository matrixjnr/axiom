package com.jsgalactic.axiom.security.jwt;

/**
 * The JWS signature algorithms {@link JwtAuthenticator} can verify, all implemented by the JDK.
 * {@code none} is deliberately absent: an unsigned token is never accepted. A token's {@code alg}
 * header must name one of these and match the algorithm its verification key was registered for.
 */
public enum JwsAlgorithm {
    /** HMAC with SHA-256; a secret of at least 32 bytes. */
    HS256("HmacSHA256", Family.HMAC, 32),
    /** HMAC with SHA-384; a secret of at least 48 bytes. */
    HS384("HmacSHA384", Family.HMAC, 48),
    /** HMAC with SHA-512; a secret of at least 64 bytes. */
    HS512("HmacSHA512", Family.HMAC, 64),
    /** RSASSA-PKCS1-v1_5 with SHA-256; an RSA public key of at least 2048 bits. */
    RS256("SHA256withRSA", Family.RSA, 0),
    /** RSASSA-PKCS1-v1_5 with SHA-384; an RSA public key of at least 2048 bits. */
    RS384("SHA384withRSA", Family.RSA, 0),
    /** RSASSA-PKCS1-v1_5 with SHA-512; an RSA public key of at least 2048 bits. */
    RS512("SHA512withRSA", Family.RSA, 0),
    /** ECDSA on P-256 with SHA-256. */
    ES256("SHA256withECDSAinP1363Format", Family.EC, 32),
    /** ECDSA on P-384 with SHA-384. */
    ES384("SHA384withECDSAinP1363Format", Family.EC, 48),
    /** ECDSA on P-521 with SHA-512. */
    ES512("SHA512withECDSAinP1363Format", Family.EC, 66);

    /** Key families; a key of one family is never used with an algorithm of another. */
    enum Family { HMAC, RSA, EC }

    private final String jcaName;
    private final Family family;
    private final int size;

    JwsAlgorithm(String jcaName, Family family, int size) {
        this.jcaName = jcaName;
        this.family = family;
        this.size = size;
    }

    String jcaName() { return jcaName; }

    Family family() { return family; }

    /** HMAC: minimum secret bytes. EC: bytes of one signature component (R or S). */
    int size() { return size; }
}
