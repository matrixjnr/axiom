package com.jsgalactic.axiom.server;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.jsgalactic.axiom.Axiom;
import com.jsgalactic.axiom.application.Application;
import com.jsgalactic.axiom.http.Body;
import com.jsgalactic.axiom.http.Request;
import com.jsgalactic.axiom.http.Response;
import java.lang.management.ManagementFactory;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Request bodies reach codecs as read-only views, and array-only codecs keep working. */
class CodecViewTest {
    private static final int LARGE = 8 * 1024 * 1024;

    record Note(String title, int priority) { }

    /** Starts an application with {@link ViewCodec} installed next to the array-only PairsCodec. */
    private static Application start(Application app, Path services) throws Exception {
        var file = services.resolve("META-INF/services/com.jsgalactic.axiom.codec.spi.BodyCodec");
        Files.createDirectories(file.getParent());
        Files.writeString(file, ViewCodec.class.getName() + "\n");
        var thread = Thread.currentThread();
        var original = thread.getContextClassLoader();
        try (var loader = new URLClassLoader(new URL[] {services.toUri().toURL()}, original)) {
            thread.setContextClassLoader(loader);
            return app.start();
        } finally { thread.setContextClassLoader(original); }
    }

    private static Request post(String path, String contentType, byte[] body) {
        return new Request("POST", path).withBody(Body.of(contentType, body));
    }

    @Test void snapshotsCustomCodecResponsesDuringTheCall(@TempDir Path services) throws Exception {
        var app = Axiom.create();
        var value = new StringBuilder("before");
        app.get("/custom", ctx -> {
            var response = ctx.status(201).encode(ViewCodec.MEDIA_TYPE, value);
            value.append("-after");
            return response;
        });
        try (var running = start(app, services)) {
            var response = running.handle(Request.get("/custom"));
            assertThat(response.status()).isEqualTo(201);
            assertThat(response.headers()).containsEntry("Content-Type", ViewCodec.MEDIA_TYPE);
            assertThat((byte[]) response.body()).isEqualTo("before".getBytes(StandardCharsets.UTF_8));
        }
    }

    @Test void ownsCodecOutputAndRejectsNullOutputImmediately(@TempDir Path services) throws Exception {
        var app = Axiom.create();
        var bytes = "before".getBytes(StandardCharsets.UTF_8);
        app.get("/owned", ctx -> {
            var response = ctx.encode(ViewCodec.MEDIA_TYPE, new byte[][] {bytes});
            bytes[0] = 'X';
            return response;
        });
        app.get("/null", ctx -> {
            assertThatThrownBy(() -> ctx.encode(ViewCodec.MEDIA_TYPE, new byte[0][]))
                    .isInstanceOf(IllegalStateException.class).hasMessageContaining("returned null");
            return "checked";
        });
        try (var running = start(app, services)) {
            assertThat((byte[]) running.handle(Request.get("/owned")).body())
                    .isEqualTo("before".getBytes(StandardCharsets.UTF_8));
            assertThat(running.handle(Request.get("/null")).body()).isEqualTo("checked");
        }
    }

    @Test void passesTheBodyAsAReadOnlyViewWithoutCopyingIt(@TempDir Path services) throws Exception {
        var threads = (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
        boolean measurable = threads.isThreadAllocatedMemorySupported() && threads.isThreadAllocatedMemoryEnabled();
        if (Boolean.getBoolean("axiom.requireAllocationTests")) {
            assertThat(measurable).as("thread allocation measurement is required by axiom.requireAllocationTests").isTrue();
        }
        assumeTrue(measurable);
        var allocated = new AtomicLong(-1);
        var app = Axiom.create().maxRequestBody(LARGE);
        app.post("/view", ctx -> {
            ctx.body(String.class); // Loads and links the decoding path before measuring.
            long before = threads.getCurrentThreadAllocatedBytes();
            var description = ctx.body(String.class);
            allocated.set(threads.getCurrentThreadAllocatedBytes() - before);
            return description;
        });
        var content = new byte[LARGE];
        Arrays.fill(content, (byte) 'v');
        try (var running = start(app, services)) {
            // In-memory handling runs the handler on this platform thread, where allocation is measurable.
            Response response = running.handle(post("/view", ViewCodec.MEDIA_TYPE, content));
            assertThat(response.body()).isEqualTo("readOnly=true;remaining=" + LARGE + ";first=v");
        }
        // The handler ran on this thread, so a missing measurement is a test defect, not a skip.
        assertThat(allocated.get()).as("allocation measured on the handler thread").isGreaterThanOrEqualTo(0);
        assertThat(allocated.get()).as("bytes allocated while decoding an %d-byte body", LARGE)
                .isLessThan(LARGE / 8);
    }

    @Test void stillDecodesWithCodecsThatImplementOnlyTheArrayMethod(@TempDir Path services) throws Exception {
        var app = Axiom.create();
        app.post("/notes", ctx -> ctx.body(Note.class).title() + "/" + ctx.body(Note.class).priority());
        try (var running = start(app, services)) {
            var response = running.handle(post("/notes", "application/json",
                    "title=hello;priority=2".getBytes(StandardCharsets.UTF_8)));
            assertThat(response.body()).isEqualTo("hello/2");
        }
    }
}
