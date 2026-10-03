package com.jsgalactic.axiom.test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

import com.jsgalactic.axiom.Axiom;
import com.jsgalactic.axiom.http.Response;
import org.junit.jupiter.api.Test;

/** The test client refuses exactly the responses a listener answers with 500. */
class TestClientSerializationTest {
    private static final int LIMIT = 1024 * 1024;

    @Test
    void refusesTextWhoseUtf8EncodingExceedsTheBodyLimit() throws Exception {
        var app = Axiom.create();
        // Fewer characters than the limit, but two bytes each in UTF-8.
        app.get("/wide", ctx -> "é".repeat(LIMIT / 2 + 1));
        app.get("/fits", ctx -> "é".repeat(LIMIT / 2));
        app.get("/ascii", ctx -> "x".repeat(LIMIT));
        app.get("/bytes", ctx -> new byte[LIMIT]);
        app.get("/over", ctx -> new byte[LIMIT + 1]);
        try (var client = TestClient.start(app)) {
            assertThatIllegalStateException().isThrownBy(() -> client.get("/wide"))
                    .withMessageContaining("exceeds").withMessageContaining("500");
            assertThat(client.get("/fits").status()).isEqualTo(200);
            assertThat(client.get("/ascii").status()).isEqualTo(200);
            assertThat(client.get("/bytes").status()).isEqualTo(200);
            assertThatIllegalStateException().isThrownBy(() -> client.get("/over"));
        }
    }

    @Test
    void appliesTheHeaderSizeAndLatin1Rules() throws Exception {
        var app = Axiom.create();
        // 8192 counted characters exactly: name (1) + value + 4 per field, with no other headers.
        app.get("/at-limit", ctx -> Response.of(204, null).withHeader("A", "v".repeat(8192 - 5)));
        app.get("/over-limit", ctx -> Response.of(204, null).withHeader("A", "v".repeat(8192 - 4)));
        app.get("/latin1", ctx -> Response.of(204, null).withHeader("A", "ÿ"));
        app.get("/wide", ctx -> Response.of(204, null).withHeader("A", "Ā"));
        try (var client = TestClient.start(app)) {
            assertThat(client.get("/at-limit").status()).isEqualTo(204);
            assertThatIllegalStateException().isThrownBy(() -> client.get("/over-limit"));
            assertThat(client.get("/latin1").status()).isEqualTo(204);
            assertThatIllegalStateException().isThrownBy(() -> client.get("/wide"));
        }
    }
}
