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

    private static String text(Response response) {
        return new String((byte[]) response.body(), StandardCharsets.UTF_8);
    }
}
