package io.axiom.http.internal;

import static org.assertj.core.api.Assertions.assertThat;

import io.axiom.Axiom;
import io.netty.channel.ChannelOption;
import io.netty.channel.embedded.EmbeddedChannel;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

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
                server.termination().toCompletableFuture().get(5, TimeUnit.SECONDS);
            }
        }
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
                server.termination().toCompletableFuture().get(5, TimeUnit.SECONDS);
            }
        }
    }
}
