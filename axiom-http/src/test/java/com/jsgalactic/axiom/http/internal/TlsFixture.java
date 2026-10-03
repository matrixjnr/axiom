package com.jsgalactic.axiom.http.internal;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.cert.X509Certificate;
import java.util.Base64;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.TimeUnit;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;

/**
 * Self-signed identities for TLS tests, generated with the JDK's own {@code keytool} (no third-party
 * library) and written as the PEM files a listener reads. A temporary directory holds everything and
 * is removed on close.
 */
final class TlsFixture implements AutoCloseable {
    private static final char[] PASSWORD = "changeit".toCharArray();
    private final Path directory;
    private int sequence;

    /** One generated identity: PEM files plus the parsed certificate and key. */
    record Identity(Path chain, Path key, Path keystore, X509Certificate certificate) { }

    TlsFixture() throws IOException { directory = Files.createTempDirectory("axiom-tls"); }

    Path directory() { return directory; }

    /** A self-signed EC certificate for localhost, valid for the next ten years. */
    Identity identity(String name) throws Exception { return identity(name, "-0d", 3650); }

    /**
     * A self-signed certificate.
     * @param startOffset keytool {@code -startdate} offset such as {@code -3d}
     * @param validityDays days of validity counted from the start
     */
    Identity identity(String name, String startOffset, int validityDays) throws Exception {
        var keystore = directory.resolve(name + "-" + (sequence++) + ".p12");
        var keytool = Path.of(System.getProperty("java.home"), "bin", "keytool").toString();
        var command = List.of(keytool, "-genkeypair", "-alias", "id", "-keyalg", "EC", "-groupname", "secp256r1",
                "-sigalg", "SHA256withECDSA", "-dname", "CN=" + name, "-ext", "san=dns:localhost,ip:127.0.0.1",
                "-validity", Integer.toString(validityDays), "-startdate", startOffset, "-keystore", keystore.toString(),
                "-storetype", "PKCS12", "-storepass", new String(PASSWORD), "-keypass", new String(PASSWORD));
        var process = new ProcessBuilder(command).redirectErrorStream(true).start();
        byte[] output;
        try (InputStream in = process.getInputStream()) { output = in.readAllBytes(); }
        if (!process.waitFor(60, TimeUnit.SECONDS) || process.exitValue() != 0) {
            throw new IOException("keytool failed: " + new String(output, StandardCharsets.UTF_8));
        }
        var store = KeyStore.getInstance("PKCS12");
        try (InputStream in = Files.newInputStream(keystore)) { store.load(in, PASSWORD); }
        var certificate = (X509Certificate) store.getCertificate("id");
        var chain = directory.resolve(name + "-chain-" + sequence + ".pem");
        var key = directory.resolve(name + "-key-" + sequence + ".pem");
        write(chain, pem("CERTIFICATE", certificate.getEncoded()));
        write(key, pem("PRIVATE KEY", store.getKey("id", PASSWORD).getEncoded()));
        return new Identity(chain, key, keystore, certificate);
    }

    /** Copies an identity's PEM files over the given paths, as a certificate renewal would. */
    static void install(Identity identity, Path chain, Path key) throws IOException {
        Files.copy(identity.chain(), chain, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        Files.copy(identity.key(), key, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
    }

    /** A PEM file of the given certificates. */
    Path certificates(String name, X509Certificate... certificates) throws Exception {
        var file = directory.resolve(name + ".pem");
        var text = new StringBuilder();
        for (var certificate : certificates) { text.append(pem("CERTIFICATE", certificate.getEncoded())); }
        write(file, text.toString());
        return file;
    }

    /** A client context that trusts only the given server certificate and presents the identity, if any. */
    static SSLContext client(X509Certificate trusted, Identity presented) throws Exception {
        var anchors = KeyStore.getInstance("PKCS12");
        anchors.load(null, null);
        anchors.setCertificateEntry("server", trusted);
        var trust = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        trust.init(anchors);
        javax.net.ssl.KeyManager[] keys = null;
        if (presented != null) { keys = keyManagers(presented); }
        var context = SSLContext.getInstance("TLS");
        context.init(keys, trust.getTrustManagers(), null);
        return context;
    }

    /** The identity's keystore as key managers, for a server context supplier or a client certificate. */
    static javax.net.ssl.KeyManager[] keyManagers(Identity identity) throws Exception {
        var store = KeyStore.getInstance("PKCS12");
        try (InputStream in = Files.newInputStream(identity.keystore())) { store.load(in, PASSWORD); }
        var factory = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        factory.init(store, PASSWORD);
        return factory.getKeyManagers();
    }

    static String pem(String label, byte[] der) {
        return "-----BEGIN " + label + "-----\n" + Base64.getMimeEncoder(64, "\n".getBytes(StandardCharsets.US_ASCII))
                .encodeToString(der) + "\n-----END " + label + "-----\n";
    }

    private static void write(Path file, String text) throws IOException {
        Files.writeString(file, text, StandardCharsets.US_ASCII);
    }

    @Override public void close() throws IOException {
        try (var files = Files.walk(directory)) {
            for (var file : files.sorted(Comparator.reverseOrder()).toList()) { Files.deleteIfExists(file); }
        }
    }
}
