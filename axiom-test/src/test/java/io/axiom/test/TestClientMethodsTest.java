package io.axiom.test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import io.axiom.Axiom;
import io.axiom.http.Request;
import org.junit.jupiter.api.Test;

/** Method handling through the test client, which must agree with the listener. */
class TestClientMethodsTest {
    @Test
    void rejectsMethodsThatAreNotTokensAndMatchesTheRestCaseSensitively() throws Exception {
        var app = Axiom.create();
        app.route("PROPFIND", "/x", ctx -> ctx.method());
        app.get("/x", ctx -> ctx.method());
        try (var client = TestClient.start(app)) {
            // The listener answers these with 400 before routing.
            assertThatIllegalArgumentException().isThrownBy(() -> Request.fromTarget("G(T", "/x"));
            assertThatIllegalArgumentException().isThrownBy(() -> Request.fromTarget("G T", "/x"));
            assertThat(client.execute(new Request("PROPFIND", "/x")).body()).isEqualTo("PROPFIND");
            var lower = client.execute(new Request("propfind", "/x"));
            assertThat(lower.status()).isEqualTo(405);
            assertThat(lower.headers()).containsEntry("Allow", "GET, HEAD, PROPFIND");
        }
    }
}
