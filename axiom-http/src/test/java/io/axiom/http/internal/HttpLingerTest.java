package io.axiom.http.internal;

import static org.assertj.core.api.Assertions.assertThat;

import io.axiom.Axiom;
import io.axiom.application.Application;
import io.axiom.server.internal.execution.RequestDispatcher;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.http.HttpResponseEncoder;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** After a listener error response the connection half-closes and discards input for a bounded time. */
class HttpLingerTest {
    private final RequestDispatcher executor = new RequestDispatcher(4);
    private final Application app = Axiom.create();
    private HttpConnection connection;

    @AfterEach void stop() throws Exception {
        app.close();
        executor.close();
        executor.termination().toCompletableFuture().get(5, TimeUnit.SECONDS);
    }

    private static final class Socket extends DuplexEmbeddedChannel { }

    private Socket socket() { return socket(TransportSettings.DEFAULTS); }

    private Socket socket(TransportSettings settings) {
        app.maxRequestBody(16);
        app.start();
        var channel = new Socket();
        channel.freezeTime();
        connection = new HttpConnection(app, executor, settings, null, () -> false);
        channel.pipeline().addLast(new RequestDecoder(NettyServer.decoderConfig()), new HttpResponseEncoder(), connection);
        return channel;
    }

    @Test void errorResponseHalfClosesAndDiscardsInputUntilTheLingerTimeoutWhileInputKeepsArriving() {
        app.post("/", ctx -> { throw new AssertionError("must not execute"); });
        var channel = socket();
        try {
            channel.writeInbound(ascii("POST / HTTP/1.1\r\nHost: a\r\nContent-Length: 17\r\n\r\n0123456789"));
            assertThat(outbound(channel)).startsWith("HTTP/1.1 413 ").contains("connection: close");
            assertThat(channel.outputShutdown).isTrue();
            assertThat(channel.isActive()).isTrue();
            // The rest of the body, and anything after it, is released unread.
            var rest = ascii("0123456POST / HTTP/1.1\r\nHost: a\r\nContent-Length: 0\r\n\r\n");
            channel.writeInbound(rest);
            assertThat(rest.refCnt()).isZero();
            assertThat(channel.<Object>readOutbound()).isNull();
            // A client still sending keeps the connection lingering up to the total bound.
            long step = HttpConnection.LINGER_QUIET_TIMEOUT.toMillis() - 1;
            for (long elapsed = 0; elapsed < HttpConnection.LINGER_TIMEOUT.toMillis() - 1;) {
                long advance = Math.min(step, HttpConnection.LINGER_TIMEOUT.toMillis() - 1 - elapsed);
                channel.advanceTimeBy(advance, TimeUnit.MILLISECONDS);
                elapsed += advance;
                channel.runScheduledPendingTasks();
                assertThat(channel.isActive()).isTrue();
                var more = ascii("more input");
                channel.writeInbound(more);
                assertThat(more.refCnt()).isZero();
            }
            channel.advanceTimeBy(1, TimeUnit.MILLISECONDS);
            channel.runScheduledPendingTasks();
            assertThat(channel.isActive()).isFalse();
        } finally { channel.finishAndReleaseAll(); }
    }

    @Test void lingeringEndsOnceTheClientHasBeenQuietForTheQuietPeriod() {
        var channel = socket();
        try {
            channel.writeInbound(ascii("GARBAGE\r\n\r\n"));
            assertThat(outbound(channel)).startsWith("HTTP/1.1 400 ");
            assertThat(channel.outputShutdown).isTrue();
            channel.advanceTimeBy(HttpConnection.LINGER_QUIET_TIMEOUT.toMillis() - 1, TimeUnit.MILLISECONDS);
            channel.runScheduledPendingTasks();
            assertThat(channel.isActive()).isTrue();
            channel.advanceTimeBy(1, TimeUnit.MILLISECONDS);
            channel.runScheduledPendingTasks();
            assertThat(channel.isActive()).isFalse();
        } finally { channel.finishAndReleaseAll(); }
    }

    @Test void inputRestartsTheQuietPeriod() {
        var channel = socket();
        try {
            channel.writeInbound(ascii("GARBAGE\r\n\r\n"));
            assertThat(outbound(channel)).startsWith("HTTP/1.1 400 ");
            long quiet = HttpConnection.LINGER_QUIET_TIMEOUT.toMillis();
            channel.advanceTimeBy(quiet - 1, TimeUnit.MILLISECONDS);
            channel.runScheduledPendingTasks();
            channel.writeInbound(ascii("still sending"));
            // The first quiet period would have ended here.
            channel.advanceTimeBy(quiet - 1, TimeUnit.MILLISECONDS);
            channel.runScheduledPendingTasks();
            assertThat(channel.isActive()).isTrue();
            channel.advanceTimeBy(1, TimeUnit.MILLISECONDS);
            channel.runScheduledPendingTasks();
            assertThat(channel.isActive()).isFalse();
        } finally { channel.finishAndReleaseAll(); }
    }

    @Test void drainCutsTheLingerOfAClientStillSendingToTheShutdownBound() {
        // The quiet period is out of the way, so only the total bounds can end lingering.
        var channel = socket(TransportSettings.DEFAULTS.withLingerQuiet(java.time.Duration.ofHours(1)));
        try {
            channel.writeInbound(ascii("GARBAGE\r\n\r\n"));
            assertThat(outbound(channel)).startsWith("HTTP/1.1 400 ");
            channel.advanceTimeBy(100, TimeUnit.MILLISECONDS);
            channel.runScheduledPendingTasks();
            connection.drain();
            channel.writeInbound(ascii("still sending"));
            channel.advanceTimeBy(HttpConnection.SHUTDOWN_LINGER_TIMEOUT.toMillis() - 1, TimeUnit.MILLISECONDS);
            channel.runScheduledPendingTasks();
            assertThat(channel.isActive()).isTrue();
            channel.advanceTimeBy(1, TimeUnit.MILLISECONDS);
            channel.runScheduledPendingTasks();
            assertThat(channel.isActive()).isFalse();
        } finally { channel.finishAndReleaseAll(); }
    }

    @Test void lingeringThatStartsDuringADrainIsCutToTheShutdownBound() throws Exception {
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        app.get("/slow", ctx -> { entered.countDown(); release.await(); return "slow"; });
        var channel = socket(TransportSettings.DEFAULTS.withLingerQuiet(java.time.Duration.ofHours(1)));
        try {
            channel.writeInbound(ascii("GET /slow HTTP/1.1\r\nHost: a\r\n\r\n"));
            assertThat(entered.await(30, TimeUnit.SECONDS)).isTrue();
            connection.drain();
            release.countDown();
            assertThat(awaitOutputShutdown(channel)).contains("connection: close");
            channel.advanceTimeBy(HttpConnection.SHUTDOWN_LINGER_TIMEOUT.toMillis() - 1, TimeUnit.MILLISECONDS);
            channel.runScheduledPendingTasks();
            assertThat(channel.isActive()).isTrue();
            channel.advanceTimeBy(1, TimeUnit.MILLISECONDS);
            channel.runScheduledPendingTasks();
            assertThat(channel.isActive()).isFalse();
        } finally {
            release.countDown();
            channel.finishAndReleaseAll();
        }
    }

    @Test void lingeringStopsOnceTheDiscardLimitIsExceeded() {
        var channel = socket();
        try {
            channel.writeInbound(ascii("GARBAGE\r\n\r\n"));
            assertThat(outbound(channel)).startsWith("HTTP/1.1 400 ");
            var piece = new byte[1024 * 1024];
            for (int sent = 0; sent < HttpConnection.MAX_DISCARDED_INPUT; sent += piece.length) {
                channel.writeInbound(Unpooled.wrappedBuffer(piece));
                assertThat(channel.isActive()).isTrue();
            }
            channel.writeInbound(Unpooled.wrappedBuffer(new byte[1]));
            assertThat(channel.isActive()).isFalse();
        } finally { channel.finishAndReleaseAll(); }
    }

    @Test void inactivityEndsLingering() {
        var channel = socket();
        try {
            channel.writeInbound(ascii("GARBAGE\r\n\r\n"));
            assertThat(outbound(channel)).startsWith("HTTP/1.1 400 ");
            assertThat(channel.isActive()).isTrue();
            channel.pipeline().fireUserEventTriggered(io.netty.handler.timeout.IdleStateEvent.ALL_IDLE_STATE_EVENT);
            assertThat(channel.isActive()).isFalse();
        } finally { channel.finishAndReleaseAll(); }
    }

    @Test void pipelinedErrorLingersAfterTheEarlierResponses() throws Exception {
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        app.get("/slow", ctx -> { entered.countDown(); release.await(); return "slow"; });
        var channel = socket();
        try {
            channel.writeInbound(ascii("GET /slow HTTP/1.1\r\nHost: a\r\n\r\nGARBAGE\r\n\r\n"));
            assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
            channel.writeInbound(Unpooled.wrappedBuffer(new byte[1024 * 1024]));
            release.countDown();
            var text = new StringBuilder();
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (!channel.outputShutdown) {
                assertThat(System.nanoTime()).isLessThan(deadline);
                channel.runPendingTasks();
                text.append(outbound(channel));
                Thread.onSpinWait();
            }
            text.append(outbound(channel));
            assertThat(text.toString()).startsWith("HTTP/1.1 200 OK").contains("\r\n\r\nslowHTTP/1.1 400 ");
            assertThat(channel.isActive()).isTrue();
            channel.advanceTimeBy(HttpConnection.LINGER_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
            channel.runScheduledPendingTasks();
            assertThat(channel.isActive()).isFalse();
        } finally {
            release.countDown();
            channel.finishAndReleaseAll();
        }
    }

    @org.junit.jupiter.params.ParameterizedTest(name = "{0}")
    @org.junit.jupiter.params.provider.ValueSource(strings = {
            "GET / HTTP/1.1\r\nHost: a\r\nConnection: close\r\n\r\nGET / HTTP/1.1\r\nHost: a\r\n\r\n",
            "GET / HTTP/1.0\r\n\r\nGET / HTTP/1.0\r\n\r\n",
            "GET /bye HTTP/1.1\r\nHost: a\r\n\r\nGET / HTTP/1.1\r\nHost: a\r\n\r\n",
            "GET /bye HTTP/1.1\r\nHost: a\r\n\r\nGARBAGE\r\n\r\n"})
    void everyCloseAfterAResponseLingers(String requests) {
        var calls = new java.util.concurrent.atomic.AtomicInteger();
        app.get("/", ctx -> { calls.incrementAndGet(); return "hi"; });
        app.get("/bye", ctx -> { calls.incrementAndGet(); return io.axiom.http.Response.of(200, "bye").withHeader("Connection", "close"); });
        var channel = socket();
        try {
            channel.writeInbound(ascii(requests));
            var text = awaitOutputShutdown(channel);
            assertThat(text).startsWith("HTTP/1.1 200 OK").containsOnlyOnce("HTTP/1.1 ").contains("connection: close");
            assertThat(calls).hasValue(1);
            // The client may still be sending; its input is discarded while the connection lingers.
            var more = ascii("GET / HTTP/1.1\r\nHost: a\r\n\r\n");
            channel.writeInbound(more);
            assertThat(more.refCnt()).isZero();
            assertThat(calls).hasValue(1);
            assertThat(channel.isActive()).isTrue();
            channel.advanceTimeBy(HttpConnection.LINGER_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
            channel.runScheduledPendingTasks();
            assertThat(channel.isActive()).isFalse();
        } finally { channel.finishAndReleaseAll(); }
    }

    @Test void drainLingersAfterTheRunningResponse() throws Exception {
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        app.get("/slow", ctx -> { entered.countDown(); release.await(); return "slow"; });
        var channel = socket();
        try {
            channel.writeInbound(ascii("GET /slow HTTP/1.1\r\nHost: a\r\n\r\nGET /slow HTTP/1.1\r\nHost: a\r\n\r\n"));
            assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
            connection.drain();
            release.countDown();
            assertThat(awaitOutputShutdown(channel)).startsWith("HTTP/1.1 200 OK").containsOnlyOnce("HTTP/1.1 ")
                    .contains("connection: close");
            assertThat(channel.isActive()).isTrue();
            channel.advanceTimeBy(HttpConnection.LINGER_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
            channel.runScheduledPendingTasks();
            assertThat(channel.isActive()).isFalse();
        } finally {
            release.countDown();
            channel.finishAndReleaseAll();
        }
    }

    @Test void drainClosesAnIdleConnectionAtOnce() {
        var channel = socket();
        try {
            connection.drain();
            assertThat(channel.isActive()).isFalse();
            assertThat(channel.outputShutdown).isFalse();
        } finally { channel.finishAndReleaseAll(); }
    }

    @Test void largeResponseBeforeACloseReachesASlowReaderInFullWhileItKeepsSending() throws Exception {
        var body = new byte[1024 * 1024];
        new java.util.Random(3).nextBytes(body);
        app.get("/big", ctx -> body);
        app.start();
        // A long linger keeps the test independent of machine load.
        var server = NettyServer.bind(app, new java.net.InetSocketAddress("127.0.0.1", 0),
                TransportSettings.DEFAULTS.withLinger(java.time.Duration.ofSeconds(60))
                        .withLingerQuiet(java.time.Duration.ofSeconds(60)));
        var client = new java.net.Socket();
        Thread sender = null;
        try {
            // A small receive window makes the client a slow reader: most of the response is still
            // in the server's send buffer when the server is done writing it.
            client.setReceiveBufferSize(4096);
            client.connect(server.localAddress(), 5000);
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

    private static void awaitNoConnections(NettyServer server) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        while (server.connections() + server.lingering() != 0) {
            assertThat(System.nanoTime()).as("lingering connection closed").isLessThan(deadline);
            Thread.onSpinWait();
        }
    }

    /** Runs tasks posted by handler threads until the connection shuts down its output. */
    private static String awaitOutputShutdown(Socket channel) {
        var text = new StringBuilder();
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!channel.outputShutdown) {
            assertThat(channel.isActive()).as("closed without lingering").isTrue();
            assertThat(System.nanoTime()).isLessThan(deadline);
            channel.runPendingTasks();
            text.append(outbound(channel));
            Thread.onSpinWait();
        }
        return text.append(outbound(channel)).toString();
    }

    private static ByteBuf ascii(String text) { return Unpooled.copiedBuffer(text, StandardCharsets.US_ASCII); }

    private static String outbound(EmbeddedChannel channel) {
        var text = new StringBuilder();
        for (ByteBuf buffer; (buffer = channel.readOutbound()) != null;) {
            text.append(buffer.toString(StandardCharsets.US_ASCII));
            buffer.release();
        }
        return text.toString();
    }
}
