package com.jsgalactic.axiom.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.jsgalactic.axiom.Axiom;
import com.jsgalactic.axiom.application.Application;
import com.jsgalactic.axiom.error.InternalServerErrorException;
import com.jsgalactic.axiom.lifecycle.ListenerOptions;
import com.jsgalactic.axiom.lifecycle.RejectionObserver;
import com.jsgalactic.axiom.lifecycle.Server;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/** The observer of listener rejections over real sockets. */
@Timeout(60)
class RejectionObserverListenerTest {
    private record Seen(int status, String code, String requestId) { }

    private Application app;
    private Server server;

    @AfterEach
    void close() throws Exception {
        if (app != null) {
            app.close();
            server.termination().toCompletableFuture().get(10, TimeUnit.SECONDS);
        }
    }

    private Exchange.Reply request(String raw) throws Exception {
        try (var socket = new Socket()) {
            socket.connect(server.localAddress(), 5000);
            socket.setSoTimeout(10_000);
            socket.getOutputStream().write(raw.getBytes(StandardCharsets.ISO_8859_1));
            socket.getOutputStream().flush();
            return MiddlewareListenerTest.read(socket.getInputStream(), false);
        }
    }

    private void serve(RejectionObserver observer) throws Exception {
        app = Axiom.create();
        app.use((ctx, next) -> { throw new AssertionError("Middleware must not see listener rejections"); });
        app.get("/boom", ctx -> { throw new ArithmeticException("secret detail"); });
        app.post("/upload", ctx -> "ok");
        server = app.listen(new java.net.InetSocketAddress(java.net.InetAddress.getLoopbackAddress(), 0),
                ListenerOptions.builder().rejectionObserver(observer).build());
    }

    @Test
    void reportsEachListenerRejectionWithItsStatusCodeAndRequestId() throws Exception {
        var seen = new CopyOnWriteArrayList<Seen>();
        serve((status, code, requestId) -> seen.add(new Seen(status, code, requestId)));
        // Rejected before routing, so the throwing global middleware never runs.
        var cases = List.of(
                new Object[] {"GET / HTTP/1.1\r\n\r\n", 400, "bad_request"},
                new Object[] {"CONNECT example.com:443 HTTP/1.1\r\nHost: example.com:443\r\n\r\n", 501, "not_implemented"},
                new Object[] {"GET /" + "a".repeat(5000) + " HTTP/1.1\r\nHost: x\r\n\r\n", 414, "uri_too_long"},
                new Object[] {"GET / HTTP/1.1\r\nHost: x\r\nX-Big: " + "b".repeat(10_000) + "\r\n\r\n", 431,
                        "request_header_fields_too_large"},
                new Object[] {"POST /upload HTTP/1.1\r\nHost: x\r\nContent-Length: 9999999\r\n\r\n", 413,
                        "content_too_large"});
        for (var c : cases) {
            seen.clear();
            var reply = request((String) c[0]);
            assertThat(reply.status()).isEqualTo(c[1]);
            // The observer is called before the response is written, so it has run by now.
            assertThat(seen).singleElement().satisfies(one -> {
                assertThat(one.status()).isEqualTo(c[1]);
                assertThat(one.code()).isEqualTo(c[2]);
                assertThat(one.requestId()).isEqualTo(reply.headers().get("X-Request-ID"));
            });
        }
    }

    @Test
    void reportsTheListenersOwn500ButNotResponsesTheApplicationProduced() throws Exception {
        var seen = new CopyOnWriteArrayList<Seen>();
        app = Axiom.create();
        app.get("/boom", ctx -> { throw new ArithmeticException("secret detail"); });
        app.get("/chosen", ctx -> { throw new InternalServerErrorException(); });
        server = app.listen(new java.net.InetSocketAddress(java.net.InetAddress.getLoopbackAddress(), 0),
                ListenerOptions.builder().rejectionObserver((s, c, id) -> seen.add(new Seen(s, c, id))).build());
        assertThat(request("GET /chosen HTTP/1.1\r\nHost: x\r\n\r\n").status()).isEqualTo(500);
        assertThat(request("GET /missing HTTP/1.1\r\nHost: x\r\n\r\n").status()).isEqualTo(404);
        assertThat(seen).isEmpty();
        var boom = request("GET /boom HTTP/1.1\r\nHost: x\r\n\r\n");
        assertThat(boom.status()).isEqualTo(500);
        assertThat(seen).singleElement().satisfies(one -> {
            assertThat(one.code()).isEqualTo("internal_server_error");
            assertThat(one.requestId()).isEqualTo(boom.headers().get("X-Request-ID"));
            assertThat(one.toString()).doesNotContain("secret");
        });
    }

    @Test
    void anObserverThatThrowsNeverChangesTheResponse() throws Exception {
        serve((status, code, requestId) -> { throw new IllegalStateException("observer bug"); });
        var reply = request("GET / HTTP/1.1\r\n\r\n");
        assertThat(reply.status()).isEqualTo(400);
        assertThat(reply.body()).contains("\"code\":\"bad_request\"").doesNotContain("observer");
    }
}
