package com.jsgalactic.axiom.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.jsgalactic.axiom.Axiom;
import com.jsgalactic.axiom.application.Application;
import com.jsgalactic.axiom.http.HttpPreconditions;
import com.jsgalactic.axiom.http.InvalidRequestPathException;
import com.jsgalactic.axiom.http.Request;
import com.jsgalactic.axiom.http.StaticFiles;
import com.jsgalactic.axiom.lifecycle.Server;
import com.jsgalactic.axiom.test.TestClient;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.CancellationException;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

/** Static files through the test client and a live listener, with the same answers. */
@Timeout(60)
class StaticFilesIntegrationTest {
    private static final String CONTENT = "0123456789";

    @TempDir Path root;

    private Application app;
    private Server server;

    @AfterEach
    void close() throws Exception {
        if (app != null) {
            app.close();
            if (server != null) { server.termination().toCompletableFuture().get(10, TimeUnit.SECONDS); }
        }
    }

    private Application application(long max) throws Exception {
        Files.writeString(root.resolve("hello.txt"), CONTENT);
        Files.writeString(root.resolve("large.txt"), CONTENT.repeat(10));
        Files.writeString(root.resolve("huge.txt"), "x".repeat(64 * 1024));
        app = Axiom.create();
        app.use(HttpPreconditions.conditional());
        app.get("/files/*path", StaticFiles.directory(root).maxFileSize(max).build());
        return app;
    }

    private static Request get(String path, String... headers) {
        return request("GET", path, headers);
    }

    private static Request request(String method, String path, String... headers) {
        var map = new HashMap<String, String>();
        for (int i = 0; i < headers.length; i += 2) { map.put(headers[i], headers[i + 1]); }
        return Request.fromTarget(method, path).withHeaders(map);
    }

    @Test
    void theTestClientServesValidatorsRangesAndErrors() throws Exception {
        try (var client = TestClient.start(application(64))) {
            var file = client.execute(get("/files/hello.txt"));
            assertThat(file.status()).isEqualTo(200);
            assertThat(new String((byte[]) file.body(), StandardCharsets.UTF_8)).isEqualTo(CONTENT);
            assertThat(file.headers())
                    .containsEntry("Content-Type", "text/plain; charset=utf-8")
                    .containsEntry("Cache-Control", StaticFiles.DEFAULT_CACHE_CONTROL)
                    .containsEntry("Accept-Ranges", "bytes")
                    .doesNotContainKey("Content-Length");
            var etag = file.headers().get("ETag");
            assertThat(etag).matches("\"sha256-[0-9a-f]{64}\"");
            assertThat(file.headers().get("Last-Modified")).contains("GMT");

            var head = client.execute(request("HEAD", "/files/hello.txt"));
            assertThat(head.status()).isEqualTo(200);
            assertThat(head.body()).isNull();
            assertThat(head.headers()).containsEntry("Content-Length", "10").containsEntry("ETag", etag);

            var cached = client.execute(get("/files/hello.txt", "If-None-Match", etag));
            assertThat(cached.status()).isEqualTo(304);
            assertThat(cached.body()).isNull();
            assertThat(cached.headers()).containsEntry("ETag", etag);

            var partial = client.execute(get("/files/hello.txt", "Range", "bytes=2-5"));
            assertThat(partial.status()).isEqualTo(206);
            assertThat(partial.headers()).containsEntry("Content-Range", "bytes 2-5/10");
            assertThat(new String((byte[]) partial.body(), StandardCharsets.UTF_8)).isEqualTo("2345");

            var invalid = client.execute(get("/files/hello.txt", "Range", "bytes=20-"));
            assertThat(invalid.status()).isEqualTo(416);
            assertThat(invalid.headers()).containsEntry("Content-Range", "bytes */10");

            var oversized = client.execute(get("/files/large.txt"));
            assertThat(oversized.status()).isEqualTo(413);
            assertThat(new String((byte[]) oversized.body(), StandardCharsets.UTF_8))
                    .contains("\"code\":\"file_too_large\"");
            var missing = client.execute(get("/files/missing.txt"));
            assertThat(missing.status()).isEqualTo(404);
            assertThat(new String((byte[]) missing.body(), StandardCharsets.UTF_8))
                    .contains("\"code\":\"file_not_found\"");

            assertThatThrownBy(() -> get("/files/%2e%2e/secret.txt"))
                    .isInstanceOf(InvalidRequestPathException.class);
        }
    }

    @Test
    void aStreamCanBeReadIncrementallyAndCancelled() throws Exception {
        try (var client = TestClient.start(application(1024 * 1024))) {
            try (var stream = client.stream(get("/files/large.txt"))) {
                assertThat(stream.status()).isEqualTo(200);
                assertThat(stream.readAllText()).isEqualTo(CONTENT.repeat(10));
                assertThat(stream.completion().isDone()).isTrue();
            }
            var cancelled = client.stream(get("/files/huge.txt"));
            assertThat(cancelled.next().orElseThrow()).hasSize(16 * 1024);
            cancelled.close();
            assertThatThrownBy(() -> cancelled.completion().join())
                    .isInstanceOf(CancellationException.class);
        }
    }

    @Test
    void aLiveListenerGivesTheSameAnswers() throws Exception {
        application(64);
        server = app.listen(0);
        int port = server.localAddress().getPort();

        var file = wire(port, "GET", "/files/hello.txt", Map.of());
        assertThat(file.status()).isEqualTo(200);
        assertThat(file.body()).isEqualTo(CONTENT);
        assertThat(file.contentType()).isEqualTo("text/plain; charset=utf-8");
        assertThat(file.headers().get("etag")).matches("\"sha256-[0-9a-f]{64}\"");
        var etag = file.headers().get("etag");

        var head = wire(port, "HEAD", "/files/hello.txt", Map.of());
        assertThat(head.status()).isEqualTo(200);
        assertThat(head.body()).isEmpty();
        assertThat(head.headers().get("content-length")).contains("10");

        var cached = wire(port, "GET", "/files/hello.txt", Map.of("If-None-Match", etag));
        assertThat(cached.status()).isEqualTo(304);
        assertThat(cached.body()).isEmpty();

        var partial = wire(port, "GET", "/files/hello.txt", Map.of("Range", "bytes=2-5"));
        assertThat(partial.status()).isEqualTo(206);
        assertThat(partial.headers().get("content-range")).contains("bytes 2-5/10");
        assertThat(partial.body()).isEqualTo("2345");

        var invalid = wire(port, "GET", "/files/hello.txt", Map.of("Range", "bytes=20-"));
        assertThat(invalid.status()).isEqualTo(416);
        assertThat(invalid.headers().get("content-range")).contains("bytes */10");

        var oversized = wire(port, "GET", "/files/large.txt", Map.of());
        assertThat(oversized.status()).isEqualTo(413);
        assertThat(oversized.body()).contains("\"code\":\"file_too_large\"");
        var missing = wire(port, "GET", "/files/missing.txt", Map.of());
        assertThat(missing.status()).isEqualTo(404);
        assertThat(missing.body()).contains("\"code\":\"file_not_found\"");
        var traversal = wire(port, "GET", "/files/%2e%2e/secret.txt", Map.of());
        assertThat(traversal.status()).isEqualTo(400);
    }

    /** One request over a fresh socket, decoding either chunked or Content-Length framing. */
    private static Exchange.Reply wire(int port, String method, String path, Map<String, String> headers)
            throws IOException {
        try (var socket = new Socket()) {
            socket.connect(new InetSocketAddress("localhost", port), 5000);
            socket.setSoTimeout(10_000);
            var head = new StringBuilder(method + " " + path + " HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n");
            headers.forEach((name, value) -> head.append(name).append(": ").append(value).append("\r\n"));
            head.append("\r\n");
            socket.getOutputStream().write(head.toString().getBytes(StandardCharsets.ISO_8859_1));
            socket.getOutputStream().flush();
            InputStream in = socket.getInputStream();
            var status = line(in).split(" ", 3);
            var fields = new TreeMap<String, String>(String.CASE_INSENSITIVE_ORDER);
            for (var field = line(in); !field.isEmpty(); field = line(in)) {
                int colon = field.indexOf(':');
                fields.put(field.substring(0, colon), field.substring(colon + 1).trim());
            }
            return Exchange.Reply.of(Integer.parseInt(status[1]), fields,
                    method.equals("HEAD") ? new byte[0] : body(in, fields));
        }
    }

    private static byte[] body(InputStream in, Map<String, String> headers) throws IOException {
        var all = new ByteArrayOutputStream();
        if (headers.getOrDefault("Transfer-Encoding", "").equalsIgnoreCase("chunked")) {
            for (var size = Integer.parseInt(line(in).split(";", 2)[0], 16); size > 0;
                    size = Integer.parseInt(line(in).split(";", 2)[0], 16)) {
                all.write(in.readNBytes(size));
                line(in);
            }
            line(in);
        } else {
            int length = Integer.parseInt(headers.getOrDefault("Content-Length", "0"));
            all.write(in.readNBytes(length));
        }
        return all.toByteArray();
    }

    private static String line(InputStream in) throws IOException {
        var bytes = new ByteArrayOutputStream();
        for (int value; (value = in.read()) != -1;) {
            if (value == '\n') { return bytes.toString(StandardCharsets.ISO_8859_1).replace("\r", ""); }
            bytes.write(value);
        }
        throw new IOException("Unexpected end of response");
    }
}
