package com.jsgalactic.axiom.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.jsgalactic.axiom.Axiom;
import com.jsgalactic.axiom.application.Application;
import com.jsgalactic.axiom.error.NotFoundException;
import com.jsgalactic.axiom.lifecycle.Server;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
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
        app.get("/broken", ctx -> { throw new UnsupportedOperationException(POISON); });
        app.get("/unmapped", ctx -> { throw new ArithmeticException(POISON); });
        app.error(IllegalStateException.class, (ctx, failure) -> ctx.status(409).json(new Failure("busy")));
        app.error(NoSuchElementException.class, (ctx, failure) -> { throw new NotFoundException("item_not_found"); });
        app.error(UnsupportedOperationException.class, (ctx, failure) -> { throw new IllegalStateException(POISON); });
        server = app.listen(0);
        try (var socket = new Socket()) {
            socket.connect(server.localAddress(), 5000);
            socket.setSoTimeout(10_000);
            var out = socket.getOutputStream();
            out.write(("GET /busy HTTP/1.1\r\nHost: localhost\r\nAccept: text/plain\r\n\r\n"
                    + "GET /missing HTTP/1.1\r\nHost: localhost\r\n\r\n"
                    + "GET /broken HTTP/1.1\r\nHost: localhost\r\n\r\n"
                    + "GET /unmapped HTTP/1.1\r\nHost: localhost\r\n\r\n").getBytes(StandardCharsets.ISO_8859_1));
            out.flush();
            var in = socket.getInputStream();

            var busy = MiddlewareListenerTest.read(in, false);
            assertThat(busy.status()).isEqualTo(409);
            assertThat(busy.contentType()).isEqualTo("application/json");
            assertThat(busy.body()).isEqualTo("{\"reason\":\"busy\"}");
            // Middleware unwound before the error handler ran, so it never saw this response.
            assertThat(busy.headers()).doesNotContainKey("X-Content-Type-Options");

            var missing = MiddlewareListenerTest.read(in, false);
            assertThat(missing.status()).isEqualTo(404);
            assertThat(missing.body()).contains("\"code\":\"item_not_found\"");

            var broken = MiddlewareListenerTest.read(in, false);
            assertThat(broken.status()).isEqualTo(500);
            assertThat(broken.body()).matches(
                    "\\{\"status\":500,\"code\":\"internal_server_error\",\"requestId\":\"[A-Za-z0-9_-]+-[0-9a-f]+\"}");
            assertThat(broken.headers().get("X-Request-ID")).isEqualTo(broken.body().split("\"requestId\":\"")[1]
                    .replace("\"}", ""));

            var unmapped = MiddlewareListenerTest.read(in, false);
            assertThat(unmapped.status()).isEqualTo(500);
            assertThat(unmapped.body()).contains("\"code\":\"internal_server_error\"");
            for (var reply : java.util.List.of(busy, missing, broken, unmapped)) {
                assertThat(reply.toString()).doesNotContain("POISON", "script", "secret", "Exception", "at io.");
            }
            // The listener's own 500 for an unmapped exception closes the connection.
            assertThat(in.read()).isEqualTo(-1);
        }
    }
}
