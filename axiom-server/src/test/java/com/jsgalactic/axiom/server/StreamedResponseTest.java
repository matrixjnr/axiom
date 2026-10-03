package com.jsgalactic.axiom.server;

import static org.assertj.core.api.Assertions.assertThat;

import com.jsgalactic.axiom.Axiom;
import com.jsgalactic.axiom.http.Request;
import com.jsgalactic.axiom.http.Response;
import com.jsgalactic.axiom.server.internal.ResponseSerialization;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/** How the in-memory application treats a streamed response: HEAD, codecs and serialization rules. */
class StreamedResponseTest {
    @Test
    void headReceivesTheHeadWithoutRunningTheWriterOrAdvertisingALength() throws Exception {
        var writes = new AtomicInteger();
        try (var app = Axiom.create()) {
            app.get("/feed", ctx -> Response.stream(200, "text/plain", out -> { writes.incrementAndGet(); out.write("x"); })
                    .withHeader("X-Feed", "1"));
            app.start();
            var head = app.handle(new Request("HEAD", "/feed"));
            assertThat(head.status()).isEqualTo(200);
            assertThat(head.isStreaming()).isFalse();
            assertThat(head.body()).isNull();
            assertThat(head.headers()).containsEntry("Content-Type", "text/plain").containsEntry("X-Feed", "1")
                    .doesNotContainKey("Content-Length");
            assertThat(writes).hasValue(0);
            assertThat(app.handle(new Request("GET", "/feed")).isStreaming()).isTrue();
        }
    }

    @Test
    void aStreamIsNeverOfferedToACodecEvenWhenOneIsInstalledForItsMediaType() throws Exception {
        try (var app = Axiom.create()) {
            app.get("/events", ctx -> Response.stream(200, "application/json", out -> out.write("[]")));
            app.start();
            // The test classpath has a codec for application/json; encoding a StreamBody with it would fail.
            assertThat(app.handle(new Request("GET", "/events")).isStreaming()).isTrue();
        }
    }

    @Test
    void streamsSkipTheBodySizeRuleButNotTheHeaderRules() {
        assertThat(ResponseSerialization.rejection(Response.stream(200, "text/plain", out -> { }))).isNull();
        var oversized = Response.stream(200, "text/plain", out -> { }).withHeader("X-Big", "a".repeat(9000));
        assertThat(ResponseSerialization.rejection(oversized)).contains("headers");
    }
}
