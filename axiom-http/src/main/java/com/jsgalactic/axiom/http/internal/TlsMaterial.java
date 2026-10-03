package com.jsgalactic.axiom.http.internal;

import com.jsgalactic.axiom.lifecycle.TlsConfigurationException;
import com.jsgalactic.axiom.lifecycle.TlsOptions;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.SecureRandom;
import java.security.Signature;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Collections;
import java.util.Date;
import java.util.HexFormat;
import java.util.List;
import java.util.regex.Pattern;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.TrustManagerFactory;

/**
 * Validated key material of a TLS listener: an {@link SSLContext} plus the protocol, cipher suite,
 * ALPN and client authentication settings that every connection's engine gets. Immutable; a reload
 * builds a new instance and the listener swaps its reference, so a connection that is being set up
 * sees either the old or the new material, never a mix.
 *
 * <p>Every failure is a {@link TlsConfigurationException} whose message names the file or the
 * problem. Nothing read from a key file, and no exception from parsing one, reaches a message.
 */
final class TlsMaterial {
    /** The only application protocol the listener offers; HTTP/2 is not served. */
    static final String ALPN = "http/1.1";
    private static final long MAX_PEM_BYTES = 1 << 20;
    private static final Pattern PEM = Pattern.compile(
            "-----BEGIN ([A-Z0-9 ]+)-----([A-Za-z0-9+/=\\s]*)-----END \\1-----");
    private static final char[] STORE_PASSWORD = "axiom".toCharArray();

    private final SSLContext context;
    private final String[] protocols;
    private final String[] cipherSuites;
    private final boolean clientAuth;
    private final String fingerprint;

    private TlsMaterial(SSLContext context, String[] protocols, String[] cipherSuites, boolean clientAuth,
            String fingerprint) {
        this.context = context;
        this.protocols = protocols;
        this.cipherSuites = cipherSuites;
        this.clientAuth = clientAuth;
        this.fingerprint = fingerprint;
    }

    /**
     * Reads and validates the material the options describe.
     * @throws TlsConfigurationException if it cannot be used
     */
    static TlsMaterial load(TlsOptions options) throws TlsConfigurationException {
        var now = Date.from(options.clock().instant());
        SSLContext context;
        String fingerprint;
        if (options.sslContext().isPresent()) {
            try {
                context = options.sslContext().get().get();
            } catch (RuntimeException failure) {
                throw new TlsConfigurationException("The sslContext supplier failed with "
                        + failure.getClass().getSimpleName());
            }
            if (context == null) { throw new TlsConfigurationException("The sslContext supplier returned null"); }
            fingerprint = "";
        } else {
            var chainPath = options.certificateChain().orElseThrow();
            var keyPath = options.privateKey().orElseThrow();
            var chainBytes = read(chainPath);
            var keyBytes = read(keyPath);
            var trustPath = options.clientTrust();
            var trustBytes = trustPath.isPresent() ? read(trustPath.get()) : null;
            var chain = certificates(chainPath, chainBytes);
            checkValidity(chainPath, chain, now);
            var key = privateKey(keyPath, keyBytes, chain.getFirst());
            context = context(chain, key, trustPath.isPresent() ? certificates(trustPath.get(), trustBytes) : null,
                    trustPath.orElse(null), now);
            fingerprint = fingerprint(chainBytes, keyBytes, trustBytes);
        }
        return configure(context, options, fingerprint);
    }

    /**
     * Reads the files the options name and returns the fingerprint {@link #load} would record, or
     * null when a file cannot be read; used to notice changed files without parsing them.
     */
    static String currentFingerprint(TlsOptions options) {
        if (options.certificateChain().isEmpty()) { return null; }
        try {
            var trust = options.clientTrust();
            return fingerprint(read(options.certificateChain().get()), read(options.privateKey().orElseThrow()),
                    trust.isPresent() ? read(trust.get()) : null);
        } catch (TlsConfigurationException unreadable) {
            return null;
        }
    }

    /** Digest of the bytes the material was built from; empty for a supplied SSL context. */
    String fingerprint() { return fingerprint; }

    /** Returns a server-mode engine with this material's settings, ALPN {@code http/1.1} only. */
    SSLEngine newEngine() {
        var engine = context.createSSLEngine();
        engine.setUseClientMode(false);
        var parameters = engine.getSSLParameters();
        parameters.setProtocols(protocols);
        parameters.setCipherSuites(cipherSuites);
        parameters.setApplicationProtocols(new String[] {ALPN});
        parameters.setUseCipherSuitesOrder(true);
        // Either call replaces the other, so only one is made: without mutual TLS no certificate is requested.
        if (clientAuth) { parameters.setNeedClientAuth(true); } else { parameters.setWantClientAuth(false); }
        engine.setSSLParameters(parameters);
        return engine;
    }

    private static TlsMaterial configure(SSLContext context, TlsOptions options, String fingerprint)
            throws TlsConfigurationException {
        SSLEngine probe;
        try {
            probe = context.createSSLEngine();
        } catch (RuntimeException failure) {
            throw new TlsConfigurationException("The SSL context cannot create engines");
        }
        var supportedProtocols = List.of(probe.getSupportedProtocols());
        var protocols = new ArrayList<String>();
        for (var protocol : TlsOptions.Protocol.values()) {
            if (protocol.compareTo(options.minimumProtocol()) >= 0 && supportedProtocols.contains(protocol.jdkName())) {
                protocols.add(protocol.jdkName());
            }
        }
        Collections.reverse(protocols); // TLS 1.3 first; the enum runs oldest first.
        if (protocols.isEmpty()) {
            throw new TlsConfigurationException("This JVM supports no TLS version from "
                    + options.minimumProtocol().jdkName() + " upwards");
        }
        var supportedSuites = List.of(probe.getSupportedCipherSuites());
        var suites = options.cipherSuites().stream().filter(supportedSuites::contains).distinct().toList();
        if (suites.isEmpty()) {
            throw new TlsConfigurationException("None of the configured cipher suites is supported by this JVM");
        }
        return new TlsMaterial(context, protocols.toArray(String[]::new), suites.toArray(String[]::new),
                options.clientAuth() == TlsOptions.ClientAuth.REQUIRED, fingerprint);
    }

    private static SSLContext context(List<X509Certificate> chain, PrivateKey key, List<X509Certificate> trust,
            Path trustPath, Date now) throws TlsConfigurationException {
        try {
            var keys = KeyStore.getInstance("PKCS12");
            keys.load(null, null);
            keys.setKeyEntry("server", key, STORE_PASSWORD, chain.toArray(new X509Certificate[0]));
            var keyManagers = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
            keyManagers.init(keys, STORE_PASSWORD);
            javax.net.ssl.TrustManager[] trustManagers = null;
            if (trust != null) {
                checkValidity(trustPath, trust, now);
                var anchors = KeyStore.getInstance("PKCS12");
                anchors.load(null, null);
                for (int i = 0; i < trust.size(); i++) { anchors.setCertificateEntry("client-" + i, trust.get(i)); }
                var factory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
                factory.init(anchors);
                trustManagers = factory.getTrustManagers();
            }
            var context = SSLContext.getInstance("TLS");
            context.init(keyManagers.getKeyManagers(), trustManagers, new SecureRandom());
            return context;
        } catch (TlsConfigurationException invalid) {
            throw invalid;
        } catch (GeneralSecurityException | IOException failure) {
            throw new TlsConfigurationException("The TLS key material cannot be loaded into a key store");
        }
    }

    private static List<X509Certificate> certificates(Path file, byte[] content) throws TlsConfigurationException {
        var chain = new ArrayList<X509Certificate>();
        var matcher = PEM.matcher(new String(content, StandardCharsets.US_ASCII));
        try {
            var factory = CertificateFactory.getInstance("X.509");
            while (matcher.find()) {
                if (!matcher.group(1).equals("CERTIFICATE")) { continue; }
                var der = Base64.getMimeDecoder().decode(matcher.group(2));
                chain.add((X509Certificate) factory.generateCertificate(new ByteArrayInputStream(der)));
            }
        } catch (GeneralSecurityException | IllegalArgumentException malformed) {
            throw new TlsConfigurationException(file + " holds a CERTIFICATE block that is not a valid X.509 certificate");
        }
        if (chain.isEmpty()) { throw new TlsConfigurationException(file + " contains no PEM CERTIFICATE block"); }
        return chain;
    }

    private static void checkValidity(Path file, List<X509Certificate> certificates, Date now)
            throws TlsConfigurationException {
        for (var certificate : certificates) {
            if (now.after(certificate.getNotAfter())) {
                throw new TlsConfigurationException("A certificate in " + file + " expired at "
                        + certificate.getNotAfter().toInstant() + " (subject " + certificate.getSubjectX500Principal().getName() + ")");
            }
            if (now.before(certificate.getNotBefore())) {
                throw new TlsConfigurationException("A certificate in " + file + " is not valid before "
                        + certificate.getNotBefore().toInstant() + " (subject " + certificate.getSubjectX500Principal().getName() + ")");
            }
        }
    }

    private static PrivateKey privateKey(Path file, byte[] content, X509Certificate leaf)
            throws TlsConfigurationException {
        var matcher = PEM.matcher(new String(content, StandardCharsets.US_ASCII));
        byte[] der = null;
        while (matcher.find()) {
            switch (matcher.group(1)) {
                case "PRIVATE KEY" -> {
                    if (der != null) { throw new TlsConfigurationException(file + " holds more than one private key"); }
                    try {
                        der = Base64.getMimeDecoder().decode(matcher.group(2));
                    } catch (IllegalArgumentException malformed) {
                        throw new TlsConfigurationException(file + " holds a PRIVATE KEY block that is not valid Base64");
                    }
                }
                case "ENCRYPTED PRIVATE KEY" -> throw new TlsConfigurationException(file
                        + " is an encrypted key; supply an unencrypted PKCS#8 key (BEGIN PRIVATE KEY)");
                case "RSA PRIVATE KEY", "EC PRIVATE KEY" -> throw new TlsConfigurationException(file
                        + " is a traditional-format key; convert it to PKCS#8 (BEGIN PRIVATE KEY)");
                default -> { /* Certificates and other blocks in the same file are ignored. */ }
            }
        }
        if (der == null) { throw new TlsConfigurationException(file + " contains no PEM PRIVATE KEY block"); }
        var algorithm = leaf.getPublicKey().getAlgorithm();
        var kind = switch (algorithm) {
            case "RSA" -> "RSA";
            case "EC" -> "EC";
            case "EdDSA", "Ed25519" -> "Ed25519";
            default -> throw new TlsConfigurationException("The certificate key type " + algorithm
                    + " is not supported; use RSA, EC or Ed25519");
        };
        PrivateKey key;
        try {
            key = KeyFactory.getInstance(kind).generatePrivate(new PKCS8EncodedKeySpec(der));
        } catch (GeneralSecurityException mismatch) {
            throw new TlsConfigurationException("The private key in " + file + " is not a valid " + kind
                    + " key for the certificate");
        } finally {
            Arrays.fill(der, (byte) 0);
        }
        if (!matches(key, leaf, kind)) {
            throw new TlsConfigurationException("The private key in " + file + " does not belong to the certificate");
        }
        return key;
    }

    /** Signs a random challenge with the key and checks it with the certificate's public key. */
    private static boolean matches(PrivateKey key, X509Certificate leaf, String kind) throws TlsConfigurationException {
        try {
            var algorithm = switch (kind) {
                case "RSA" -> "SHA256withRSA";
                case "EC" -> "SHA256withECDSA";
                default -> "Ed25519";
            };
            var challenge = new byte[32];
            new SecureRandom().nextBytes(challenge);
            var signer = Signature.getInstance(algorithm);
            signer.initSign(key);
            signer.update(challenge);
            var signature = signer.sign();
            var verifier = Signature.getInstance(algorithm);
            verifier.initVerify(leaf.getPublicKey());
            verifier.update(challenge);
            return verifier.verify(signature);
        } catch (GeneralSecurityException failure) {
            return false;
        }
    }

    private static byte[] read(Path file) throws TlsConfigurationException {
        long size;
        try {
            if (!Files.exists(file)) { throw new TlsConfigurationException(file + " does not exist"); }
            size = Files.size(file);
        } catch (TlsConfigurationException missing) {
            throw missing;
        } catch (IOException | SecurityException unreadable) {
            throw new TlsConfigurationException(file + " cannot be read");
        }
        if (size > MAX_PEM_BYTES) { throw new TlsConfigurationException(file + " is larger than 1 MiB"); }
        try {
            return Files.readAllBytes(file);
        } catch (IOException | SecurityException unreadable) {
            throw new TlsConfigurationException(file + " cannot be read");
        }
    }

    private static String fingerprint(byte[]... contents) {
        try {
            var digest = MessageDigest.getInstance("SHA-256");
            for (var content : contents) {
                digest.update(content == null ? new byte[] {0} : content);
                digest.update((byte) 0xff);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (GeneralSecurityException impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}
