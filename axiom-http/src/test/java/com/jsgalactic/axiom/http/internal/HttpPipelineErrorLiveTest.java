package com.jsgalactic.axiom.http.internal;

import static org.assertj.core.api.Assertions.assertThat;

import com.jsgalactic.axiom.Axiom;
import com.jsgalactic.axiom.application.Application;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * A listener error on a later pipelined request over real sockets; the embedded-channel cases are in
 * {@link HttpPipelineErrorTest}.
 */
@Tag("integration")
class HttpPipelineErrorLiveTest {
    private static final String SLOW = "POST /slow HTTP/1.1\r\nHost: a\r\nContent-Length: 4\r\n\r\nbody";
    private static final String NEXT = "GET /next HTTP/1.1\r\nHost: a\r\n\r\n";

    private final CountDownLatch entered = new CountDownLatch(1);
    private final CountDownLatch release = new CountDownLatch(1);
    private final AtomicBoolean interrupted = new AtomicBoolean();
    private final Application app = Axiom.create();

    @BeforeEach void routes() {
        app.maxRequestBody(16);
        app.requestTimeout(Duration.ofSeconds(30));
        app.post("/slow", ctx -> {
            entered.countDown();
            try { release.await(); }
            catch (InterruptedException cancelled) { interrupted.set(true); throw cancelled; }
            return "slow:" + ctx.request().body().length();
        });
        app.get("/next", ctx -> "next");
        app.post("/after", ctx -> "after");
    }

    @AfterEach void stop() {
        release.countDown();
        app.close();
    }

    @Test void overTheNetworkADisconnectAfterMuchDiscardedInputStillInterruptsTheHandler() throws Exception {
        app.start();
        var server = NettyServer.bind(app, new java.net.InetSocketAddress("127.0.0.1", 0));
        try {
            var wire = new Wire(server);
            wire.write(SLOW + "GARBAGE\r\n\r\n");
            assertThat(entered.await(30, TimeUnit.SECONDS)).isTrue();
            // Several megabytes of input after the error, then a disconnect.
            var junk = new byte[64 * 1024];
            for (int sent = 0; sent < 3 * 1024 * 1024; sent += junk.length) { wire.socket.getOutputStream().write(junk); }
            wire.close();
            awaitInterrupted();
        } finally { stop(server); }
    }


    @Test void overTheNetworkTheEarlierResponseArrivesBeforeTheErrorAndClose() throws Exception {
        app.start();
        var server = NettyServer.bind(app, new java.net.InetSocketAddress("127.0.0.1", 0));
        try (var wire = new Wire(server)) {
            wire.write(SLOW + NEXT + "POST /after HTTP/1.1\r\nHost: a\r\nContent-Length: 17\r\n\r\n");
            assertThat(entered.await(30, TimeUnit.SECONDS)).isTrue();
            release.countDown();
            assertThat(wire.read(false).text()).isEqualTo("slow:4");
            assertThat(wire.read(false).text()).isEqualTo("next");
            var rejected = wire.read(false);
            assertThat(rejected.status()).isEqualTo(413);
            assertThat(rejected.headers()).containsEntry("connection", "close");
            assertThat(wire.socket.getInputStream().read()).isEqualTo(-1);
            assertThat(interrupted).isFalse();
        } finally { stop(server); }
    }


    @Test void overTheNetworkADisconnectBehindAnErrorInterruptsTheHandler() throws Exception {
        app.start();
        var server = NettyServer.bind(app, new java.net.InetSocketAddress("127.0.0.1", 0));
        try {
            var wire = new Wire(server);
            wire.write(SLOW + "GARBAGE\r\n\r\n");
            assertThat(entered.await(30, TimeUnit.SECONDS)).isTrue();
            wire.close();
            awaitInterrupted();
        } finally { stop(server); }
    }


    private static void stop(NettyServer server) throws Exception {
        server.close();
        server.termination().toCompletableFuture().get(10, TimeUnit.SECONDS);
    }

    private void awaitInterrupted() {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        while (!interrupted.get()) {
            assertThat(System.nanoTime()).isLessThan(deadline);
            Thread.onSpinWait();
        }
    }
}
