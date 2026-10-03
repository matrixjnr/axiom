package com.jsgalactic.axiom.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.jsgalactic.axiom.Axiom;
import com.jsgalactic.axiom.application.Application;
import com.jsgalactic.axiom.error.LengthRequiredException;
import com.jsgalactic.axiom.lifecycle.Server;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * The documented use of {@link LengthRequiredException}: an endpoint that accepts only bodies
 * with a declared length refuses chunked ones. The listener itself never answers 411.
 */
@Timeout(60)
class LengthRequiredListenerTest {
    private Application app;
    private Server server;

    @AfterEach
    void close() throws Exception {
        if (app != null) {
            app.close();
            server.termination().toCompletableFuture().get(10, TimeUnit.SECONDS);
        }
    }

    @Test
    void anEndpointCanRequireADeclaredLengthAndTheConnectionStaysUsable() throws Exception {
        app = Axiom.create();
        app.post("/upload", ctx -> {
            if (ctx.header("Content-Length").isEmpty()) { throw new LengthRequiredException(); }
            return ctx.status(201).text("stored " + ctx.request().body().bytes().length);
        });
        server = app.listen(0);
        try (var socket = new Socket()) {
            socket.connect(server.localAddress(), 5000);
            socket.setSoTimeout(10_000);
            var out = socket.getOutputStream();
            out.write(("POST /upload HTTP/1.1\r\nHost: localhost\r\nContent-Type: text/plain\r\n"
                    + "Transfer-Encoding: chunked\r\n\r\n5\r\nhello\r\n0\r\n\r\n"
                    + "POST /upload HTTP/1.1\r\nHost: localhost\r\nContent-Type: text/plain\r\n"
                    + "Content-Length: 5\r\n\r\nhello").getBytes(StandardCharsets.ISO_8859_1));
            out.flush();
            var in = socket.getInputStream();

            var refused = MiddlewareListenerTest.read(in, false);
            assertThat(refused.status()).isEqualTo(411);
            assertThat(refused.contentType()).isEqualTo("application/problem+json");
            assertThat(refused.body()).matches(
                    "\\{\"status\":411,\"code\":\"length_required\",\"requestId\":\"[A-Za-z0-9_-]+-[0-9a-f]+\"}");

            var accepted = MiddlewareListenerTest.read(in, false);
            assertThat(accepted.status()).isEqualTo(201);
            assertThat(accepted.body()).isEqualTo("stored 5");
        }
    }

    @Test
    void theListenerTreatsAMissingLengthAsAnEmptyBodyInsteadOfAnswering411() throws Exception {
        app = Axiom.create();
        app.post("/echo", ctx -> ctx.text("length " + ctx.request().body().bytes().length));
        server = app.listen(0);
        try (var socket = new Socket()) {
            socket.connect(server.localAddress(), 5000);
            socket.setSoTimeout(10_000);
            socket.getOutputStream().write(("POST /echo HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n")
                    .getBytes(StandardCharsets.ISO_8859_1));
            socket.getOutputStream().flush();
            var reply = MiddlewareListenerTest.read(socket.getInputStream(), false);
            assertThat(reply.status()).isEqualTo(200);
            assertThat(reply.body()).isEqualTo("length 0");
        }
    }
}
