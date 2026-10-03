package com.jsgalactic.axiom.http.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.jsgalactic.axiom.lifecycle.ListenerOptions;
import com.jsgalactic.axiom.lifecycle.TlsOptions;
import com.jsgalactic.axiom.lifecycle.TransportKind;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * Transport selection and the behavior of every transport that can run here. The whole
 * integration suite runs twice (see the build file of this module): once without native
 * libraries on the class path, where {@code AUTO} means NIO, and once with the native library of
 * the build platform, where {@code AUTO} must pick it. The native cases here skip when the native
 * transport is not usable and fail instead under {@code -Daxiom.requireNativeTransport=true}.
 */
@Tag("integration")
class TransportSelectionTest {
    private static final InetSocketAddress LOOPBACK = new InetSocketAddress("127.0.0.1", 0);
    private static final boolean REQUIRED = Boolean.getBoolean("axiom.requireNativeTransport");

    private static NativeTransports.Availability availability(TransportKind kind) {
        return switch (kind) {
            case EPOLL -> NativeTransports.epoll();
            case KQUEUE -> NativeTransports.kqueue();
            default -> throw new IllegalArgumentException(kind.name());
        };
    }

    /** The native transport this operating system can have, or null where there is none. */
    private static TransportKind nativeKindOfThisSystem() {
        var os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        if (os.contains("linux")) { return TransportKind.EPOLL; }
        if (os.contains("mac") || os.contains("bsd")) { return TransportKind.KQUEUE; }
        return null;
    }

    /**
     * Skips a native case that cannot run here, unless native transports are required, in which
     * case the one this system should have must be usable and anything else is skipped.
     */
    private static void requireUsable(TransportKind kind) {
        if (kind != nativeKindOfThisSystem()) {
            Assumptions.abort(kind + " does not exist on this operating system");
        }
        var found = availability(kind);
        if (found.transport() != null) { return; }
        if (REQUIRED) { Assertions.fail(kind + " is required (axiom.requireNativeTransport) but is not usable: " + found.reason(), found.cause()); }
        Assumptions.abort(kind + " is not usable here: " + found.reason());
    }

    private static ListenerOptions options(TransportKind kind) { return ListenerOptions.builder().transport(kind).build(); }

    @Test void autoPicksTheNativeTransportExactlyWhenTheBuildProvidesIt() throws Exception {
        var expected = System.getProperty("axiom.expectedTransport", "NIO");
        try (var fixture = new Fixture()) {
            fixture.app.get("/", ctx -> "ok");
            var server = (NettyServer) fixture.listen();
            if (expected.equals("NIO")) {
                assertThat(server.transport()).isEqualTo(TransportKind.NIO);
            } else {
                var kind = nativeKindOfThisSystem();
                if (kind != null && availability(kind).transport() == null && !REQUIRED) {
                    Assumptions.abort("The native transport is not usable here");
                }
                assertThat(server.transport()).as("AUTO with the native library on the class path").isEqualTo(kind);
            }
        }
    }

    @Test void defaultsToAutoWhichResolvesToAConcreteKind() throws Exception {
        try (var fixture = new Fixture()) {
            var server = (NettyServer) fixture.listen();
            assertThat(server.settings().options().transport()).isEqualTo(TransportKind.AUTO);
            assertThat(server.transport()).isIn(TransportKind.NIO, TransportKind.EPOLL, TransportKind.KQUEUE);
        }
    }

    @ParameterizedTest @EnumSource(value = TransportKind.class, names = {"NIO", "EPOLL", "KQUEUE"})
    void servesPlainHttp(TransportKind kind) throws Exception {
        if (kind != TransportKind.NIO) { requireUsable(kind); }
        try (var fixture = new Fixture()) {
            fixture.app.get("/hello", ctx -> "hello " + ctx.request().path());
            var server = fixture.app.listen(LOOPBACK, options(kind));
            fixture.servers.add(server);
            assertThat(((NettyServer) server).transport()).isEqualTo(kind);
            try (var wire = new Wire(server)) {
                for (int i = 0; i < 3; i++) {
                    var reply = wire.get("/hello");
                    assertThat(reply.status()).isEqualTo(200);
                    assertThat(new String(reply.body(), StandardCharsets.UTF_8)).isEqualTo("hello /hello");
                }
            }
        }
    }

    @ParameterizedTest @EnumSource(value = TransportKind.class, names = {"NIO", "EPOLL", "KQUEUE"})
    void servesHttps(TransportKind kind) throws Exception {
        if (kind != TransportKind.NIO) { requireUsable(kind); }
        try (var tls = new TlsFixture(); var fixture = new Fixture()) {
            var identity = tls.identity("localhost");
            fixture.app.get("/", ctx -> ctx.request().scheme());
            var listenerOptions = ListenerOptions.builder().transport(kind).tls(TlsOptions.pem(identity.chain(), identity.key())).build();
            var server = fixture.app.listen(LOOPBACK, listenerOptions);
            fixture.servers.add(server);
            var client = TlsFixture.client(identity.certificate(), null);
            try (var socket = client.getSocketFactory().createSocket(server.localAddress().getAddress(), server.localAddress().getPort())) {
                socket.setSoTimeout(30_000);
                var out = socket.getOutputStream();
                out.write("GET / HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
                out.flush();
                var text = new String(socket.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
                assertThat(text).startsWith("HTTP/1.1 200").endsWith("https");
            }
        }
    }

    @ParameterizedTest @EnumSource(value = TransportKind.class, names = {"NIO", "EPOLL", "KQUEUE"})
    void closeDrainsAnIdleConnectionAndTerminates(TransportKind kind) throws Exception {
        if (kind != TransportKind.NIO) { requireUsable(kind); }
        try (var fixture = new Fixture()) {
            fixture.app.get("/", ctx -> "ok");
            var server = fixture.app.listen(LOOPBACK, options(kind));
            fixture.servers.add(server);
            try (var wire = new Wire(server)) {
                assertThat(wire.get("/").status()).isEqualTo(200);
                server.close();
                server.termination().toCompletableFuture().get(20, TimeUnit.SECONDS);
                assertThat(server.isOpen()).isFalse();
                // The idle keep-alive connection was closed by the server.
                assertThat(wire.socket.getInputStream().read()).isEqualTo(-1);
            }
        }
    }

    @ParameterizedTest @EnumSource(value = TransportKind.class, names = {"EPOLL", "KQUEUE"})
    void anUnusableNativeTransportFailsStartupWithoutBinding(TransportKind kind) throws Exception {
        Assumptions.assumeTrue(availability(kind).transport() == null, kind + " is usable here");
        try (var fixture = new Fixture()) {
            assertThatThrownBy(() -> fixture.app.listen(LOOPBACK, options(kind)))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining(kind.name()).hasMessageContaining("netty-transport-native-");
            // The application is untouched and can still listen with the default transport.
            fixture.servers.add(fixture.app.listen(LOOPBACK, options(TransportKind.NIO)));
        }
    }

    @Test void nioIsNeverReplacedEvenWhenNativeIsAvailable() throws Exception {
        try (var fixture = new Fixture()) {
            var server = (NettyServer) fixture.app.listen(LOOPBACK, options(TransportKind.NIO));
            fixture.servers.add(server);
            assertThat(server.transport()).isEqualTo(TransportKind.NIO);
        }
    }

    @Test void resolveNeverReturnsAuto() {
        for (var kind : TransportKind.values()) {
            if (kind == TransportKind.EPOLL || kind == TransportKind.KQUEUE) { continue; }
            assertThat(Transport.resolve(kind).kind()).isNotEqualTo(TransportKind.AUTO);
        }
    }
}
