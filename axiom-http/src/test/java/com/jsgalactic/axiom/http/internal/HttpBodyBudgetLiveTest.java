package com.jsgalactic.axiom.http.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.jsgalactic.axiom.lifecycle.ListenerOptions;
import java.net.InetSocketAddress;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** The listener-wide body budget over real sockets: uploaders that never finish hold it, and 503 follows. */
@Tag("integration")
class HttpBodyBudgetLiveTest {
    private static final InetSocketAddress LOOPBACK = new InetSocketAddress("127.0.0.1", 0);
    private static final String HEAD = "POST /echo HTTP/1.1\r\nHost: a\r\n";

    private static void awaitInFlight(NettyServer server, long expected) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        while (server.bodyBytesInFlight() != expected) {
            assertThat(System.nanoTime()).as("body bytes in flight reach " + expected).isLessThan(deadline);
            Thread.onSpinWait();
        }
    }

    @Test void slowUploadersExhaustTheBudgetAndFurtherBodiesAreAnswered503UntilOneEnds() throws Exception {
        try (var fixture = new Fixture()) {
            fixture.app.maxRequestBody(512);
            fixture.app.post("/echo", ctx -> "len:" + ctx.request().body().length());
            var server = (NettyServer) fixture.app.listen(LOOPBACK,
                    ListenerOptions.builder().maxInFlightBodyBytes(1024).build());
            fixture.servers.add(server);
            try (var first = new Wire(server); var second = new Wire(server)) {
                // Two uploads announce 512 bytes each and then stall: the budget is spent.
                first.write(HEAD + "Content-Length: 512\r\n\r\n");
                second.write(HEAD + "Content-Length: 512\r\n\r\n");
                awaitInFlight(server, 1024);
                try (var third = new Wire(server)) {
                    third.write(HEAD + "Content-Length: 1\r\n\r\n");
                    var refused = third.read(false);
                    assertThat(refused.status()).isEqualTo(503);
                    assertThat(refused.headers()).containsEntry("connection", "close");
                }
                assertThat(server.bodyBytesInFlight()).isEqualTo(1024);
                // One uploader gives up; its share is available again.
                first.socket.close();
                awaitInFlight(server, 512);
                try (var fourth = new Wire(server)) {
                    fourth.write(HEAD + "Content-Length: 5\r\n\r\nhello");
                    var reply = fourth.read(false);
                    assertThat(reply.status()).isEqualTo(200);
                    assertThat(reply.text()).isEqualTo("len:5");
                }
                // The other finishes its upload and is answered; nothing remains reserved.
                second.write("x".repeat(512));
                assertThat(second.read(false).text()).isEqualTo("len:512");
                awaitInFlight(server, 0);
            }
        }
    }

    @Test void aListenerWhoseBudgetIsSmallerThanTheBodyLimitRefusesToStart() throws Exception {
        try (var fixture = new Fixture()) {
            fixture.app.maxRequestBody(512);
            assertThatThrownBy(() -> fixture.app.listen(LOOPBACK,
                    ListenerOptions.builder().maxInFlightBodyBytes(511).build()))
                    .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("maxInFlightBodyBytes");
            // The application is unharmed, and a budget that fits works.
            fixture.servers.add(fixture.app.listen(LOOPBACK,
                    ListenerOptions.builder().maxInFlightBodyBytes(512).build()));
        }
    }

    @Test void theDefaultBudgetFitsTheLargestConfigurableBody() throws Exception {
        try (var fixture = new Fixture()) {
            fixture.app.maxRequestBody(64 * 1024 * 1024);
            fixture.listen();
        }
    }
}
