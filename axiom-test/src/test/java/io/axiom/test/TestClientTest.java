package io.axiom.test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

import io.axiom.Axiom;
import io.axiom.application.Application;
import io.axiom.http.Request;
import org.junit.jupiter.api.Test;

class TestClientTest {
    @Test
    void usesRealDispatchAndOwnsTheApplicationLifecycle() throws Exception {
        var app = Axiom.create();
        app.get("/", ctx -> "Hello, world!");
        app.post("/created", ctx -> ctx.status(201).text("created"));
        try (var client = TestClient.start(app)) {
            assertThat(client.get("/").body()).isEqualTo("Hello, world!");
            assertThat(client.get("/missing").status()).isEqualTo(404);
            assertThat(client.execute(new Request("POST", "/created")).status()).isEqualTo(201);
            assertThatIllegalStateException().isThrownBy(() -> app.get("/late", ctx -> "late"));
        }
        assertThat(app.state()).isEqualTo(Application.State.CLOSED);
    }
}
