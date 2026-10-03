package com.jsgalactic.axiom.lifecycle;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;
import javax.net.ssl.SSLContext;

/**
 * TLS settings of one HTTP listener, passed with
 * {@link ListenerOptions.Builder#tls(TlsOptions)}. A listener with TLS options speaks HTTPS and
 * nothing else on its port; a client that sends plain text is disconnected.
 *
 * <p><b>Key material</b> comes from exactly one source: a PEM certificate chain file and an
 * unencrypted PKCS#8 PEM private key file ({@link #pem(Path, Path)}), or an {@link SSLContext}
 * supplier ({@link Builder#sslContext(Supplier)}) for keys that live in a keystore, a hardware
 * module or a secret manager. Material is validated when the listener starts, so a missing file, a
 * malformed PEM, a key that does not match the certificate or an expired certificate stops startup
 * with a {@link TlsConfigurationException} and never prints key content. The files are read once
 * at startup and again by {@link Server#reloadTls()} or the optional polling (see
 * {@link Builder#reloadInterval(Duration)}); a reload swaps the material for new connections
 * atomically and leaves open connections alone. A reload that fails keeps the previous material.
 *
 * <p><b>Protocols and ciphers.</b> TLS 1.2 is the default minimum and TLS 1.3 is used whenever the
 * client supports it. Only forward-secret authenticated-encryption suites are enabled by default
 * (see {@link #DEFAULT_CIPHER_SUITES}). The listener advertises ALPN {@code http/1.1} only.
 *
 * <p><b>Client certificates</b> are not requested by default.
 * {@link Builder#requireClientCertificates(Path)} turns on mutual TLS: a client without a
 * certificate issued by one of the trusted certificates is refused during the handshake.
 *
 * <p>Instances are immutable and may be shared by several listeners.
 */
public final class TlsOptions {
    /** The protocols a listener can be limited to, oldest first. */
    public enum Protocol {
        /** TLS 1.2. */
        TLS_1_2("TLSv1.2"),
        /** TLS 1.3. */
        TLS_1_3("TLSv1.3");

        private final String jdkName;

        Protocol(String jdkName) { this.jdkName = jdkName; }

        /** @return the protocol name that {@code javax.net.ssl} uses, such as {@code TLSv1.3} */
        public String jdkName() { return jdkName; }
    }

    /** Whether the listener asks clients for a certificate. */
    public enum ClientAuth {
        /** No client certificate is requested. */
        NONE,
        /** The client must present a trusted certificate or the handshake fails. */
        REQUIRED
    }

    /**
     * The cipher suites enabled by default: TLS 1.3 suites and, for TLS 1.2, ECDHE key exchange
     * with AES-GCM or ChaCha20-Poly1305. Suites the JVM does not support are skipped.
     */
    public static final List<String> DEFAULT_CIPHER_SUITES = List.of(
            "TLS_AES_256_GCM_SHA384", "TLS_AES_128_GCM_SHA256", "TLS_CHACHA20_POLY1305_SHA256",
            "TLS_ECDHE_ECDSA_WITH_AES_256_GCM_SHA384", "TLS_ECDHE_RSA_WITH_AES_256_GCM_SHA384",
            "TLS_ECDHE_ECDSA_WITH_CHACHA20_POLY1305_SHA256", "TLS_ECDHE_RSA_WITH_CHACHA20_POLY1305_SHA256",
            "TLS_ECDHE_ECDSA_WITH_AES_128_GCM_SHA256", "TLS_ECDHE_RSA_WITH_AES_128_GCM_SHA256");
    private static final Duration MIN_RELOAD = Duration.ofSeconds(1);
    private static final Duration MAX_RELOAD = Duration.ofDays(1);

    private final Path certificateChain;
    private final Path privateKey;
    private final Supplier<SSLContext> sslContext;
    private final Protocol minimumProtocol;
    private final List<String> cipherSuites;
    private final ClientAuth clientAuth;
    private final Path clientTrust;
    private final Duration reloadInterval;
    private final Clock clock;

    private TlsOptions(Builder b) {
        certificateChain = b.certificateChain;
        privateKey = b.privateKey;
        sslContext = b.sslContext;
        minimumProtocol = b.minimumProtocol;
        cipherSuites = List.copyOf(b.cipherSuites);
        clientAuth = b.clientAuth;
        clientTrust = b.clientTrust;
        reloadInterval = b.reloadInterval;
        clock = b.clock;
    }

    /**
     * Options that serve a PEM certificate chain and private key with every other setting at its
     * default.
     * @param certificateChain PEM file holding the server certificate first, then its issuers
     * @param privateKey PEM file holding the matching unencrypted PKCS#8 private key
     * @return the options
     * @throws NullPointerException if a path is null
     */
    public static TlsOptions pem(Path certificateChain, Path privateKey) {
        return builder().certificateChain(certificateChain).privateKey(privateKey).build();
    }

    /**
     * Starts a builder with every setting at its default and no key material.
     * @return a new builder
     */
    public static Builder builder() { return new Builder(); }

    /**
     * Starts a builder holding these options' values.
     * @return a new builder
     */
    public Builder toBuilder() {
        var b = new Builder();
        b.certificateChain = certificateChain;
        b.privateKey = privateKey;
        b.sslContext = sslContext;
        b.minimumProtocol = minimumProtocol;
        b.cipherSuites = cipherSuites;
        b.clientAuth = clientAuth;
        b.clientTrust = clientTrust;
        b.reloadInterval = reloadInterval;
        b.clock = clock;
        return b;
    }

    /** @return the PEM certificate chain file, or empty when an SSL context supplier is used */
    public Optional<Path> certificateChain() { return Optional.ofNullable(certificateChain); }
    /** @return the PEM private key file, or empty when an SSL context supplier is used */
    public Optional<Path> privateKey() { return Optional.ofNullable(privateKey); }
    /** @return the SSL context supplier, or empty when PEM files are used */
    public Optional<Supplier<SSLContext>> sslContext() { return Optional.ofNullable(sslContext); }
    /** @return the oldest TLS version a client may use */
    public Protocol minimumProtocol() { return minimumProtocol; }
    /** @return the enabled cipher suites in preference order */
    public List<String> cipherSuites() { return cipherSuites; }
    /** @return whether clients must present a certificate */
    public ClientAuth clientAuth() { return clientAuth; }
    /** @return the PEM file of certificates that client certificates must chain to, if any */
    public Optional<Path> clientTrust() { return Optional.ofNullable(clientTrust); }
    /** @return how often the key files are checked for changes, or empty when they are not polled */
    public Optional<Duration> reloadInterval() { return Optional.ofNullable(reloadInterval); }
    /** @return the clock that decides whether a certificate is valid */
    public Clock clock() { return clock; }

    /** Describes the options without file names or key material. */
    @Override public String toString() {
        return "TlsOptions[source=" + (sslContext != null ? "sslContext" : "pem") + ", minimumProtocol="
                + minimumProtocol + ", cipherSuites=" + cipherSuites.size() + ", clientAuth=" + clientAuth
                + ", reloadInterval=" + reloadInterval + "]";
    }

    /**
     * Collects settings before {@link #build()}. Not thread-safe; the built options are immutable.
     */
    public static final class Builder {
        private Path certificateChain;
        private Path privateKey;
        private Supplier<SSLContext> sslContext;
        private Protocol minimumProtocol = Protocol.TLS_1_2;
        private List<String> cipherSuites = DEFAULT_CIPHER_SUITES;
        private ClientAuth clientAuth = ClientAuth.NONE;
        private Path clientTrust;
        private Duration reloadInterval;
        private Clock clock = Clock.systemUTC();

        private Builder() { }

        /**
         * Sets the PEM file holding the server certificate followed by its intermediate issuers.
         * @param file the certificate chain file
         * @return this builder
         */
        public Builder certificateChain(Path file) {
            certificateChain = Objects.requireNonNull(file, "certificateChain");
            return this;
        }

        /**
         * Sets the PEM file holding the private key of the server certificate. The key must be
         * unencrypted PKCS#8 ({@code BEGIN PRIVATE KEY}) with an RSA, EC or Ed25519 key.
         * @param file the private key file
         * @return this builder
         */
        public Builder privateKey(Path file) {
            privateKey = Objects.requireNonNull(file, "privateKey");
            return this;
        }

        /**
         * Takes the key material from an {@link SSLContext}, an alternative to the PEM files. The
         * supplier is called at startup and on each reload, and may return a new context each time.
         * The listener applies its own protocol, cipher suite, ALPN and client authentication
         * settings to every connection; the context supplies the keys and, with
         * {@link #requireClientCertificates()}, the trust anchors.
         * @param supplier produces the context; must not return null
         * @return this builder
         */
        public Builder sslContext(Supplier<SSLContext> supplier) {
            sslContext = Objects.requireNonNull(supplier, "sslContext");
            return this;
        }

        /**
         * Sets the oldest TLS version a client may negotiate. The default is TLS 1.2, which is also
         * the oldest version this API allows.
         * @param protocol the minimum version
         * @return this builder
         */
        public Builder minimumProtocol(Protocol protocol) {
            minimumProtocol = Objects.requireNonNull(protocol, "minimumProtocol");
            return this;
        }

        /**
         * Replaces the enabled cipher suites, in preference order. Names are JSSE standard names.
         * Suites the JVM does not support are skipped, and at least one must remain.
         * @param suites one or more suite names
         * @return this builder
         * @throws IllegalArgumentException if empty or a name is blank
         */
        public Builder cipherSuites(List<String> suites) {
            Objects.requireNonNull(suites, "cipherSuites");
            if (suites.isEmpty() || suites.stream().anyMatch(s -> s == null || s.isBlank())) {
                throw new IllegalArgumentException("cipherSuites must hold at least one non-blank name");
            }
            cipherSuites = List.copyOf(suites);
            return this;
        }

        /**
         * Turns on mutual TLS for PEM key material: clients must present a certificate that chains
         * to one of the certificates in the file, or the handshake fails.
         * @param trustedCertificates PEM file of the trusted client certificates or issuing authorities
         * @return this builder
         */
        public Builder requireClientCertificates(Path trustedCertificates) {
            clientAuth = ClientAuth.REQUIRED;
            clientTrust = Objects.requireNonNull(trustedCertificates, "trustedCertificates");
            return this;
        }

        /**
         * Turns on mutual TLS for an {@link #sslContext(Supplier) SSL context} whose trust managers
         * decide which client certificates are accepted.
         * @return this builder
         */
        public Builder requireClientCertificates() {
            clientAuth = ClientAuth.REQUIRED;
            clientTrust = null;
            return this;
        }

        /**
         * Checks the key files for changes at this interval and reloads them when one changed, as
         * {@link Server#reloadTls()} does. A reload that fails is logged and counted, keeps the
         * previous material, and is tried again at the next check. Off by default. It applies to
         * PEM files only.
         * @param interval 1 second to one day
         * @return this builder
         * @throws IllegalArgumentException if out of range
         */
        public Builder reloadInterval(Duration interval) {
            Objects.requireNonNull(interval, "reloadInterval");
            if (interval.compareTo(MIN_RELOAD) < 0 || interval.compareTo(MAX_RELOAD) > 0) {
                throw new IllegalArgumentException("reloadInterval must be from " + MIN_RELOAD + " to " + MAX_RELOAD
                        + ": " + interval);
            }
            reloadInterval = interval;
            return this;
        }

        /**
         * Sets the clock that decides whether certificates are expired or not yet valid. Tests
         * inject a fixed clock; the default is the system UTC clock.
         * @param clock the clock
         * @return this builder
         */
        public Builder clock(Clock clock) {
            this.clock = Objects.requireNonNull(clock, "clock");
            return this;
        }

        /**
         * Builds immutable options.
         * @return the options
         * @throws IllegalStateException if the key material is not exactly one of PEM files (both a
         *         chain and a key) or an SSL context supplier, if a client trust file is combined
         *         with an SSL context, or if polling is combined with an SSL context
         */
        public TlsOptions build() {
            boolean pem = certificateChain != null || privateKey != null;
            if (pem == (sslContext != null)) {
                throw new IllegalStateException("Set either certificateChain and privateKey, or sslContext");
            }
            if (pem && (certificateChain == null || privateKey == null)) {
                throw new IllegalStateException("certificateChain and privateKey must be set together");
            }
            if (pem && clientAuth == ClientAuth.REQUIRED && clientTrust == null) {
                throw new IllegalStateException("PEM key material needs a trusted certificates file "
                        + "for requireClientCertificates(Path)");
            }
            if (sslContext != null && clientTrust != null) {
                throw new IllegalStateException("Client trust files apply to PEM key material; "
                        + "an sslContext decides trust itself");
            }
            if (sslContext != null && reloadInterval != null) {
                throw new IllegalStateException("reloadInterval applies to PEM files; call Server.reloadTls() "
                        + "to refresh an sslContext");
            }
            return new TlsOptions(this);
        }
    }
}
