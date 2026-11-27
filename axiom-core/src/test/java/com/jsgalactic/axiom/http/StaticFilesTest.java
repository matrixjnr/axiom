package com.jsgalactic.axiom.http;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.jsgalactic.axiom.context.Context;
import com.jsgalactic.axiom.context.Handler;
import com.jsgalactic.axiom.error.AxiomException;
import com.jsgalactic.axiom.error.BadRequestException;
import com.jsgalactic.axiom.error.NotFoundException;
import com.jsgalactic.axiom.error.PayloadTooLargeException;
import com.jsgalactic.axiom.execution.ExecutionContext;
import com.jsgalactic.axiom.routing.Route;
import java.io.ByteArrayOutputStream;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystemException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Path safety, validators, ranges, limits and streaming behavior of {@link StaticFiles}. */
class StaticFilesTest {
    private static final String CONTENT = "0123456789";

    @TempDir Path root;

    private Path file(String name, String content) throws Exception {
        var path = root.resolve(name);
        Files.createDirectories(path.getParent());
        Files.writeString(path, content);
        return path;
    }

    private static Response handle(Handler handler, String method, String path, String... headers) throws Exception {
        var map = new HashMap<String, String>();
        for (int i = 0; i < headers.length; i += 2) { map.put(headers[i], headers[i + 1]); }
        var request = new Request(method, "/assets/file", "", map, Body.empty());
        return (Response) handler.handle(new Capture(request, path));
    }

    private static Response evaluate(Handler handler, String method, String path, String... headers) throws Exception {
        var map = new HashMap<String, String>();
        for (int i = 0; i < headers.length; i += 2) { map.put(headers[i], headers[i + 1]); }
        var request = new Request(method, "/assets/file", "", map, Body.empty());
        return HttpPreconditions.evaluate(request, (Response) handler.handle(new Capture(request, path)));
    }

    private static String body(Response response) throws Exception {
        var out = new ByteArrayOutputStream();
        if (response.isStreaming()) { response.streamBody().writeTo(writer(out)); }
        return out.toString(StandardCharsets.UTF_8);
    }

    private static BodyWriter writer(ByteArrayOutputStream out) {
        return new BodyWriter() {
            @Override public void write(byte[] bytes, int offset, int length) { out.write(bytes, offset, length); }
            @Override public long bytesWritten() { return out.size(); }
        };
    }

    @Test
    void servesAStreamWithValidatorsAndBoundedHeaders() throws Exception {
        var path = file("hello.txt", CONTENT);
        var response = handle(StaticFiles.directory(root).build(), "GET", "hello.txt");
        assertThat(response.status()).isEqualTo(200);
        assertThat(response.isStreaming()).isTrue();
        assertThat(response.headers())
                .containsEntry("Content-Type", "text/plain; charset=utf-8")
                .containsEntry("Cache-Control", StaticFiles.DEFAULT_CACHE_CONTROL)
                .containsEntry("Accept-Ranges", "bytes")
                .doesNotContainKey("Content-Length");
        assertThat(response.headers().get("ETag")).matches("\"sha256-[0-9a-f]{64}\"");
        assertThat(response.headers().get("Last-Modified")).matches("[A-Z][a-z]{2}, \\d{2} [A-Z][a-z]{2} \\d{4} .* GMT");
        assertThat(response.streamLimit()).isEqualTo(CONTENT.length() + 4096);
        assertThat(body(response)).isEqualTo(CONTENT);
        // The tag is a content hash: unchanged content keeps it, changed content does not.
        assertThat(handle(StaticFiles.directory(root).build(), "GET", "hello.txt").headers().get("ETag"))
                .isEqualTo(response.headers().get("ETag"));
        Files.writeString(path, "changed");
        assertThat(handle(StaticFiles.directory(root).build(), "GET", "hello.txt").headers().get("ETag"))
                .isNotEqualTo(response.headers().get("ETag"));
    }

    @Test
    void usesTheBoundedExtensionTableAndConfigurableOverrides() throws Exception {
        file("page.HTML", "x");
        file("app.mjs", "x");
        file("data.json", "x");
        file("logo.unknown", "x");
        var plain = StaticFiles.directory(root).build();
        assertThat(handle(plain, "GET", "page.HTML").headers().get("Content-Type"))
                .isEqualTo("text/html; charset=utf-8");
        assertThat(handle(plain, "GET", "app.mjs").headers().get("Content-Type"))
                .isEqualTo("text/javascript; charset=utf-8");
        assertThat(handle(plain, "GET", "data.json").headers().get("Content-Type")).isEqualTo("application/json");
        assertThat(handle(plain, "GET", "logo.unknown").headers().get("Content-Type"))
                .isEqualTo(StaticFiles.DEFAULT_CONTENT_TYPE);
        var overridden = StaticFiles.directory(root).contentType("unknown", "application/x-custom").build();
        assertThat(handle(overridden, "GET", "logo.unknown").headers().get("Content-Type"))
                .isEqualTo("application/x-custom");
    }

    @ParameterizedTest
    @ValueSource(strings = {"../secret.txt", "a/../../secret.txt", "/etc/passwd", "a\\b", "a%2Fb", "a%2fb",
            "a%5Cb", "a%00b", "a%2Eb", "a/./b", "a//b", "a b", "a%zz", "a%"})
    void refusesUnsafePathsBeforeReadingThem(String path) throws Exception {
        file("secret.txt", "secret");
        var outside = Files.writeString(Files.createTempDirectory("static-outside").resolve("secret.txt"), "secret");
        assertThatThrownBy(() -> handle(StaticFiles.directory(outside.getParent()).build(), "GET", path))
                .isInstanceOfSatisfying(BadRequestException.class,
                        failure -> assertThat(failure.code()).isEqualTo("invalid_path_encoding"));
    }

    @Test
    void answersMissingFilesDirectoriesAndOversizedFilesWithoutLeakingDetails() throws Exception {
        file("big.txt", CONTENT);
        var handler = StaticFiles.directory(root).maxFileSize(4).build();
        assertThatThrownBy(() -> handle(handler, "GET", "missing.txt"))
                .isInstanceOfSatisfying(NotFoundException.class,
                        failure -> assertThat(failure.code()).isEqualTo("file_not_found"));
        assertThatThrownBy(() -> handle(handler, "GET", ""))
                .isInstanceOf(NotFoundException.class);
        assertThatThrownBy(() -> handle(handler, "GET", "big.txt"))
                .isInstanceOfSatisfying(PayloadTooLargeException.class,
                        failure -> assertThat(failure.code()).isEqualTo("file_too_large"));
        assertThatThrownBy(() -> handle(handler, "POST", "big.txt"))
                .isInstanceOfSatisfying(AxiomException.class, failure -> assertThat(failure.status()).isEqualTo(405));
    }

    @Test
    void followsSymlinksOnlyInsideTheRoot() throws Exception {
        var inside = file("inside.txt", CONTENT);
        var outside = Files.writeString(Files.createTempDirectory("static-link").resolve("outside.txt"), CONTENT);
        try {
            Files.createSymbolicLink(root.resolve("linked.txt"), inside);
            Files.createSymbolicLink(root.resolve("escaping.txt"), outside);
            var handler = StaticFiles.directory(root).build();
            assertThat(body(handle(handler, "GET", "linked.txt"))).isEqualTo(CONTENT);
            assertThatThrownBy(() -> handle(handler, "GET", "escaping.txt")).isInstanceOf(NotFoundException.class);
        } catch (UnsupportedOperationException | FileSystemException unsupported) {
            // Some file systems do not grant the right to create symbolic links.
        }
    }

    @Test
    void headHasNoStreamAndCarriesTheRepresentationLength() throws Exception {
        file("hello.txt", CONTENT);
        var handler = StaticFiles.directory(root).build();
        var response = handle(handler, "HEAD", "hello.txt");
        // The runtime drops a HEAD response's stream; its headers are what the listener sends.
        assertThat(response.headers())
                .containsEntry("Content-Length", "10")
                .containsEntry("Content-Type", "text/plain; charset=utf-8");
        assertThat(response.headers().get("ETag")).startsWith("\"sha256-");
        var cached = evaluate(handler, "HEAD", "hello.txt",
                "If-None-Match", response.headers().get("ETag"));
        assertThat(cached.status()).isEqualTo(304);
        assertThat(cached.headers()).doesNotContainKey("Content-Length");
    }

    @Test
    void conditionalAndRangeRequestsUseTheSameEvaluationAsBufferedResponses() throws Exception {
        file("hello.txt", CONTENT);
        var handler = StaticFiles.directory(root).build();
        var etag = handle(handler, "GET", "hello.txt").headers().get("ETag");

        assertThat(evaluate(handler, "GET", "hello.txt", "If-None-Match", etag).status()).isEqualTo(304);
        assertThat(evaluate(handler, "GET", "hello.txt", "If-None-Match", "\"other\"").status()).isEqualTo(200);
        assertThat(evaluate(handler, "GET", "hello.txt", "If-Match", "\"other\"").status()).isEqualTo(412);
        var modified = handle(handler, "GET", "hello.txt").headers().get("Last-Modified");
        assertThat(evaluate(handler, "GET", "hello.txt", "If-Modified-Since", modified).status()).isEqualTo(304);

        var single = evaluate(handler, "GET", "hello.txt", "Range", "bytes=2-5");
        assertThat(single.status()).isEqualTo(206);
        assertThat(single.isStreaming()).isTrue();
        assertThat(single.headers()).containsEntry("Content-Range", "bytes 2-5/10")
                .containsEntry("Accept-Ranges", "bytes")
                .containsEntry("ETag", etag);
        assertThat(body(single)).isEqualTo("2345");

        var suffix = evaluate(handler, "GET", "hello.txt", "Range", "bytes=-3");
        assertThat(suffix.headers()).containsEntry("Content-Range", "bytes 7-9/10");
        assertThat(body(suffix)).isEqualTo("789");

        var several = evaluate(handler, "GET", "hello.txt", "Range", "bytes=0-1,4-5");
        assertThat(several.status()).isEqualTo(206);
        assertThat(several.headers().get("Content-Type")).isEqualTo(
                "multipart/byteranges; boundary=" + HttpPreconditions.BOUNDARY);
        assertThat(body(several)).contains("Content-Range: bytes 0-1/10", "Content-Range: bytes 4-5/10", "01", "45");

        var unsatisfiable = evaluate(handler, "GET", "hello.txt", "Range", "bytes=10-");
        assertThat(unsatisfiable.status()).isEqualTo(416);
        assertThat(unsatisfiable.body()).isNull();
        assertThat(unsatisfiable.headers()).containsEntry("Content-Range", "bytes */10");

        var ignored = evaluate(handler, "GET", "hello.txt", "If-Range", "\"other\"", "Range", "bytes=0-1");
        assertThat(ignored.status()).isEqualTo(200);
        assertThat(body(ignored)).isEqualTo(CONTENT);
    }

    @Test
    void servesClasspathFilesFromDirectoriesAndJars() throws Exception {
        var fromDirectory = StaticFiles.classpath("assets", getClass().getClassLoader()).build();
        assertThat(body(handle(fromDirectory, "GET", "hello.txt"))).isEqualTo(CONTENT);
        assertThatThrownBy(() -> handle(fromDirectory, "GET", "../secret.txt")).isInstanceOf(BadRequestException.class);

        var jar = Files.createTempFile("static-files", ".jar");
        try (var out = new JarOutputStream(Files.newOutputStream(jar))) {
            out.putNextEntry(new JarEntry("assets/"));
            out.closeEntry();
            out.putNextEntry(new JarEntry("assets/jar.txt"));
            out.write(CONTENT.getBytes(StandardCharsets.UTF_8));
            out.closeEntry();
        }
        try (var loader = new URLClassLoader(new URL[] {jar.toUri().toURL()}, null)) {
            var fromJar = StaticFiles.classpath("assets", loader).build();
            var response = handle(fromJar, "GET", "jar.txt");
            assertThat(body(response)).isEqualTo(CONTENT);
            assertThat(response.headers().get("ETag")).matches("\"sha256-[0-9a-f]{64}\"");
        }
    }

    @Test
    void validatesItsConfiguration() {
        assertThatThrownBy(() -> StaticFiles.directory(root).maxFileSize(0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> StaticFiles.directory(root).capture("bad-name"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> StaticFiles.directory(root).cacheControl("bad\rvalue"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> StaticFiles.directory(root).contentType("js", "text/javascript\r\nX: y"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> StaticFiles.classpath("/a/../b")).isInstanceOf(IllegalArgumentException.class);
    }

    /** Minimal context that exposes one capture without a runtime provider. */
    private record Capture(Request request, String value) implements Context {
        @Override public ExecutionContext execution() { throw new UnsupportedOperationException(); }
        @Override public Context status(int status) { return this; }
        @Override public Route route() { throw new UnsupportedOperationException(); }
        @Override public String path(String name) {
            if (!name.equals("path")) { throw new IllegalArgumentException("Undeclared capture"); }
            return value;
        }
        @Override public Map<String, String> pathParameters() { return Map.of("path", value); }
        @Override public <T> T body(Class<T> type) { throw new UnsupportedOperationException(); }
        @Override public Response response(Object body) { return Response.of(200, body); }
    }
}
