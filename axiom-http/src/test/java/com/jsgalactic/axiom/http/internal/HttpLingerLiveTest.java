package com.jsgalactic.axiom.http.internal;

import static org.assertj.core.api.Assertions.assertThat;

import com.jsgalactic.axiom.Axiom;
import com.jsgalactic.axiom.application.Application;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** Lingering over real sockets; the embedded-channel cases are in {@link HttpLingerTest}. */
@Tag("integration")
class HttpLingerLiveTest {
    private final Application app = Axiom.create();

    @AfterEach void stop() {
        app.close();
    }

    @Test void largeResponseBeforeACloseReachesASlowReaderInFullWhileItKeepsSending() throws Exception {
        var body = new byte[128 * 1024];
        new java.util.Random(3).nextBytes(body);
        app.get("/big", ctx -> body);
        app.start();
        // Fixed buffers make the outcome independent of kernel defaults: the whole response fits in
        // the server's send buffer, so the server is done writing it at once, while far less than the
        // client's upload fits in the buffers on the way, so the client is still sending then.
        // A long linger and quiet period keep the test independent of machine load.
        var server = NettyServer.bind(app, new java.net.InetSocketAddress("127.0.0.1", 0),
                TransportSettings.DEFAULTS.withLinger(java.time.Duration.ofSeconds(60))
                        .withLingerQuiet(java.time.Duration.ofSeconds(60))
                        .withSocketBuffers(64 * 1024, 4 * body.length));
        var client = new java.net.Socket();
        Thread sender = null;
        try {
            // A small receive window makes the client a slow reader: most of the response is still
            // in the server's send buffer when the server is done writing it.
            client.setReceiveBufferSize(4096);
            client.setSendBufferSize(64 * 1024);
            client.connect(server.localAddress(), 30_000);
            client.setSoTimeout(30_000);
            var out = client.getOutputStream();
            out.write(("GET /big HTTP/1.1\r\nHost: a\r\nConnection: close\r\n\r\n"
                    + "POST /big HTTP/1.1\r\nHost: a\r\nContent-Length: 4194304\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
            // The client keeps sending pipelined input, which the server never reads as a request.
            sender = Thread.ofVirtual().start(() -> {
                var piece = new byte[16 * 1024];
                try {
                    for (int sent = 0; sent < 4 * 1024 * 1024; sent += piece.length) { out.write(piece); }
                } catch (java.io.IOException closed) { /* Only the response matters. */ }
            });
            // The client reads nothing until it has sent everything: a server that closed early has
            // reset the connection by then, destroying the response it had not delivered yet.
            sender.join();
            var in = client.getInputStream();
            var headers = new StringBuilder();
            while (!headers.toString().endsWith("\r\n\r\n")) {
                int next = in.read();
                assertThat(next).as("end of stream inside the head").isNotEqualTo(-1);
                headers.append((char) next);
            }
            assertThat(headers.toString()).startsWith("HTTP/1.1 200 OK").contains("content-length: " + body.length)
                    .contains("connection: close");
            assertThat(in.readNBytes(body.length)).isEqualTo(body);
            assertThat(in.read()).isEqualTo(-1);
        } finally {
            client.close();
            if (sender != null) { sender.join(); }
            server.close();
            server.termination().toCompletableFuture().get(10, TimeUnit.SECONDS);
        }
    }

    @Test void overTheNetworkAClientThatReadsTheResponseAndGoesQuietIsClosedAfterTheQuietPeriod() throws Exception {
        app.get("/", ctx -> "bye");
        app.start();
        // The total linger bound is far away: only the quiet period can end lingering.
        var server = NettyServer.bind(app, new java.net.InetSocketAddress("127.0.0.1", 0),
                TransportSettings.DEFAULTS.withLinger(java.time.Duration.ofHours(1)));
        try (var wire = new Wire(server)) {
            wire.write("GET / HTTP/1.1\r\nHost: a\r\nConnection: close\r\n\r\n");
            assertThat(wire.read(false).text()).isEqualTo("bye");
            assertThat(wire.socket.getInputStream().read()).isEqualTo(-1);
            // The client keeps its socket open and sends nothing more.
            awaitNoConnections(server);
        } finally {
            server.close();
            server.termination().toCompletableFuture().get(30, TimeUnit.SECONDS);
        }
    }

    @Test void overTheNetworkListenerShutdownDoesNotWaitForTheFullLinger() throws Exception {
        app.get("/", ctx -> "bye");
        app.start();
        // Neither the total linger bound nor the quiet period nor the grace period can end it.
        var server = NettyServer.bind(app, new java.net.InetSocketAddress("127.0.0.1", 0),
                TransportSettings.DEFAULTS.withLinger(java.time.Duration.ofHours(1))
                        .withLingerQuiet(java.time.Duration.ofHours(1))
                        .withShutdownGrace(java.time.Duration.ofHours(1)));
        try (var wire = new Wire(server)) {
            wire.write("GET / HTTP/1.1\r\nHost: a\r\nConnection: close\r\n\r\n");
            assertThat(wire.read(false).text()).isEqualTo("bye");
            assertThat(wire.socket.getInputStream().read()).isEqualTo(-1);
            assertThat(server.lingering()).isEqualTo(1);
            server.close();
            server.termination().toCompletableFuture().get(30, TimeUnit.SECONDS);
        } finally {
            server.close();
        }
    }

    @Test void overTheNetworkAClientThatPausesWhileUploadingKeepsItsConnectionWhenTheQuietPeriodCoversThePause()
            throws Exception {
        app.maxRequestBody(16);
        app.post("/", ctx -> "unreachable");
        app.start();
        // The quiet period is the tuning knob for slow uploaders (lingerQuietTimeout); this one far
        // exceeds the pause, and the total bound is far away too.
        var server = NettyServer.bind(app, new java.net.InetSocketAddress("127.0.0.1", 0),
                TransportSettings.DEFAULTS.withLinger(java.time.Duration.ofHours(1))
                        .withLingerQuiet(java.time.Duration.ofHours(1)));
        try (var wire = new Wire(server)) {
            // The declared body is over the limit, so the answer comes at once while the upload is
            // only begun.
            wire.write("POST / HTTP/1.1\r\nHost: a\r\nContent-Length: 1000\r\n\r\n" + "x".repeat(100));
            assertThat(wire.read(false).status()).isEqualTo(413);
            // The response and end of stream are in; the server now lingers. The client pauses until
            // it has seen that (no timing assumption), then resumes its upload.
            awaitLingering(server, 1);
            wire.write("x".repeat(900));
            assertThat(server.lingering()).as("still lingering after the pause").isEqualTo(1);
            // Had the server closed with unread input, the client would see a reset, not a clean end.
            assertThat(wire.socket.getInputStream().read()).isEqualTo(-1);
        } finally {
            server.close();
            server.termination().toCompletableFuture().get(30, TimeUnit.SECONDS);
        }
    }

    @Test void overTheNetworkAClientThatPausesLongerThanTheConfiguredQuietPeriodIsClosed() throws Exception {
        app.maxRequestBody(16);
        app.post("/", ctx -> "unreachable");
        app.start();
        // Only the quiet period can end lingering here, so a pause is what closes the connection.
        var server = NettyServer.bind(app, new java.net.InetSocketAddress("127.0.0.1", 0),
                TransportSettings.DEFAULTS.withLinger(java.time.Duration.ofHours(1))
                        .withLingerQuiet(java.time.Duration.ofMillis(50)));
        try (var wire = new Wire(server)) {
            wire.write("POST / HTTP/1.1\r\nHost: a\r\nContent-Length: 1000\r\n\r\n" + "x".repeat(100));
            assertThat(wire.read(false).status()).isEqualTo(413);
            // The client stays connected and silent, mid-upload; the server gives up on it.
            awaitNoConnections(server);
        } finally {
            server.close();
            server.termination().toCompletableFuture().get(30, TimeUnit.SECONDS);
        }
    }

    private static void awaitLingering(NettyServer server, int expected) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        while (server.lingering() != expected) {
            assertThat(System.nanoTime()).as("lingering reaches " + expected).isLessThan(deadline);
            Thread.onSpinWait();
        }
    }

    private static void awaitNoConnections(NettyServer server) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        while (server.connections() + server.lingering() != 0) {
            assertThat(System.nanoTime()).as("lingering connection closed").isLessThan(deadline);
            Thread.onSpinWait();
        }
    }
}
