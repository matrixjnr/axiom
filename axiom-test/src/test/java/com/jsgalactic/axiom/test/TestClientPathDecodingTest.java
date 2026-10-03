package com.jsgalactic.axiom.test;

import static org.assertj.core.api.Assertions.assertThat;

import com.jsgalactic.axiom.Axiom;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

/** A path capture that cannot be decoded is the client's fault: 400, as over a listener. */
class TestClientPathDecodingTest {
    @Test
    void undecodableCapturesAnswer400WithoutEchoingTheInput() throws Exception {
        var app = Axiom.create();
        app.get("/users/:id", ctx -> "user " + ctx.pathDecoded("id"));
        try (var client = TestClient.start(app)) {
            assertThat(client.get("/users/J%C3%BCrgen").body()).isEqualTo("user Jürgen");
            for (var raw : new String[] {"%FF", "%C3%28", "%ED%A0%80"}) {
                var response = client.get("/users/" + raw);
                assertThat(response.status()).as(raw).isEqualTo(400);
                assertThat(response.headers()).containsEntry("Content-Type", "application/problem+json");
                var body = new String((byte[]) response.body(), StandardCharsets.UTF_8);
                assertThat(body).contains("\"code\":\"invalid_path_encoding\"").doesNotContain(raw);
            }
        }
    }
}
