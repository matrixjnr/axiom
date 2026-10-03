package com.jsgalactic.axiom.test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.jsgalactic.axiom.Axiom;
import com.jsgalactic.axiom.http.InvalidRequestPathException;
import com.jsgalactic.axiom.http.Request;
import org.junit.jupiter.api.Test;

class TestClientQueryTest {
    @Test
    void sendsQueriesToHandlersWithEveryMethod() throws Exception {
        var app = Axiom.create();
        app.get("/search", ctx -> ctx.query("q").orElse("none") + "|" + ctx.queryAll("tag") + "|" + ctx.path());
        app.post("/items/:id", ctx -> ctx.path("id") + "|" + ctx.query("dry").orElse("no") + "|"
                + new String(ctx.request().body().bytes(), java.nio.charset.StandardCharsets.UTF_8));
        app.put("/items/:id", ctx -> ctx.query("v").orElseThrow());
        app.patch("/items/:id", ctx -> ctx.query("v").orElseThrow());
        try (var client = TestClient.start(app)) {
            assertThat(client.get("/search?q=a+b&tag=x&tag=%E2%82%AC").body()).isEqualTo("a b|[x, €]|/search");
            assertThat(client.get("/search").body()).isEqualTo("none|[]|/search");
            assertThat(client.post("/items/7?dry", "text/plain", "body").body()).isEqualTo("7||body");
            assertThat(client.put("/items/7?v=1", "text/plain", new byte[0]).body()).isEqualTo("1");
            assertThat(client.patch("/items/7?v=2", null, "").body()).isEqualTo("2");
            assertThat(client.get("/missing?q=1").status()).isEqualTo(404);
        }
    }

    @Test
    void appliesTheListenersTargetValidation() throws Exception {
        var app = Axiom.create();
        app.get("/search", ctx -> "never");
        try (var client = TestClient.start(app)) {
            for (var invalid : new String[] {"/search?q=%zz", "/search?q=%FF", "/search?q=a b", "/search?q=#x"}) {
                assertThatIllegalArgumentException().as(invalid).isThrownBy(() -> client.get(invalid))
                        .satisfies(failure -> assertThat(failure.getMessage()).doesNotContain(invalid.substring(8)));
            }
            assertThatIllegalArgumentException().isThrownBy(() -> client.post("/search?q=%", "text/plain", "x"));
            assertThatIllegalArgumentException().isThrownBy(
                    () -> client.get("/search?" + "x".repeat(Request.MAX_QUERY_LENGTH + 1)));
            assertThatThrownBy(() -> client.get("/a/../search?q=1")).isInstanceOf(InvalidRequestPathException.class);
        }
    }
}
