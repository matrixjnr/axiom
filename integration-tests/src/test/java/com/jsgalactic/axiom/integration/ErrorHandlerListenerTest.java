package com.jsgalactic.axiom.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.jsgalactic.axiom.Axiom;
import com.jsgalactic.axiom.application.Application;
import com.jsgalactic.axiom.error.InternalServerErrorException;
import com.jsgalactic.axiom.error.NotFoundException;
import com.jsgalactic.axiom.lifecycle.Server;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/** Error handlers over a live listener with the real JSON codec. */
@Timeout(60)
class ErrorHandlerListenerTest {
    private static final String POISON = "POISON<script>secret-token";
    private Application app;
    private Server server;

    /** Body of an application-defined error response. */
    public record Failure(String reason) { }

    @AfterEach
    void close() throws Exception {
        if (app != null) {
            app.close();
            server.termination().toCompletableFuture().get(10, TimeUnit.SECONDS);
        }
    }

    @Test
    void mapsExceptionsOnAKeptAliveConnectionWithoutLeakingFailures() throws Exception {
        app = Axiom.create();
        app.use((ctx, next) -> next.run().withHeader("X-Content-Type-Options", "nosniff"));
        app.get("/busy", ctx -> { throw new IllegalStateException(POISON); });
        app.get("/missing", ctx -> { throw new NoSuchElementException(POISON); });
        app.get("/intended", ctx -> { throw new InternalServerErrorException(); });
        app.error(IllegalStateException.class, (ctx, failure) -> ctx.status(409).json(new Failure("busy")));
        app.error(NoSuchElementException.class, (ctx, failure) -> { throw new NotFoundException("item_not_found"); });
        server = app.listen(0);
        try (var socket = new Socket()) {
            socket.connect(server.localAddress(), 5000);
            socket.setSoTimeout(10_000);
            var out = socket.getOutputStream();
            out.write(("GET /busy HTTP/1.1\r\nHost: localhost\r\nAccept: text/plain\r\n\r\n"
                    + "GET /missing HTTP/1.1\r\nHost: localhost\r\n\r\n"
                    + "GET /intended HTTP/1.1\r\nHost: localhost\r\n\r\n").getBytes(StandardCharsets.ISO_8859_1));
            out.flush();
            var in = socket.getInputStream();

            var busy = MiddlewareListenerTest.read(in, false);
            assertThat(busy.status()).isEqualTo(409);
            assertThat(busy.contentType()).isEqualTo("application/json");
            assertThat(busy.body()).isEqualTo("{\"reason\":\"busy\"}");
            // Middleware unwound before the error handler ran, and this one has no afterError.
            assertThat(busy.headers()).doesNotContainKey("X-Content-Type-Options");

            var missing = MiddlewareListenerTest.read(in, false);
            assertThat(missing.status()).isEqualTo(404);
            assertThat(missing.body()).contains("\"code\":\"item_not_found\"");

            // A 500 the application chose is an ordinary response and keeps the connection.
            var intended = MiddlewareListenerTest.read(in, false);
            assertThat(intended.status()).isEqualTo(500);
            assertThat(intended.headers()).doesNotContainKey("Connection");
            assertThat(intended.body()).matches(
                    "\\{\"status\":500,\"code\":\"internal_server_error\",\"requestId\":\"[A-Za-z0-9_-]+-[0-9a-f]+\"}");
            assertThat(intended.headers().get("X-Request-ID")).isEqualTo(intended.body().split("\"requestId\":\"")[1]
                    .replace("\"}", ""));
            out.write("GET /busy HTTP/1.1\r\nHost: localhost\r\n\r\n".getBytes(StandardCharsets.ISO_8859_1));
            out.flush();
            assertThat(MiddlewareListenerTest.read(in, false).status()).isEqualTo(409);
            for (var reply : List.of(busy, missing, intended)) {
                assertThat(reply.toString()).doesNotContain("POISON", "script", "secret", "Exception", "at io.");
            }
        }
    }

    /** Every 500 the framework generates follows one rule: the response says close and the connection ends. */
    @Test
    void closesTheConnectionAfterEveryFrameworkGenerated500() throws Exception {
        app = Axiom.create();
        app.get("/broken", ctx -> { throw new UnsupportedOperationException(POISON); });
        app.get("/unmapped", ctx -> { throw new ArithmeticException(POISON); });
        app.get("/null", ctx -> { throw new IllegalStateException(POISON); });
        app.error(UnsupportedOperationException.class, (ctx, failure) -> { throw new IllegalStateException(POISON); });
        app.error(IllegalStateException.class, (ctx, failure) -> null);
        server = app.listen(0);
        for (var path : List.of("/broken", "/unmapped", "/null")) {
            try (var socket = new Socket()) {
                socket.connect(server.localAddress(), 5000);
                socket.setSoTimeout(10_000);
                var out = socket.getOutputStream();
                // The second request is pipelined behind a response that closes, so it is never answered.
                out.write(("GET " + path + " HTTP/1.1\r\nHost: localhost\r\n\r\n"
                        + "GET " + path + " HTTP/1.1\r\nHost: localhost\r\n\r\n").getBytes(StandardCharsets.ISO_8859_1));
                out.flush();
                var in = socket.getInputStream();
                var reply = MiddlewareListenerTest.read(in, false);
                assertThat(reply.status()).as(path).isEqualTo(500);
                assertThat(reply.headers().get("Connection")).as(path).isEqualToIgnoringCase("close");
                assertThat(reply.toString()).as(path).doesNotContain("POISON");
                assertThat(in.read()).as(path).isEqualTo(-1);
            }
        }
    }
}
