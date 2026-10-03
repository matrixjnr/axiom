package com.jsgalactic.axiom.test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.jsgalactic.axiom.Axiom;
import com.jsgalactic.axiom.error.NotFoundException;
import com.jsgalactic.axiom.http.Response;
import java.nio.charset.StandardCharsets;
import java.util.NoSuchElementException;
import org.junit.jupiter.api.Test;

class TestClientErrorHandlerTest {
    private static final String POISON = "POISON<script>secret-token";

    @Test
    void mapsExceptionsAndNeverLeaksAFailingErrorHandler() throws Exception {
        var app = Axiom.create();
        app.get("/missing", ctx -> { throw new NoSuchElementException(POISON); });
        app.get("/broken", ctx -> { throw new IllegalStateException(POISON); });
        app.get("/unmapped", ctx -> { throw new UnsupportedOperationException(POISON); });
        app.error(NoSuchElementException.class, (ctx, failure) -> { throw new NotFoundException("item_not_found"); });
        app.error(IllegalStateException.class, (ctx, failure) -> { throw new IllegalArgumentException(POISON); });
        try (var client = TestClient.start(app)) {
            var missing = client.get("/missing");
            assertThat(missing.status()).isEqualTo(404);
            assertThat(text(missing)).contains("\"code\":\"item_not_found\"").doesNotContain("POISON");

            var broken = client.get("/broken");
            assertThat(broken.status()).isEqualTo(500);
            assertThat(broken.headers()).containsEntry("Content-Type", "application/problem+json");
            assertThat(text(broken)).matches(
                    "\\{\"status\":500,\"code\":\"internal_server_error\",\"requestId\":\"[A-Za-z0-9_-]+-[0-9a-f]+\"}");
            assertThat(text(broken) + broken.headers()).doesNotContain("POISON", "script", "secret", "Exception");

            // Without a handler, in-memory clients still see the exception itself.
            assertThatThrownBy(() -> client.get("/unmapped")).isInstanceOf(UnsupportedOperationException.class);
        }
    }

    @Test
    void mappingClientsAnswerUnmappedFailuresLikeTheListener() throws Exception {
        var app = Axiom.create();
        app.get("/unmapped", ctx -> { throw new UnsupportedOperationException(POISON); });
        app.get("/checked", ctx -> { throw new java.io.IOException(POISON); });
        app.get("/missing", ctx -> { throw new NotFoundException("item_not_found"); });
        app.get("/unsendable", ctx -> Response.of(200, new Object()));
        app.get("/stream", ctx -> { throw new IllegalStateException(POISON); });
        try (var client = TestClient.startMappingFailures(app)) {
            for (var path : new String[] {"/unmapped", "/checked", "/unsendable"}) {
                var response = client.get(path);
                assertThat(response.status()).as(path).isEqualTo(500);
                assertThat(response.headers()).as(path).containsEntry("Content-Type", "application/problem+json")
                        .containsEntry("Connection", "close");
                assertThat(text(response)).as(path).matches(
                        "\\{\"status\":500,\"code\":\"internal_server_error\",\"requestId\":\"[A-Za-z0-9_-]+-[0-9a-f]+\"}");
                assertThat(text(response) + response.headers()).as(path).doesNotContain("POISON", "script", "Exception");
            }
            // Failures that already had a response keep it.
            assertThat(client.get("/missing").status()).isEqualTo(404);
            // The streaming entry point answers a failure before the head the same way.
            try (var stream = client.stream(com.jsgalactic.axiom.http.Request.get("/stream"))) {
                assertThat(stream.status()).isEqualTo(500);
            }
            // The future completes normally, not exceptionally.
            assertThat(client.submit(com.jsgalactic.axiom.http.Request.get("/unmapped")).get().status()).isEqualTo(500);
        }
    }

    @Test
    void defaultClientsStillPropagateUnmappedFailuresAndUnsendableResponses() throws Exception {
        var app = Axiom.create();
        app.get("/unsendable", ctx -> Response.of(200, new Object()));
        app.get("/unmapped", ctx -> { throw new UnsupportedOperationException(POISON); });
        try (var client = TestClient.start(app)) {
            assertThatThrownBy(() -> client.get("/unmapped")).isInstanceOf(UnsupportedOperationException.class);
            assertThatThrownBy(() -> client.get("/unsendable")).isInstanceOf(IllegalStateException.class);
        }
    }

    private static String text(Response response) {
        return new String((byte[]) response.body(), StandardCharsets.UTF_8);
    }
}
