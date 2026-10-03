package com.jsgalactic.axiom.http.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.jsgalactic.axiom.Axiom;
import com.jsgalactic.axiom.lifecycle.ListenerOptions;
import com.jsgalactic.axiom.server.internal.execution.RequestDispatcher;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.http.HttpResponseEncoder;
import io.netty.handler.timeout.IdleStateHandler;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** Each public listener option changes the behavior of a listener that is given it. */
@Tag("integration")
class ListenerOptionsTransportTest {
    private static final InetSocketAddress LOOPBACK = new InetSocketAddress("127.0.0.1", 0);

    private static NettyServer listen(Fixture fixture, ListenerOptions options) throws IOException {
        var server = fixture.app.listen(LOOPBACK, options);
        fixture.servers.add(server);
        return (NettyServer) server;
    }

    @Test void listenPassesTheOptionsToTheTransportAndDefaultsMatchTheConstants() throws Exception {
        try (var fixture = new Fixture()) {
            var options = ListenerOptions.builder().maxConnections(5).build();
            assertThat(listen(fixture, options).settings().options()).isSameAs(options);
            var plain = (NettyServer) fixture.listen();
            assertThat(plain.settings()).isEqualTo(TransportSettings.DEFAULTS);
            assertThatThrownBy(() -> fixture.app.listen(LOOPBACK, null)).isInstanceOf(NullPointerException.class);
        }
    }

    @Test void ioThreadsSizesTheEventLoops() throws Exception {
        try (var fixture = new Fixture()) {
            int other = ListenerOptions.defaults().ioThreads() + 1;
            assertThat(listen(fixture, ListenerOptions.builder().ioThreads(other).build()).io.executorCount())
                    .isEqualTo(other);
            assertThat(listen(fixture, ListenerOptions.builder().ioThreads(1).build()).io.executorCount()).isEqualTo(1);
        }
    }

    @Test void shutdownGraceBoundsHowLongAHandlerIsLeftRunning() throws Exception {
        var entered = new CountDownLatch(1);
        var interrupted = new CountDownLatch(1);
        try (var fixture = new Fixture()) {
            fixture.app.get("/", ctx -> {
                entered.countDown();
                try { new CountDownLatch(1).await(); }
                catch (InterruptedException expected) { interrupted.countDown(); Thread.currentThread().interrupt(); }
                return "late";
            });
            var server = listen(fixture, ListenerOptions.builder().shutdownGrace(Duration.ofMillis(100)).build());
            try (var wire = new Wire(server)) {
                wire.write("GET / HTTP/1.1\r\nHost: a\r\n\r\n");
                assertThat(entered.await(30, TimeUnit.SECONDS)).isTrue();
                long start = System.nanoTime();
                server.close();
                assertThat(interrupted.await(30, TimeUnit.SECONDS)).isTrue();
                server.termination().toCompletableFuture().get(30, TimeUnit.SECONDS);
                // The default grace period is five seconds; ending far sooner shows the option applied.
                assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(NettyServer.SHUTDOWN_GRACE);
            }
        }
    }

    @Test void maxConnectionsRefusesConnectionsOverTheCapWithoutAResponseAndFreesSlots() throws Exception {
        try (var fixture = new Fixture()) {
            fixture.app.get("/", ctx -> "ok");
            var server = listen(fixture, ListenerOptions.builder().maxConnections(2).build());
            try (var first = new Wire(server); var second = new Wire(server)) {
                assertThat(first.get("/").status()).isEqualTo(200);
                assertThat(second.get("/").status()).isEqualTo(200);
                awaitConnections(server, 2);
                try (var third = new Wire(server)) {
                    // Over the cap: closed at once, no status line.
                    assertThat(third.socket.getInputStream().read()).isEqualTo(-1);
                }
                assertThat(server.connections()).isEqualTo(2);
                first.socket.close();
                awaitConnections(server, 1);
                try (var replacement = new Wire(server)) {
                    assertThat(replacement.get("/").status()).isEqualTo(200);
                }
            }
        }
    }

    @Test void maxConnectionsAboveTheDefaultAdmitsMoreConnections() throws Exception {
        var cap = NettyServer.MAX_CONNECTIONS + 2;
        var options = ListenerOptions.builder().maxConnections(cap).build();
        try (var app = Axiom.create()) {
            app.start();
            var server = NettyServer.bind(app, LOOPBACK, TransportSettings.of(options));
            var accepted = new java.util.ArrayList<EmbeddedChannel>();
            try {
                for (int i = 0; i < cap; i++) {
                    var channel = new EmbeddedChannel();
                    server.accept(channel, app);
                    assertThat(channel.isActive()).isTrue();
                    accepted.add(channel);
                }
                var refused = new EmbeddedChannel();
                server.accept(refused, app);
                assertThat(refused.isActive()).isFalse();
                assertThat(server.connections()).isEqualTo(cap);
            } finally {
                for (var channel : accepted) { channel.close(); }
                server.close();
                server.termination().toCompletableFuture().get(30, TimeUnit.SECONDS);
            }
        }
    }

    @Test void maxLingeringConnectionsSizesTheLingeringPool() throws Exception {
        var options = ListenerOptions.builder().maxConnections(4).maxLingeringConnections(1).build();
        try (var app = Axiom.create()) {
            app.start();
            var server = NettyServer.bind(app, LOOPBACK, TransportSettings.of(options));
            var accepted = new java.util.ArrayList<DuplexEmbeddedChannel>();
            try {
                for (int i = 0; i < 4; i++) {
                    var channel = new DuplexEmbeddedChannel();
                    server.accept(channel, app);
                    accepted.add(channel);
                }
                reject(accepted.get(0));
                assertThat(server.lingering()).isEqualTo(1);
                assertThat(server.connections()).isEqualTo(3);
                // The pool of one is full: the next lingering connection keeps its regular slot.
                reject(accepted.get(1));
                assertThat(server.lingering()).isEqualTo(1);
                assertThat(server.connections()).isEqualTo(3);
            } finally {
                for (var channel : accepted) { channel.finishAndReleaseAll(); }
                server.close();
                server.termination().toCompletableFuture().get(10, TimeUnit.SECONDS);
            }
        }
    }

    @Test void zeroLingeringConnectionsKeepsEveryLingeringConnectionOnItsRegularSlot() throws Exception {
        var options = ListenerOptions.builder().maxConnections(2).maxLingeringConnections(0).build();
        try (var app = Axiom.create()) {
            app.start();
            var server = NettyServer.bind(app, LOOPBACK, TransportSettings.of(options));
            var channel = new DuplexEmbeddedChannel();
            try {
                server.accept(channel, app);
                reject(channel);
                assertThat(server.lingering()).isZero();
                assertThat(server.connections()).isEqualTo(1);
            } finally {
                channel.finishAndReleaseAll();
                server.close();
                server.termination().toCompletableFuture().get(10, TimeUnit.SECONDS);
            }
        }
    }

    @Test void idleTimeoutConfiguresTheInactivityHandler() throws Exception {
        var options = ListenerOptions.builder().idleTimeout(Duration.ofSeconds(7)).build();
        try (var app = Axiom.create()) {
            app.start();
            var server = NettyServer.bind(app, LOOPBACK, TransportSettings.of(options));
            var channel = new EmbeddedChannel();
            try {
                server.accept(channel, app);
                assertThat(channel.pipeline().get(IdleStateHandler.class).getAllIdleTimeInMillis()).isEqualTo(7000);
            } finally {
                channel.close();
                server.close();
                server.termination().toCompletableFuture().get(10, TimeUnit.SECONDS);
            }
        }
    }

    @Test void maxPipelinedRequestsBoundsOutstandingRequestsPerConnection() throws Exception {
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        try (var fixture = new Fixture()) {
            fixture.app.get("/", ctx -> {
                entered.countDown();
                assertThat(release.await(30, TimeUnit.SECONDS)).isTrue();
                return "ok";
            });
            var server = listen(fixture, ListenerOptions.builder().maxPipelinedRequests(2).build());
            try (var wire = new Wire(server)) {
                var request = "GET / HTTP/1.1\r\nHost: a\r\n\r\n";
                // The default allows eight outstanding requests; with two, the third is refused.
                wire.write(request + request + request);
                assertThat(entered.await(30, TimeUnit.SECONDS)).isTrue();
                release.countDown();
                assertThat(wire.read(false).status()).isEqualTo(200);
                assertThat(wire.read(false).status()).isEqualTo(200);
                var refused = wire.read(false);
                assertThat(refused.status()).isEqualTo(503);
                assertThat(refused.headers()).containsEntry("connection", "close");
            } finally { release.countDown(); }
        }
    }

    @Test void maxRequestLineSetsTheLimitForUriTooLong() throws Exception {
        try (var fixture = new Fixture()) {
            fixture.app.get("/:rest", ctx -> "ok");
            var small = listen(fixture, ListenerOptions.builder().maxRequestLine(256).build());
            var large = listen(fixture, ListenerOptions.builder().maxRequestLine(16384).build());
            var path = "/" + "a".repeat(5000);
            try (var wire = new Wire(small)) {
                assertThat(wire.get("/" + "a".repeat(100)).status()).isEqualTo(200);
                assertThat(wire.get(path).status()).isEqualTo(414);
            }
            try (var wire = new Wire(large)) { assertThat(wire.get(path).status()).isEqualTo(200); }
            // The default of 4 KiB refuses the same line.
            try (var wire = new Wire(listen(fixture, ListenerOptions.defaults()))) {
                assertThat(wire.get(path).status()).isEqualTo(414);
            }
        }
    }

    @Test void maxHeaderBytesSetsTheLimitForHeaderFieldsTooLarge() throws Exception {
        try (var fixture = new Fixture()) {
            fixture.app.get("/", ctx -> "ok");
            var small = listen(fixture, ListenerOptions.builder().maxHeaderBytes(256).build());
            var large = listen(fixture, ListenerOptions.builder().maxHeaderBytes(65536).build());
            var request = "GET / HTTP/1.1\r\nHost: a\r\nX-Big: " + "v".repeat(10_000) + "\r\n\r\n";
            try (var wire = new Wire(small)) {
                assertThat(wire.get("/").status()).isEqualTo(200);
            }
            try (var wire = new Wire(small)) {
                wire.write(request);
                assertThat(wire.read(false).status()).isEqualTo(431);
            }
            try (var wire = new Wire(large)) {
                wire.write(request);
                assertThat(wire.read(false).status()).isEqualTo(200);
            }
        }
    }

    @Test void headTimeoutSetsTheBoundOnARequestHead() throws Exception {
        var executor = new RequestDispatcher(4);
        try (var app = Axiom.create()) {
            app.start();
            var settings = TransportSettings.of(ListenerOptions.builder().headTimeout(Duration.ofSeconds(3)).build());
            var channel = new EmbeddedChannel();
            channel.freezeTime();
            channel.pipeline().addLast(new RequestDecoder(NettyServer.decoderConfig()), new HttpResponseEncoder(),
                    new HttpConnection(app, executor, settings, null, () -> false));
            try {
                channel.writeInbound(ascii("GET / HT"));
                channel.advanceTimeBy(2999, TimeUnit.MILLISECONDS);
                channel.runScheduledPendingTasks();
                assertThat(channel.isActive()).isTrue();
                channel.advanceTimeBy(1, TimeUnit.MILLISECONDS);
                channel.runScheduledPendingTasks();
                assertThat(outbound(channel)).startsWith("HTTP/1.1 408 ");
            } finally { channel.finishAndReleaseAll(); }
        } finally {
            executor.close();
            executor.termination().toCompletableFuture().get(30, TimeUnit.SECONDS);
        }
    }

    @Test void lingerTimeoutsAndDiscardLimitApplyToLingeringConnections() throws Exception {
        var executor = new RequestDispatcher(4);
        try (var app = Axiom.create()) {
            app.start();
            // Total bound 3 s, quiet 1 s, shutdown bound 200 ms, discard budget 10 bytes.
            var options = ListenerOptions.builder().lingerTimeout(Duration.ofSeconds(3))
                    .lingerQuietTimeout(Duration.ofSeconds(1)).shutdownLingerTimeout(Duration.ofMillis(200))
                    .maxDiscardedInput(10).build();
            var settings = TransportSettings.of(options);

            var quiet = lingering(app, executor, settings, null);
            try {
                quiet.channel.advanceTimeBy(999, TimeUnit.MILLISECONDS);
                quiet.channel.runScheduledPendingTasks();
                assertThat(quiet.channel.isActive()).isTrue();
                quiet.channel.advanceTimeBy(1, TimeUnit.MILLISECONDS);
                quiet.channel.runScheduledPendingTasks();
                assertThat(quiet.channel.isActive()).isFalse();
            } finally { quiet.channel.finishAndReleaseAll(); }

            var total = lingering(app, executor, settings.withLingerQuiet(Duration.ofHours(1)), null);
            try {
                total.channel.advanceTimeBy(2999, TimeUnit.MILLISECONDS);
                total.channel.runScheduledPendingTasks();
                assertThat(total.channel.isActive()).isTrue();
                total.channel.advanceTimeBy(1, TimeUnit.MILLISECONDS);
                total.channel.runScheduledPendingTasks();
                assertThat(total.channel.isActive()).isFalse();
            } finally { total.channel.finishAndReleaseAll(); }

            var closing = lingering(app, executor, settings.withLingerQuiet(Duration.ofHours(1)), null);
            try {
                closing.connection.drain();
                closing.channel.advanceTimeBy(199, TimeUnit.MILLISECONDS);
                closing.channel.runScheduledPendingTasks();
                assertThat(closing.channel.isActive()).isTrue();
                closing.channel.advanceTimeBy(1, TimeUnit.MILLISECONDS);
                closing.channel.runScheduledPendingTasks();
                assertThat(closing.channel.isActive()).isFalse();
            } finally { closing.channel.finishAndReleaseAll(); }

            var discard = lingering(app, executor, settings, null);
            try {
                discard.channel.writeInbound(Unpooled.wrappedBuffer(new byte[10]));
                assertThat(discard.channel.isActive()).isTrue();
                discard.channel.writeInbound(Unpooled.wrappedBuffer(new byte[1]));
                assertThat(discard.channel.isActive()).isFalse();
            } finally { discard.channel.finishAndReleaseAll(); }
        } finally {
            executor.close();
            executor.termination().toCompletableFuture().get(30, TimeUnit.SECONDS);
        }
    }

    private record Lingering(EmbeddedChannel channel, HttpConnection connection) { }

    /** A connection that has answered a malformed request and now lingers. */
    private static Lingering lingering(com.jsgalactic.axiom.application.Application app, RequestDispatcher executor,
            TransportSettings settings, ConnectionSlots.Slot slot) {
        var channel = new DuplexEmbeddedChannel();
        channel.freezeTime();
        var connection = new HttpConnection(app, executor, settings, slot, () -> false);
        channel.pipeline().addLast(new RequestDecoder(NettyServer.decoderConfig()), new HttpResponseEncoder(), connection);
        channel.writeInbound(ascii("GARBAGE\r\n\r\n"));
        assertThat(outbound(channel)).startsWith("HTTP/1.1 400 ");
        assertThat(channel.outputShutdown).isTrue();
        return new Lingering(channel, connection);
    }

    private static void reject(DuplexEmbeddedChannel channel) {
        channel.writeInbound(ascii("GARBAGE\r\n\r\n"));
        for (ByteBuf written; (written = channel.readOutbound()) != null;) { written.release(); }
        assertThat(channel.outputShutdown).as("lingering").isTrue();
    }

    private static void awaitConnections(NettyServer server, int expected) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        while (server.connections() != expected) {
            assertThat(System.nanoTime()).as("connections reach " + expected).isLessThan(deadline);
            Thread.onSpinWait();
        }
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
