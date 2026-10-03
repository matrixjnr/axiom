package com.jsgalactic.axiom.lifecycle;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import javax.net.ssl.SSLContext;
import org.junit.jupiter.api.Test;

class TlsOptionsTest {
    private static final Path CHAIN = Path.of("chain.pem");
    private static final Path KEY = Path.of("secret-key-file.pem");

    @Test void pemOptionsUseSecureDefaults() {
        var options = TlsOptions.pem(CHAIN, KEY);
        assertThat(options.certificateChain()).contains(CHAIN);
        assertThat(options.privateKey()).contains(KEY);
        assertThat(options.sslContext()).isEmpty();
        assertThat(options.minimumProtocol()).isEqualTo(TlsOptions.Protocol.TLS_1_2);
        assertThat(options.minimumProtocol().jdkName()).isEqualTo("TLSv1.2");
        assertThat(options.cipherSuites()).isEqualTo(TlsOptions.DEFAULT_CIPHER_SUITES)
                .noneMatch(suite -> suite.contains("_CBC_") || suite.contains("RC4") || suite.contains("3DES"))
                .allMatch(suite -> suite.startsWith("TLS_ECDHE_") || suite.startsWith("TLS_AES_")
                        || suite.startsWith("TLS_CHACHA20_"));
        assertThat(options.clientAuth()).isEqualTo(TlsOptions.ClientAuth.NONE);
        assertThat(options.clientTrust()).isEmpty();
        assertThat(options.reloadInterval()).isEmpty();
        assertThat(options.clock()).isNotNull();
    }

    @Test void builderSetsEverySettingAndToBuilderCopiesThem() {
        var clock = Clock.fixed(Instant.EPOCH, ZoneOffset.UTC);
        var options = TlsOptions.builder().certificateChain(CHAIN).privateKey(KEY)
                .minimumProtocol(TlsOptions.Protocol.TLS_1_3).cipherSuites(List.of("TLS_AES_256_GCM_SHA384"))
                .requireClientCertificates(Path.of("clients.pem")).reloadInterval(Duration.ofSeconds(30))
                .clock(clock).build();
        assertThat(options.minimumProtocol()).isEqualTo(TlsOptions.Protocol.TLS_1_3);
        assertThat(options.cipherSuites()).containsExactly("TLS_AES_256_GCM_SHA384");
        assertThat(options.clientAuth()).isEqualTo(TlsOptions.ClientAuth.REQUIRED);
        assertThat(options.clientTrust()).contains(Path.of("clients.pem"));
        assertThat(options.reloadInterval()).contains(Duration.ofSeconds(30));
        assertThat(options.clock()).isSameAs(clock);
        var copy = options.toBuilder().minimumProtocol(TlsOptions.Protocol.TLS_1_2).build();
        assertThat(copy.minimumProtocol()).isEqualTo(TlsOptions.Protocol.TLS_1_2);
        assertThat(copy.clientTrust()).contains(Path.of("clients.pem"));
        assertThat(options.minimumProtocol()).isEqualTo(TlsOptions.Protocol.TLS_1_3);
    }

    @Test void anSslContextSupplierIsAnAlternativeToPemFiles() throws Exception {
        var context = SSLContext.getDefault();
        var options = TlsOptions.builder().sslContext(() -> context).requireClientCertificates().build();
        assertThat(options.sslContext().orElseThrow().get()).isSameAs(context);
        assertThat(options.certificateChain()).isEmpty();
        assertThat(options.clientAuth()).isEqualTo(TlsOptions.ClientAuth.REQUIRED);
    }

    @Test void rejectsInconsistentKeyMaterial() throws Exception {
        var context = SSLContext.getDefault();
        assertThatThrownBy(() -> TlsOptions.builder().build()).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> TlsOptions.builder().certificateChain(CHAIN).build())
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("together");
        assertThatThrownBy(() -> TlsOptions.builder().privateKey(KEY).build())
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> TlsOptions.builder().certificateChain(CHAIN).privateKey(KEY)
                .sslContext(() -> context).build()).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> TlsOptions.builder().certificateChain(CHAIN).privateKey(KEY)
                .requireClientCertificates().build()).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> TlsOptions.builder().sslContext(() -> context)
                .requireClientCertificates(Path.of("c.pem")).build()).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> TlsOptions.builder().sslContext(() -> context)
                .reloadInterval(Duration.ofSeconds(5)).build()).isInstanceOf(IllegalStateException.class);
    }

    @Test void validatesIndividualSettings() {
        var builder = TlsOptions.builder();
        assertThatThrownBy(() -> builder.cipherSuites(List.of())).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> builder.cipherSuites(List.of(" "))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> builder.reloadInterval(Duration.ofMillis(999)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("reloadInterval");
        assertThatThrownBy(() -> builder.reloadInterval(Duration.ofDays(1).plusSeconds(1)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> builder.certificateChain(null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> builder.privateKey(null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> builder.sslContext(null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> builder.minimumProtocol(null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> builder.clock(null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> builder.requireClientCertificates(null)).isInstanceOf(NullPointerException.class);
        assertThat(TlsOptions.builder().certificateChain(CHAIN).privateKey(KEY)
                .reloadInterval(Duration.ofSeconds(1)).build().reloadInterval()).contains(Duration.ofSeconds(1));
    }

    @Test void toStringNamesNeitherFilesNorKeys() {
        assertThat(TlsOptions.pem(CHAIN, KEY).toString()).doesNotContain("secret-key-file", "chain.pem")
                .contains("pem", "TLS_1_2");
    }

    @Test void listenerOptionsCarryTlsAndTheHandshakeTimeout() {
        assertThat(ListenerOptions.defaults().tls()).isEmpty();
        assertThat(ListenerOptions.defaults().handshakeTimeout()).isEqualTo(Duration.ofSeconds(10));
        var tls = TlsOptions.pem(CHAIN, KEY);
        var options = ListenerOptions.builder().tls(tls).handshakeTimeout(Duration.ofMillis(1)).build();
        assertThat(options.tls()).containsSame(tls);
        assertThat(options.handshakeTimeout()).isEqualTo(Duration.ofMillis(1));
        assertThat(options.toBuilder().build().tls()).containsSame(tls);
        assertThat(options.toBuilder().tls(null).build().tls()).isEmpty();
        assertThat(options.toString()).doesNotContain("secret-key-file");
        assertThatThrownBy(() -> ListenerOptions.builder().handshakeTimeout(Duration.ZERO))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("handshakeTimeout");
        assertThatThrownBy(() -> ListenerOptions.builder().handshakeTimeout(Duration.ofDays(2)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test void aServerWithoutTlsRefusesToReload() {
        Server server = new Server() {
            @Override public java.net.InetSocketAddress localAddress() { return null; }
            @Override public boolean isOpen() { return false; }
            @Override public com.jsgalactic.axiom.execution.AdmissionSnapshot admission() { return null; }
            @Override public java.util.concurrent.CompletionStage<Void> termination() { return null; }
            @Override public void close() { }
        };
        assertThatThrownBy(server::reloadTls).isInstanceOf(IllegalStateException.class);
    }

    @Test void configurationExceptionCarriesOnlyItsMessage() {
        var failure = new TlsConfigurationException("The private key does not match the certificate");
        assertThat(failure).hasMessage("The private key does not match the certificate").hasNoCause();
    }
}
