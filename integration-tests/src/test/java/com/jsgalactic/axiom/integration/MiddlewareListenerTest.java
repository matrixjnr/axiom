package com.jsgalactic.axiom.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.jsgalactic.axiom.Axiom;
import com.jsgalactic.axiom.application.Application;
import com.jsgalactic.axiom.http.Response;
import com.jsgalactic.axiom.integration.Exchange.Reply;
import com.jsgalactic.axiom.lifecycle.Server;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/** Middleware over a live listener, with raw sockets so pipelining and connection reuse are explicit. */
@Timeout(60)
class MiddlewareListenerTest {
    private static final String POISON = "POISON<script>secret-token";
    private Application app;
    private Server server;

    private Socket open() throws IOException {
        app = Axiom.create();
        app.use((ctx, next) -> next.run().withHeader("X-Content-Type-Options", "nosniff"));
        app.group("/api", api -> {
            api.use((ctx, next) -> ctx.header("Authorization").isPresent() ? next.run() : Response.of(401, null));
            api.use((ctx, next) -> next.run().withHeader("X-Route", ctx.route().path()));
            api.get("/items/:id", ctx -> "item " + ctx.path("id"));
            api.get("/broken", ctx -> "unreachable", (ctx, next) -> { throw new IllegalStateException(POISON); });
        });
        server = app.listen(0);
        var socket = new Socket();
        socket.connect(server.localAddress(), 5000);
        socket.setSoTimeout(10_000);
        return socket;
    }

    @AfterEach
    void close() throws Exception {
        if (app != null) {
            app.close();
            server.termination().toCompletableFuture().get(10, TimeUnit.SECONDS);
        }
    }

    @Test
    void appliesTheChainToPipelinedRequestsInOrderOnOneConnection() throws Exception {
        try (var socket = open()) {
            var out = socket.getOutputStream();
            out.write(("GET /api/items/1 HTTP/1.1\r\nHost: localhost\r\nAuthorization: Bearer a\r\n\r\n"
                    + "GET /api/items/2 HTTP/1.1\r\nHost: localhost\r\n\r\n"
                    + "GET /missing HTTP/1.1\r\nHost: localhost\r\n\r\n"
                    + "HEAD /api/items/3 HTTP/1.1\r\nHost: localhost\r\nAuthorization: Bearer a\r\n\r\n"
                    + "GET /api/items/4 HTTP/1.1\r\nHost: localhost\r\nAuthorization: Bearer a\r\nConnection: close\r\n\r\n")
                    .getBytes(StandardCharsets.ISO_8859_1));
            out.flush();
            var in = socket.getInputStream();

            var first = read(in, false);
            assertThat(first.status()).isEqualTo(200);
            assertThat(first.body()).isEqualTo("item 1");
            assertThat(first.headers()).containsEntry("X-Route", "/api/items/:id")
                    .containsEntry("X-Content-Type-Options", "nosniff");

            var unauthorized = read(in, false);
            assertThat(unauthorized.status()).isEqualTo(401);
            assertThat(unauthorized.headers()).containsEntry("X-Content-Type-Options", "nosniff")
                    .doesNotContainKey("X-Route");

            var missing = read(in, false);
            assertThat(missing.status()).isEqualTo(404);
            assertThat(missing.contentType()).isEqualTo("application/problem+json");
            assertThat(missing.headers()).containsEntry("X-Content-Type-Options", "nosniff");

            var head = read(in, true);
            assertThat(head.status()).isEqualTo(200);
            assertThat(head.headers()).containsEntry("X-Route", "/api/items/:id").containsEntry("Content-Length", "6");

            var last = read(in, false);
            assertThat(last.body()).isEqualTo("item 4");
            assertThat(in.read()).isEqualTo(-1);
        }
    }

    @Test
    void answersMiddlewareFailuresWithTheGenericProblemWithoutLeakingDetails() throws Exception {
        try (var socket = open()) {
            var out = socket.getOutputStream();
            out.write("GET /api/broken HTTP/1.1\r\nHost: localhost\r\nAuthorization: Bearer a\r\n\r\n"
                    .getBytes(StandardCharsets.ISO_8859_1));
            out.flush();
            var reply = read(socket.getInputStream(), false);
            assertThat(reply.status()).isEqualTo(500);
            assertThat(reply.contentType()).isEqualTo("application/problem+json");
            assertThat(reply.body()).matches("\\{\"status\":500,\"code\":\"internal_server_error\",\"requestId\":\"[A-Za-z0-9_-]+-[0-9a-f]+\"}");
            assertThat(reply.toString()).doesNotContain("POISON", "script", "secret", "IllegalStateException", "at io.");
        }
    }

    static Reply read(InputStream in, boolean head) throws IOException {
        var status = line(in);
        if (!status.startsWith("HTTP/1.1 ")) { throw new IOException("Unexpected status line"); }
        var headers = new HashMap<String, String>();
        for (String line; !(line = line(in)).isEmpty();) {
            int colon = line.indexOf(':');
            headers.put(line.substring(0, colon), line.substring(colon + 1).trim());
        }
        var length = headers.entrySet().stream().filter(e -> e.getKey().equalsIgnoreCase("Content-Length"))
                .map(Map.Entry::getValue).findFirst().orElse("0");
        var body = head ? new byte[0] : in.readNBytes(Integer.parseInt(length));
        return Reply.of(Integer.parseInt(status.substring(9, 12)), headers, body);
    }

    private static String line(InputStream in) throws IOException {
        var bytes = new ByteArrayOutputStream();
        for (int value; (value = in.read()) != -1;) {
            if (value == '\n') { return bytes.toString(StandardCharsets.ISO_8859_1).replace("\r", ""); }
            bytes.write(value);
        }
        throw new IOException("Unexpected end of stream");
    }
}
