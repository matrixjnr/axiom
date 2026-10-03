package com.jsgalactic.axiom.http.internal;

import static org.assertj.core.api.Assertions.assertThat;

import com.jsgalactic.axiom.Axiom;
import com.jsgalactic.axiom.application.Application;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** The interim {@code 100 Continue} over real sockets; the frozen-time cases are in {@link HttpContinueTest}. */
@Tag("integration")
class HttpContinueLiveTest {
    private final Application app = Axiom.create();

    @AfterEach void stop() {
        app.close();
    }

    @Test void interimResponseThatTheClientReadsLeavesTheKeepAliveConnectionUsable() throws Exception {
        app.post("/echo", ctx -> new String(ctx.request().body().bytes(), StandardCharsets.UTF_8));
        app.start();
        // A response bound far shorter than the test could exceed by accident would still not fire:
        // a taken write leaves no deadline behind (see the frozen-time test).
        var server = NettyServer.bind(app, new java.net.InetSocketAddress("127.0.0.1", 0),
                TransportSettings.DEFAULTS.withResponseTimeout(java.time.Duration.ofSeconds(30)));
        try (var wire = new Wire(server)) {
            wire.write("POST /echo HTTP/1.1\r\nHost: a\r\nExpect: 100-continue\r\nContent-Length: 5\r\n\r\n");
            assertThat(wire.line()).isEqualTo("HTTP/1.1 100 Continue");
            assertThat(wire.line()).isEmpty();
            wire.write("hello");
            var reply = wire.read(false);
            assertThat(reply.status()).isEqualTo(200);
            assertThat(reply.text()).isEqualTo("hello");
            // Still open: a second exchange on the same connection works.
            wire.write("POST /echo HTTP/1.1\r\nHost: a\r\nContent-Length: 2\r\n\r\nok");
            assertThat(wire.read(false).text()).isEqualTo("ok");
        } finally {
            server.close();
            server.termination().toCompletableFuture().get(30, TimeUnit.SECONDS);
        }
    }
}
