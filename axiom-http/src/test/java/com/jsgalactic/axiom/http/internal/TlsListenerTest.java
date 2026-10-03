package com.jsgalactic.axiom.http.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.jsgalactic.axiom.lifecycle.ListenerOptions;
import com.jsgalactic.axiom.lifecycle.Server;
import com.jsgalactic.axiom.lifecycle.TlsConfigurationException;
import com.jsgalactic.axiom.lifecycle.TlsOptions;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLException;
import javax.net.ssl.SSLSocket;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** A listener with TLS options speaks HTTPS, validates its key material and swaps it on reload. */
@Tag("integration")
class TlsListenerTest {
    private static final InetSocketAddress LOOPBACK = new InetSocketAddress("127.0.0.1", 0);

    private static NettyServer listen(Fixture fixture, ListenerOptions options) throws IOException {
        var server = fixture.app.listen(LOOPBACK, options);
        fixture.servers.add(server);
        return (NettyServer) server;
    }

    private static NettyServer listen(Fixture fixture, TlsOptions tls) throws IOException {
        return listen(fixture, ListenerOptions.builder().tls(tls).build());
    }

    private static SSLSocket connect(Server server, SSLContext context) throws IOException {
        var socket = (SSLSocket) context.getSocketFactory().createSocket(server.localAddress().getAddress(),
                server.localAddress().getPort());
        socket.setSoTimeout(30_000);
        return socket;
    }

    /** One request with {@code Connection: close}; returns everything the server sent. */
    private static String get(SSLSocket socket, String path) throws IOException {
        var out = socket.getOutputStream();
        out.write(("GET " + path + " HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n")
                .getBytes(StandardCharsets.US_ASCII));
        out.flush();
        return new String(socket.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
    }

    private static String get(Server server, SSLContext context) throws IOException {
        try (var socket = connect(server, context)) { return get(socket, "/"); }
    }

    private static String schemeOf(Fixture fixture) {
        fixture.app.get("/", ctx -> ctx.request().scheme() + " " + ctx.request().isSecure());
        return "/";
    }

    // --- handshake and request attributes ---

    @Test void servesHttpsAndMarksRequestsSecure() throws Exception {
        try (var tls = new TlsFixture(); var fixture = new Fixture()) {
            var server = tls.identity("localhost");
            var probe = new Probe();
            fixture.app.metrics(probe);
            schemeOf(fixture);
            var listener = listen(fixture, TlsOptions.pem(server.chain(), server.key()));
            var client = TlsFixture.client(server.certificate(), null);
            try (var socket = connect(listener, client)) {
                socket.startHandshake();
                assertThat(socket.getSession().getProtocol()).isEqualTo("TLSv1.3");
                assertThat(get(socket, "/")).startsWith("HTTP/1.1 200").endsWith("https true");
            }
            probe.await(value -> value >= 1, TlsMetrics.HANDSHAKES, "outcome", "completed");
            assertThat(probe.tagKeys().stream().filter(key -> key.startsWith("axiom.http.tls")))
                    .containsOnly("axiom.http.tls.handshakes:outcome");
        }
    }

    @Test void keepsAConnectionAliveAcrossRequestsAndPlainListenersStayPlain() throws Exception {
        try (var tls = new TlsFixture(); var fixture = new Fixture()) {
            var server = tls.identity("localhost");
            schemeOf(fixture);
            var listener = listen(fixture, TlsOptions.pem(server.chain(), server.key()));
            try (var socket = connect(listener, TlsFixture.client(server.certificate(), null))) {
                var out = socket.getOutputStream();
                var in = socket.getInputStream();
                for (int i = 0; i < 2; i++) {
                    out.write("GET / HTTP/1.1\r\nHost: localhost\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
                    out.flush();
                    var buffer = new byte[1024];
                    var text = new StringBuilder();
                    while (!text.toString().endsWith("https true")) {
                        int read = in.read(buffer);
                        assertThat(read).isPositive();
                        text.append(new String(buffer, 0, read, StandardCharsets.US_ASCII));
                    }
                }
            }
            var plain = (NettyServer) fixture.listen();
            try (var wire = new Wire(plain)) {
                assertThat(wire.get("/").text()).isEqualTo("http false");
            }
            assertThatThrownBy(plain::reloadTls).isInstanceOf(IllegalStateException.class);
        }
    }

    @Test void advertisesHttp11OnlyThroughAlpn() throws Exception {
        try (var tls = new TlsFixture(); var fixture = new Fixture()) {
            var server = tls.identity("localhost");
            schemeOf(fixture);
            var listener = listen(fixture, TlsOptions.pem(server.chain(), server.key()));
            var client = TlsFixture.client(server.certificate(), null);
            try (var socket = connect(listener, client)) {
                var parameters = socket.getSSLParameters();
                parameters.setApplicationProtocols(new String[] {"h2", "http/1.1"});
                socket.setSSLParameters(parameters);
                socket.startHandshake();
                assertThat(socket.getApplicationProtocol()).isEqualTo("http/1.1");
            }
            try (var socket = connect(listener, client)) {
                var parameters = socket.getSSLParameters();
                parameters.setApplicationProtocols(new String[] {"h2"});
                socket.setSSLParameters(parameters);
                assertThatThrownBy(() -> get(socket, "/")).isInstanceOf(SSLException.class);
            }
            try (var socket = connect(listener, client)) {
                socket.startHandshake();
                assertThat(socket.getApplicationProtocol()).isEmpty();
                assertThat(get(socket, "/")).startsWith("HTTP/1.1 200");
            }
        }
    }

    @Test void negotiatesTls12ByDefaultAndRejectsOlderOrDisallowedVersions() throws Exception {
        try (var tls = new TlsFixture(); var fixture = new Fixture()) {
            var server = tls.identity("localhost");
            var probe = new Probe();
            fixture.app.metrics(probe);
            schemeOf(fixture);
            var client = TlsFixture.client(server.certificate(), null);
            var byDefault = listen(fixture, TlsOptions.pem(server.chain(), server.key()));
            try (var socket = connect(byDefault, client)) {
                socket.setEnabledProtocols(new String[] {"TLSv1.2"});
                socket.startHandshake();
                assertThat(socket.getSession().getProtocol()).isEqualTo("TLSv1.2");
                assertThat(socket.getSession().getCipherSuite()).startsWith("TLS_ECDHE_").contains("_GCM_");
            }
            var modern = listen(fixture, TlsOptions.builder().certificateChain(server.chain())
                    .privateKey(server.key()).minimumProtocol(TlsOptions.Protocol.TLS_1_3).build());
            try (var socket = connect(modern, client)) {
                socket.setEnabledProtocols(new String[] {"TLSv1.2"});
                assertThatThrownBy(socket::startHandshake).isInstanceOf(SSLException.class);
            }
            try (var socket = connect(modern, client)) {
                socket.startHandshake();
                assertThat(socket.getSession().getProtocol()).isEqualTo("TLSv1.3");
            }
            probe.await(value -> value >= 1, TlsMetrics.HANDSHAKES, "outcome", "failed");
        }
    }

    @Test void refusesClientsThatOfferOnlySuitesOutsideTheConfiguredList() throws Exception {
        try (var tls = new TlsFixture(); var fixture = new Fixture()) {
            var server = tls.identity("localhost");
            schemeOf(fixture);
            var listener = listen(fixture, TlsOptions.builder().certificateChain(server.chain())
                    .privateKey(server.key()).minimumProtocol(TlsOptions.Protocol.TLS_1_2)
                    .cipherSuites(List.of("TLS_ECDHE_ECDSA_WITH_AES_128_GCM_SHA256")).build());
            var client = TlsFixture.client(server.certificate(), null);
            try (var socket = connect(listener, client)) {
                socket.setEnabledProtocols(new String[] {"TLSv1.2"});
                socket.setEnabledCipherSuites(new String[] {"TLS_ECDHE_ECDSA_WITH_AES_256_GCM_SHA384"});
                assertThatThrownBy(socket::startHandshake).isInstanceOf(SSLException.class);
            }
            try (var socket = connect(listener, client)) {
                socket.setEnabledProtocols(new String[] {"TLSv1.2"});
                socket.startHandshake();
                assertThat(socket.getSession().getCipherSuite()).isEqualTo("TLS_ECDHE_ECDSA_WITH_AES_128_GCM_SHA256");
            }
        }
    }

    // --- startup validation ---

    @Test void anExpiredCertificateFailsStartupWithoutPrintingKeyMaterial() throws Exception {
        try (var tls = new TlsFixture(); var fixture = new Fixture()) {
            var expired = tls.identity("old", "-3d", 1);
            var tlsOptions = TlsOptions.pem(expired.chain(), expired.key());
            assertThatThrownBy(() -> listen(fixture, tlsOptions)).isInstanceOf(TlsConfigurationException.class)
                    .hasMessageContaining("expired").hasMessageContaining(expired.chain().toString())
                    .satisfies(failure -> assertThat(failure.getMessage()).doesNotContain(keyLine(expired.key())));
            assertThat(fixture.servers).isEmpty();
        }
    }

    @Test void theClockDecidesWhetherACertificateIsValid() throws Exception {
        try (var tls = new TlsFixture(); var fixture = new Fixture()) {
            var server = tls.identity("localhost");
            var past = Clock.fixed(Instant.parse("2000-01-01T00:00:00Z"), ZoneOffset.UTC);
            var future = Clock.fixed(Instant.now().plus(Duration.ofDays(4000)), ZoneOffset.UTC);
            assertThatThrownBy(() -> listen(fixture, TlsOptions.builder().certificateChain(server.chain())
                    .privateKey(server.key()).clock(past).build()))
                    .isInstanceOf(TlsConfigurationException.class).hasMessageContaining("not valid before");
            assertThatThrownBy(() -> listen(fixture, TlsOptions.builder().certificateChain(server.chain())
                    .privateKey(server.key()).clock(future).build()))
                    .isInstanceOf(TlsConfigurationException.class).hasMessageContaining("expired");
        }
    }

    @Test void invalidMaterialFailsStartupWithAClearMessage() throws Exception {
        try (var tls = new TlsFixture(); var fixture = new Fixture()) {
            var one = tls.identity("one");
            var two = tls.identity("two");
            var missing = tls.directory().resolve("missing.pem");
            var garbage = tls.directory().resolve("garbage.pem");
            Files.writeString(garbage, "not a pem file at all");
            var badBlock = tls.directory().resolve("bad-block.pem");
            Files.writeString(badBlock, "-----BEGIN CERTIFICATE-----\nAAAA\n-----END CERTIFICATE-----\n");
            var traditional = tls.directory().resolve("traditional.pem");
            Files.writeString(traditional, "-----BEGIN EC PRIVATE KEY-----\nAAAA\n-----END EC PRIVATE KEY-----\n");
            var encrypted = tls.directory().resolve("encrypted.pem");
            Files.writeString(encrypted, "-----BEGIN ENCRYPTED PRIVATE KEY-----\nAAAA\n-----END ENCRYPTED PRIVATE KEY-----\n");
            var brokenKey = tls.directory().resolve("broken-key.pem");
            Files.writeString(brokenKey, "-----BEGIN PRIVATE KEY-----\nQUJDREVGR0g=\n-----END PRIVATE KEY-----\n");
            var twoKeys = tls.directory().resolve("two-keys.pem");
            Files.writeString(twoKeys, Files.readString(one.key()) + Files.readString(two.key()));

            check(fixture, missing, one.key(), "does not exist");
            check(fixture, garbage, one.key(), "contains no PEM CERTIFICATE");
            check(fixture, badBlock, one.key(), "not a valid X.509");
            check(fixture, one.chain(), missing, "does not exist");
            check(fixture, one.chain(), garbage, "contains no PEM PRIVATE KEY");
            check(fixture, one.chain(), traditional, "PKCS#8");
            check(fixture, one.chain(), encrypted, "encrypted");
            check(fixture, one.chain(), brokenKey, "not a valid EC key");
            check(fixture, one.chain(), twoKeys, "more than one private key");
            check(fixture, one.chain(), tls.directory(), "cannot be read");
            // The key of another certificate must not be accepted, and must not be printed.
            assertThatThrownBy(() -> listen(fixture, TlsOptions.pem(one.chain(), two.key())))
                    .isInstanceOf(TlsConfigurationException.class).hasMessageContaining("does not belong")
                    .satisfies(failure -> assertThat(failure.getMessage()).doesNotContain(keyLine(two.key())));
            // A missing client trust file fails as well.
            assertThatThrownBy(() -> listen(fixture, TlsOptions.builder().certificateChain(one.chain())
                    .privateKey(one.key()).requireClientCertificates(missing).build()))
                    .isInstanceOf(TlsConfigurationException.class).hasMessageContaining("does not exist");
            var expiredTrust = tls.identity("expired-ca", "-3d", 1);
            assertThatThrownBy(() -> listen(fixture, TlsOptions.builder().certificateChain(one.chain())
                    .privateKey(one.key()).requireClientCertificates(expiredTrust.chain()).build()))
                    .isInstanceOf(TlsConfigurationException.class).hasMessageContaining("expired");
            // Nothing that failed left a listener behind.
            assertThat(fixture.servers).isEmpty();
        }
    }

    private static void check(Fixture fixture, Path chain, Path key, String message) {
        assertThatThrownBy(() -> listen(fixture, TlsOptions.pem(chain, key)))
                .isInstanceOf(TlsConfigurationException.class).hasMessageContaining(message)
                .hasNoCause();
    }

    private static String keyLine(Path key) throws IOException {
        return Files.readAllLines(key).get(1);
    }

    @Test void unsupportedCipherSuitesAndContextSuppliersFailStartup() throws Exception {
        try (var tls = new TlsFixture(); var fixture = new Fixture()) {
            var server = tls.identity("localhost");
            assertThatThrownBy(() -> listen(fixture, TlsOptions.builder().certificateChain(server.chain())
                    .privateKey(server.key()).cipherSuites(List.of("TLS_NO_SUCH_SUITE")).build()))
                    .isInstanceOf(TlsConfigurationException.class).hasMessageContaining("cipher suites");
            assertThatThrownBy(() -> listen(fixture, TlsOptions.builder().sslContext(() -> null).build()))
                    .isInstanceOf(TlsConfigurationException.class).hasMessageContaining("returned null");
            assertThatThrownBy(() -> listen(fixture, TlsOptions.builder().sslContext(() -> {
                throw new IllegalStateException("vault secret-value unavailable");
            }).build())).isInstanceOf(TlsConfigurationException.class).hasMessageContaining("IllegalStateException")
                    .satisfies(failure -> assertThat(failure.getMessage()).doesNotContain("secret-value"));
        }
    }

    @Test void aSuppliedSslContextServesAndReloads() throws Exception {
        try (var tls = new TlsFixture(); var fixture = new Fixture()) {
            var first = tls.identity("first");
            var second = tls.identity("second");
            var current = new java.util.concurrent.atomic.AtomicReference<>(first);
            var calls = new AtomicInteger();
            schemeOf(fixture);
            var options = TlsOptions.builder().sslContext(() -> {
                calls.incrementAndGet();
                try {
                    var context = SSLContext.getInstance("TLS");
                    context.init(TlsFixture.keyManagers(current.get()), null, null);
                    return context;
                } catch (Exception failure) { throw new IllegalStateException(failure); }
            }).build();
            var listener = listen(fixture, options);
            assertThat(get(listener, TlsFixture.client(first.certificate(), null))).endsWith("https true");
            current.set(second);
            listener.reloadTls();
            assertThat(calls).hasValue(2);
            assertThat(get(listener, TlsFixture.client(second.certificate(), null))).endsWith("https true");
        }
    }

    // --- reload ---

    @Test void reloadSwapsTheCertificateForNewConnectionsOnly() throws Exception {
        try (var tls = new TlsFixture(); var fixture = new Fixture()) {
            var first = tls.identity("first");
            var second = tls.identity("second");
            var chain = tls.directory().resolve("live-chain.pem");
            var key = tls.directory().resolve("live-key.pem");
            TlsFixture.install(first, chain, key);
            var probe = new Probe();
            fixture.app.metrics(probe);
            schemeOf(fixture);
            var listener = listen(fixture, TlsOptions.pem(chain, key));
            var trustFirst = TlsFixture.client(first.certificate(), null);
            var trustSecond = TlsFixture.client(second.certificate(), null);

            try (var open = connect(listener, trustFirst)) {
                open.startHandshake();
                assertThat(open.getSession().getPeerCertificates()[0]).isEqualTo(first.certificate());
                TlsFixture.install(second, chain, key);
                // Files changed on disk but nothing was reloaded yet: new connections still get the old certificate.
                try (var before = connect(listener, trustFirst)) {
                    before.startHandshake();
                    assertThat(before.getSession().getPeerCertificates()[0]).isEqualTo(first.certificate());
                }
                listener.reloadTls();
                try (var after = connect(listener, trustSecond)) {
                    after.startHandshake();
                    assertThat(after.getSession().getPeerCertificates()[0]).isEqualTo(second.certificate());
                    assertThat(get(after, "/")).endsWith("https true");
                }
                assertThatThrownBy(() -> connect(listener, trustFirst).startHandshake())
                        .isInstanceOf(SSLException.class);
                // The connection opened before the reload keeps working with the certificate it negotiated.
                var out = open.getOutputStream();
                out.write("GET / HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
                out.flush();
                assertThat(new String(open.getInputStream().readAllBytes(), StandardCharsets.UTF_8))
                        .startsWith("HTTP/1.1 200").endsWith("https true");
            }
            probe.await(value -> value == 1, TlsMetrics.RELOADS, "outcome", "completed");
        }
    }

    @Test void aRejectedReloadKeepsThePreviousMaterial() throws Exception {
        try (var tls = new TlsFixture(); var fixture = new Fixture()) {
            var first = tls.identity("first");
            var expired = tls.identity("old", "-3d", 1);
            var chain = tls.directory().resolve("live-chain.pem");
            var key = tls.directory().resolve("live-key.pem");
            TlsFixture.install(first, chain, key);
            var probe = new Probe();
            fixture.app.metrics(probe);
            schemeOf(fixture);
            var listener = listen(fixture, TlsOptions.pem(chain, key));
            var client = TlsFixture.client(first.certificate(), null);

            TlsFixture.install(expired, chain, key);
            assertThatThrownBy(listener::reloadTls).isInstanceOf(TlsConfigurationException.class)
                    .hasMessageContaining("expired");
            Files.writeString(key, "garbage");
            assertThatThrownBy(listener::reloadTls).isInstanceOf(TlsConfigurationException.class);
            assertThat(get(listener, client)).endsWith("https true");
            assertThat(probe.value(TlsMetrics.RELOADS, "outcome", "failed")).isEqualTo(2);
            assertThat(probe.value(TlsMetrics.RELOADS, "outcome", "completed")).isZero();
        }
    }

    @Test void pollingReloadsChangedFilesOnceAndRetriesRejectedOnesOnlyWhenTheyChange() throws Exception {
        try (var tls = new TlsFixture(); var fixture = new Fixture()) {
            var first = tls.identity("first");
            var second = tls.identity("second");
            var third = tls.identity("third");
            var chain = tls.directory().resolve("live-chain.pem");
            var key = tls.directory().resolve("live-key.pem");
            TlsFixture.install(first, chain, key);
            var probe = new Probe();
            fixture.app.metrics(probe);
            schemeOf(fixture);
            // No interval: the poll is driven by the test, so nothing depends on timing.
            var listener = listen(fixture, TlsOptions.pem(chain, key));

            listener.pollTls();
            assertThat(probe.value(TlsMetrics.RELOADS, "outcome", "completed")).isZero();
            TlsFixture.install(second, chain, key);
            listener.pollTls();
            listener.pollTls();
            assertThat(probe.value(TlsMetrics.RELOADS, "outcome", "completed")).isEqualTo(1);
            assertThat(get(listener, TlsFixture.client(second.certificate(), null))).endsWith("https true");

            // A half-written renewal is rejected once, not on every poll, and the old material stays.
            Files.writeString(chain, "-----BEGIN CERTIFICATE-----\n");
            listener.pollTls();
            listener.pollTls();
            assertThat(probe.value(TlsMetrics.RELOADS, "outcome", "failed")).isEqualTo(1);
            assertThat(get(listener, TlsFixture.client(second.certificate(), null))).endsWith("https true");
            TlsFixture.install(third, chain, key);
            listener.pollTls();
            assertThat(probe.value(TlsMetrics.RELOADS, "outcome", "completed")).isEqualTo(2);
            assertThat(get(listener, TlsFixture.client(third.certificate(), null))).endsWith("https true");
            Files.delete(chain);
            listener.pollTls();
            assertThat(probe.value(TlsMetrics.RELOADS, "outcome", "failed")).isEqualTo(1);
        }
    }

    @Test void theScheduledPollPicksUpRenewedFiles() throws Exception {
        try (var tls = new TlsFixture(); var fixture = new Fixture()) {
            var first = tls.identity("first");
            var second = tls.identity("second");
            var chain = tls.directory().resolve("live-chain.pem");
            var key = tls.directory().resolve("live-key.pem");
            TlsFixture.install(first, chain, key);
            var probe = new Probe();
            fixture.app.metrics(probe);
            schemeOf(fixture);
            var listener = listen(fixture, TlsOptions.builder().certificateChain(chain).privateKey(key)
                    .reloadInterval(Duration.ofSeconds(1)).build());
            TlsFixture.install(second, chain, key);
            probe.await(value -> value >= 1, TlsMetrics.RELOADS, "outcome", "completed");
            assertThat(get(listener, TlsFixture.client(second.certificate(), null))).endsWith("https true");
        }
    }

    // --- mutual TLS ---

    @Test void mutualTlsAcceptsTrustedClientsAndRejectsOthers() throws Exception {
        try (var tls = new TlsFixture(); var fixture = new Fixture()) {
            var server = tls.identity("localhost");
            var trusted = tls.identity("trusted-client");
            var stranger = tls.identity("stranger");
            var probe = new Probe();
            fixture.app.metrics(probe);
            schemeOf(fixture);
            var listener = listen(fixture, TlsOptions.builder().certificateChain(server.chain())
                    .privateKey(server.key()).requireClientCertificates(tls.certificates("clients", trusted.certificate()))
                    .build());
            assertThat(get(listener, TlsFixture.client(server.certificate(), trusted))).endsWith("https true");
            assertThatThrownBy(() -> get(listener, TlsFixture.client(server.certificate(), null)))
                    .as("no certificate").isInstanceOf(IOException.class);
            assertThatThrownBy(() -> get(listener, TlsFixture.client(server.certificate(), stranger)))
                    .as("untrusted certificate").isInstanceOf(IOException.class);
            probe.await(value -> value >= 2, TlsMetrics.HANDSHAKES, "outcome", "failed");
            assertThat(listener.admission().accepted()).isEqualTo(1);
        }
    }

    // --- hostile clients ---

    @Test void aPlainTextClientOnATlsPortIsClosedAndCounted() throws Exception {
        try (var tls = new TlsFixture(); var fixture = new Fixture()) {
            var server = tls.identity("localhost");
            var probe = new Probe();
            fixture.app.metrics(probe);
            var invoked = new AtomicInteger();
            fixture.app.get("/", ctx -> { invoked.incrementAndGet(); return "never"; });
            var listener = listen(fixture, TlsOptions.pem(server.chain(), server.key()));
            try (var socket = new Socket()) {
                socket.connect(listener.localAddress(), 30_000);
                socket.setSoTimeout(30_000);
                socket.getOutputStream().write("GET / HTTP/1.1\r\nHost: localhost\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
                socket.getOutputStream().flush();
                closedByServer(socket);
            }
            probe.await(value -> value == 1, TlsMetrics.HANDSHAKES, "outcome", "plaintext");
            assertThat(invoked).hasValue(0);
            assertThat(listener.admission().accepted()).isZero();
        }
    }

    @Test void garbageInsteadOfAHandshakeIsClosedAtOnceAndFreesItsSlot() throws Exception {
        try (var tls = new TlsFixture(); var fixture = new Fixture()) {
            var server = tls.identity("localhost");
            var probe = new Probe();
            fixture.app.metrics(probe);
            schemeOf(fixture);
            var listener = listen(fixture, ListenerOptions.builder().maxConnections(1)
                    .tls(TlsOptions.pem(server.chain(), server.key())).build());
            var client = TlsFixture.client(server.certificate(), null);
            // A record header that claims TLS but is garbage after it.
            var hello = new byte[] {0x16, 0x03, 0x01, 0x00, 0x05, 1, 2, 3, 4, 5};
            for (int i = 0; i < 3; i++) {
                try (var socket = new Socket()) {
                    socket.connect(listener.localAddress(), 30_000);
                    socket.setSoTimeout(30_000);
                    socket.getOutputStream().write(hello);
                    socket.getOutputStream().flush();
                    closedByServer(socket);
                }
            }
            probe.await(value -> value == 3, TlsMetrics.HANDSHAKES, "outcome", "failed");
            assertThat(awaitRequest(listener, client)).endsWith("https true");
        }
    }

    @Test void aStalledHandshakeTimesOutAndReleasesItsConnectionSlot() throws Exception {
        try (var tls = new TlsFixture(); var fixture = new Fixture()) {
            var server = tls.identity("localhost");
            var probe = new Probe();
            fixture.app.metrics(probe);
            schemeOf(fixture);
            var listener = listen(fixture, ListenerOptions.builder().maxConnections(1)
                    .handshakeTimeout(Duration.ofMillis(300)).tls(TlsOptions.pem(server.chain(), server.key())).build());
            try (var stalled = new Socket()) {
                stalled.connect(listener.localAddress(), 30_000);
                stalled.setSoTimeout(30_000);
                // The cap is full: a second connection is closed at once without holding anything.
                try (var refused = new Socket()) {
                    refused.connect(listener.localAddress(), 30_000);
                    refused.setSoTimeout(30_000);
                    closedByServer(refused);
                }
                // The silent client is cut off by the handshake timeout, not by the 30 second idle timeout.
                closedByServer(stalled);
            }
            probe.await(value -> value == 1, TlsMetrics.HANDSHAKES, "outcome", "timeout");
            assertThat(awaitRequest(listener, TlsFixture.client(server.certificate(), null))).endsWith("https true");
            assertThat(listener.admission().rejected()).isZero();
        }
    }

    @Test void aClientThatLeavesMidHandshakeIsCountedAsClosed() throws Exception {
        try (var tls = new TlsFixture(); var fixture = new Fixture()) {
            var server = tls.identity("localhost");
            var probe = new Probe();
            fixture.app.metrics(probe);
            var listener = listen(fixture, TlsOptions.pem(server.chain(), server.key()));
            try (var socket = new Socket()) { socket.connect(listener.localAddress(), 30_000); }
            probe.await(value -> value == 1, TlsMetrics.HANDSHAKES, "outcome", "closed");
        }
    }

    @Test void anOversizedRequestOverTlsIsAnsweredBeforeTheConnectionCloses() throws Exception {
        try (var tls = new TlsFixture(); var fixture = new Fixture()) {
            var server = tls.identity("localhost");
            schemeOf(fixture);
            var listener = listen(fixture, TlsOptions.pem(server.chain(), server.key()));
            try (var socket = connect(listener, TlsFixture.client(server.certificate(), null))) {
                var out = socket.getOutputStream();
                out.write("POST / HTTP/1.1\r\nHost: localhost\r\nContent-Length: 999999999999\r\n\r\n"
                        .getBytes(StandardCharsets.US_ASCII));
                out.flush();
                assertThat(new String(socket.getInputStream().readAllBytes(), StandardCharsets.UTF_8))
                        .startsWith("HTTP/1.1 413");
            }
        }
    }

    @Test void closingTheListenerClosesOpenTlsConnections() throws Exception {
        try (var tls = new TlsFixture(); var fixture = new Fixture()) {
            var server = tls.identity("localhost");
            schemeOf(fixture);
            var listener = listen(fixture, TlsOptions.pem(server.chain(), server.key()));
            try (var socket = connect(listener, TlsFixture.client(server.certificate(), null))) {
                socket.startHandshake();
                listener.close();
                listener.termination().toCompletableFuture().get(30, java.util.concurrent.TimeUnit.SECONDS);
                assertThat(socket.getInputStream().read()).isEqualTo(-1);
            }
        }
    }

    /** Reads until the server ends the connection, by EOF or reset. */
    private static void closedByServer(Socket socket) throws IOException {
        try {
            assertThat(socket.getInputStream().readAllBytes()).isNotNull();
        } catch (SocketException reset) {
            assertThat(reset).hasMessageContaining("reset");
        }
    }

    /**
     * Completes one request, retrying while the server still counts a connection that just closed:
     * the slot is released by the listener moments after the client sees the close.
     */
    private static String awaitRequest(Server server, SSLContext client) throws IOException {
        IOException last = null;
        for (int attempt = 0; attempt < 200; attempt++) {
            try (var socket = connect(server, client)) {
                return get(socket, "/");
            } catch (IOException refused) {
                last = refused;
            }
        }
        throw last;
    }
}
