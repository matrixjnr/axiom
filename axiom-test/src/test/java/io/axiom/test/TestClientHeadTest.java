package io.axiom.test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

import io.axiom.Axiom;
import io.axiom.http.Request;
import io.axiom.http.Response;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

/** HEAD through the test client reports what a listener sends: the GET length, no body. */
class TestClientHeadTest {
    @Test
    void headCarriesTheLengthOfTheGetRepresentationWithoutItsBody() throws Exception {
        var app = Axiom.create();
        app.get("/text", ctx -> "naïve €");
        app.get("/bytes", ctx -> new byte[42]);
        app.get("/claimed", ctx -> Response.of(200, "abc").withHeader("Content-Length", "999"));
        app.get("/no-content", ctx -> null);
        try (var client = TestClient.start(app)) {
            for (var path : new String[] {"/text", "/bytes", "/claimed"}) {
                var get = client.get(path);
                var head = client.execute(new Request("HEAD", path));
                int length = get.body() instanceof byte[] bytes ? bytes.length
                        : ((String) get.body()).getBytes(StandardCharsets.UTF_8).length;
                assertThat(head.status()).isEqualTo(get.status());
                assertThat(head.body()).isNull();
                assertThat(head.headers()).as(path).containsEntry("Content-Length", String.valueOf(length));
            }
            var noContent = client.execute(new Request("HEAD", "/no-content"));
            assertThat(noContent.status()).isEqualTo(204);
            assertThat(noContent.headers()).doesNotContainKey("Content-Length");
            var missing = client.execute(new Request("HEAD", "/missing"));
            assertThat(missing.status()).isEqualTo(404);
            assertThat(missing.headers()).doesNotContainKey("Content-Length");
        }
    }

    @Test
    void headFailsWhereGetFailsForAnUnsendableBody() throws Exception {
        var app = Axiom.create();
        app.get("/huge", ctx -> new byte[1024 * 1024 + 1]);
        // Headers at the limit: the added Content-Length does not push HEAD over it.
        app.get("/full-headers", ctx -> Response.of(200, "x").withHeader("A", "v".repeat(8192 - 5 - 41)));
        try (var client = TestClient.start(app)) {
            assertThat(client.get("/full-headers").status()).isEqualTo(200);
            assertThat(client.execute(new Request("HEAD", "/full-headers")).headers()).containsEntry("Content-Length", "1");
            assertThatIllegalStateException().isThrownBy(() -> client.get("/huge"));
            assertThatIllegalStateException().isThrownBy(() -> client.execute(new Request("HEAD", "/huge")));
        }
    }
}
