package io.axiom.http.internal;

import static org.assertj.core.api.Assertions.assertThat;

import io.axiom.Axiom;
import io.netty.channel.ChannelOption;
import io.netty.channel.embedded.EmbeddedChannel;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("integration")
class NettyServerTest {
    @Test void sizesEventLoopsAndConfiguresSockets() throws Exception {
        try (var app = Axiom.create()) {
            app.start();
            var server = NettyServer.bind(app, new InetSocketAddress("127.0.0.1", 0));
            try {
                assertThat(server.io.executorCount())
                        .isEqualTo(Math.max(2, Runtime.getRuntime().availableProcessors()));
                var listener = server.listener.config();
                assertThat(listener.getOption(ChannelOption.SO_BACKLOG)).isEqualTo(NettyServer.BACKLOG);
                assertThat(listener.getOption(ChannelOption.SO_REUSEADDR)).isTrue();

                var child = new EmbeddedChannel();
                server.accept(child, app);
                assertThat(child.config().getOption(ChannelOption.WRITE_BUFFER_WATER_MARK))
                        .isEqualTo(NettyServer.WATER_MARK);
                child.close();
            } finally {
                server.close();
                server.termination().toCompletableFuture().get(30, TimeUnit.SECONDS);
            }
        }
    }

    @Test void testSettingsCanFixSocketBuffersAndOtherwiseKeepSystemDefaults() throws Exception {
        try (var app = Axiom.create()) {
            app.start();
            var plain = NettyServer.bind(app, new InetSocketAddress("127.0.0.1", 0));
            var fixed = NettyServer.bind(app, new InetSocketAddress("127.0.0.1", 0),
                    TransportSettings.DEFAULTS.withSocketBuffers(8192, 8192));
            try {
                assertThat(TransportSettings.DEFAULTS.receiveBuffer()).isZero();
                assertThat(TransportSettings.DEFAULTS.sendBuffer()).isZero();
                // The kernel may round the request up (Linux doubles it), never below it.
                assertThat(fixed.listener.config().getOption(ChannelOption.SO_RCVBUF)).isBetween(8192, 4 * 8192);
                assertThat(plain.connections()).isZero();
                try (var client = new java.net.Socket()) {
                    client.connect(plain.localAddress(), 30_000);
                    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
                    while (plain.connections() == 0) {
                        assertThat(System.nanoTime()).as("connection accepted").isLessThan(deadline);
                        Thread.onSpinWait();
                    }
                    assertThat(plain.connections()).isEqualTo(1);
                }
            } finally {
                plain.close();
                fixed.close();
                plain.termination().toCompletableFuture().get(10, TimeUnit.SECONDS);
                fixed.termination().toCompletableFuture().get(10, TimeUnit.SECONDS);
            }
        }
    }

    @Test void lingeringConnectionsMoveToTheirOwnSmallerPoolAndFreeTheirConnectionSlot() throws Exception {
        try (var app = Axiom.create()) {
            app.start();
            var server = NettyServer.bind(app, new InetSocketAddress("127.0.0.1", 0));
            var accepted = new ArrayList<DuplexEmbeddedChannel>();
            try {
                for (int i = 0; i < NettyServer.MAX_CONNECTIONS; i++) {
                    var channel = new DuplexEmbeddedChannel();
                    server.accept(channel, app);
                    accepted.add(channel);
                }
                assertThat(server.connections()).isEqualTo(NettyServer.MAX_CONNECTIONS);
                // A rejected request: the 400 is written at once and the connection lingers.
                for (int i = 0; i < NettyServer.MAX_LINGERING; i++) {
                    reject(accepted.get(i));
                    assertThat(server.connections()).isEqualTo(NettyServer.MAX_CONNECTIONS - i - 1);
                }
                assertThat(server.lingering()).isEqualTo(NettyServer.MAX_LINGERING);
                // The lingering pool is full: the next one lingers on its regular slot.
                var overflow = accepted.get(NettyServer.MAX_LINGERING);
                reject(overflow);
                assertThat(overflow.outputShutdown).isTrue();
                assertThat(overflow.isActive()).isTrue();
                assertThat(server.lingering()).isEqualTo(NettyServer.MAX_LINGERING);
                assertThat(server.connections()).isEqualTo(NettyServer.MAX_CONNECTIONS - NettyServer.MAX_LINGERING);
                // The freed slots take new connections; then the listener is full again.
                for (int i = 0; i < NettyServer.MAX_LINGERING; i++) {
                    var channel = new DuplexEmbeddedChannel();
                    server.accept(channel, app);
                    assertThat(channel.isActive()).isTrue();
                    accepted.add(channel);
                }
                var full = new DuplexEmbeddedChannel();
                server.accept(full, app);
                assertThat(full.isActive()).isFalse();
                // Each pool gets back exactly the slot the closing connection held.
                accepted.getFirst().close();
                assertThat(server.lingering()).isEqualTo(NettyServer.MAX_LINGERING - 1);
                assertThat(server.connections()).isEqualTo(NettyServer.MAX_CONNECTIONS);
                overflow.close();
                assertThat(server.connections()).isEqualTo(NettyServer.MAX_CONNECTIONS - 1);
            } finally {
                for (var channel : accepted) { channel.finishAndReleaseAll(); }
                server.close();
                server.termination().toCompletableFuture().get(10, TimeUnit.SECONDS);
            }
        }
    }

    private static void reject(DuplexEmbeddedChannel channel) {
        channel.writeInbound(io.netty.buffer.Unpooled.copiedBuffer("GARBAGE\r\n\r\n", java.nio.charset.StandardCharsets.US_ASCII));
        for (io.netty.buffer.ByteBuf written; (written = channel.readOutbound()) != null;) { written.release(); }
        assertThat(channel.outputShutdown).as("lingering").isTrue();
    }

    @Test void refusesConnectionsBeyondTheLimitAndReleasesSlotsOnClose() throws Exception {
        try (var app = Axiom.create()) {
            app.start();
            var server = NettyServer.bind(app, new InetSocketAddress("127.0.0.1", 0));
            var accepted = new ArrayList<EmbeddedChannel>();
            try {
                for (int i = 0; i < NettyServer.MAX_CONNECTIONS; i++) {
                    var channel = new EmbeddedChannel();
                    server.accept(channel, app);
                    assertThat(channel.isActive()).isTrue();
                    accepted.add(channel);
                }
                var refused = new EmbeddedChannel();
                server.accept(refused, app);
                assertThat(refused.isActive()).isFalse();
                // A refused connection must not consume a slot when it closes.
                var another = new EmbeddedChannel();
                server.accept(another, app);
                assertThat(another.isActive()).isFalse();

                accepted.removeFirst().close();
                var replacement = new EmbeddedChannel();
                server.accept(replacement, app);
                assertThat(replacement.isActive()).isTrue();
                accepted.add(replacement);
                var full = new EmbeddedChannel();
                server.accept(full, app);
                assertThat(full.isActive()).isFalse();
            } finally {
                for (var channel : accepted) { channel.close(); }
                server.close();
                server.termination().toCompletableFuture().get(30, TimeUnit.SECONDS);
            }
        }
    }
}
