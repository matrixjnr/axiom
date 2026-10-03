package io.axiom.http.internal;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** Malformed UTF-8 in a path capture is answered 400 over a socket, not 500. */
@Tag("integration")
class HttpPathDecodingTest {
    @Test void undecodableCapturesAnswer400AndKeepTheConnection() throws Exception {
        try (var fixture = new Fixture()) {
            fixture.app.get("/users/:id", ctx -> "user " + ctx.pathDecoded("id"));
            fixture.app.get("/files/*path", ctx -> "file " + ctx.pathDecoded("path"));
            try (var wire = new Wire(fixture.listen())) {
                for (var path : new String[] {"/users/%FF", "/users/%C3%28", "/files/a/%FF/b"}) {
                    var reply = wire.get(path);
                    assertThat(reply.status()).as(path).isEqualTo(400);
                    assertThat(reply.headers()).containsEntry("Content-Type", "application/problem+json");
                    assertThat(reply.text()).contains("\"code\":\"invalid_path_encoding\"")
                            .doesNotContain("%FF").doesNotContain("%C3");
                    // An application error, not a framing error: the connection stays open.
                    assertThat(reply.headers().get("Connection")).isNotEqualToIgnoringCase("close");
                }
                assertThat(wire.get("/users/%E2%82%AC").text()).isEqualTo("user €");
            }
        }
    }
}
