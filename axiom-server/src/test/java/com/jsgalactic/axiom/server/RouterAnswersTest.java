package com.jsgalactic.axiom.server;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.jsgalactic.axiom.Axiom;
import com.jsgalactic.axiom.error.ConflictException;
import com.jsgalactic.axiom.http.Request;
import com.jsgalactic.axiom.http.Response;
import java.util.ArrayList;
import org.junit.jupiter.api.Test;

/** Customising the answers the router produces itself (404, 405, 501) with dedicated handlers. */
class RouterAnswersTest {
    @Test
    void notFoundHandlerAnswersUnroutedPathsWithItsOwnBodyAndTheStatusPreset() throws Exception {
        try (var app = Axiom.create()) {
            app.get("/known", ctx -> "known");
            app.notFound(ctx -> ctx.text("nothing at " + ctx.path() + " " + ctx.matchedRoute().isPresent()));
            app.start();
            var missing = app.handle(Request.get("/missing"));
            assertThat(missing.status()).isEqualTo(404);
            assertThat(missing.body()).isEqualTo("nothing at /missing false");
            // Routed paths and every other answer are unchanged.
            assertThat(app.handle(Request.get("/known")).body()).isEqualTo("known");
            assertThat(app.handle(new Request("POST", "/known")).headers().get("Content-Type"))
                    .isEqualTo("application/problem+json");
            assertThat(app.handle(new Request("FOO", "/missing")).headers().get("Content-Type"))
                    .isEqualTo("application/problem+json");
        }
    }

    @Test
    void aHandlerMayReturnAResponseOrNullAndHeadSuppressesTheBody() throws Exception {
        try (var app = Axiom.create()) {
            app.notFound(ctx -> ctx.path().equals("/gone") ? Response.of(410, "gone") : null);
            app.start();
            var empty = app.handle(Request.get("/other"));
            assertThat(empty.status()).isEqualTo(404);
            assertThat(empty.body()).isNull();
            assertThat(app.handle(Request.get("/gone")).status()).isEqualTo(410);
            var head = app.handle(new Request("HEAD", "/gone"));
            assertThat(head.status()).isEqualTo(410);
            assertThat(head.body()).isNull();
        }
    }

    @Test
    void methodNotAllowedHandlerAlwaysKeepsTheRoutersAllowList() throws Exception {
        try (var app = Axiom.create()) {
            app.get("/items", ctx -> "list");
            app.post("/items", ctx -> "create");
            app.methodNotAllowed(ctx -> ctx.path().equals("/items")
                    ? ctx.text("use another method").withHeader("Allow", "GET")
                    : Response.of(418, "teapot"));
            app.start();
            var mismatch = app.handle(new Request("DELETE", "/items"));
            assertThat(mismatch.status()).isEqualTo(405);
            assertThat(mismatch.body()).isEqualTo("use another method");
            assertThat(mismatch.headers()).containsEntry("Allow", "GET, HEAD, OPTIONS, POST");
        }
        // Even with another status chosen by the handler, Allow is present.
        try (var app = Axiom.create()) {
            app.put("/other", ctx -> "put");
            app.methodNotAllowed(ctx -> Response.of(418, "teapot"));
            app.start();
            var teapot = app.handle(new Request("DELETE", "/other"));
            assertThat(teapot.status()).isEqualTo(418);
            assertThat(teapot.headers()).containsEntry("Allow", "OPTIONS, PUT");
            // OPTIONS is answered automatically and never reaches the 405 handler.
            assertThat(app.handle(new Request("OPTIONS", "/other")).status()).isEqualTo(204);
        }
    }

    @Test
    void notImplementedHandlerAnswersUnrecognizedMethodsOnlyAndDeclaredMethodsAreNotFound() throws Exception {
        try (var app = Axiom.create()) {
            app.get("/known", ctx -> "known");
            app.recognizeMethods("MKCOL");
            app.notImplemented(ctx -> ctx.text("no " + ctx.method()));
            app.notFound(ctx -> ctx.text("missing"));
            app.start();
            var unknown = app.handle(new Request("FOO", "/missing"));
            assertThat(unknown.status()).isEqualTo(501);
            assertThat(unknown.body()).isEqualTo("no FOO");
            assertThat(app.handle(new Request("MKCOL", "/missing")).body()).isEqualTo("missing");
            // CONNECT is refused before routing and cannot be customised.
            var connect = app.handle(new Request("CONNECT", "/missing"));
            assertThat(connect.status()).isEqualTo(501);
            assertThat(connect.headers().get("Content-Type")).isEqualTo("application/problem+json");
        }
    }

    @Test
    void globalMiddlewareWrapsTheHandlersAndGroupMiddlewareDoesNot() throws Exception {
        var seen = new ArrayList<String>();
        try (var app = Axiom.create()) {
            app.use((ctx, next) -> {
                seen.add("before " + ctx.matchedRoute().isPresent());
                return next.run().withHeader("X-Wrapped", "yes");
            });
            app.group("/api", api -> {
                api.use((ctx, next) -> { seen.add("group"); return next.run(); });
                api.get("/x", ctx -> "x");
            });
            app.notFound(ctx -> { seen.add("handler"); return ctx.text("custom"); });
            app.start();
            var response = app.handle(Request.get("/api/missing"));
            assertThat(response.body()).isEqualTo("custom");
            assertThat(response.headers()).containsEntry("X-Wrapped", "yes");
            assertThat(seen).containsExactly("before false", "handler");
        }
    }

    @Test
    void exceptionsFromAHandlerAreMappedLikeAnyOther() throws Exception {
        try (var app = Axiom.create()) {
            app.get("/a", ctx -> "a");
            app.notFound(ctx -> { throw new ConflictException("custom_missing"); });
            app.methodNotAllowed(ctx -> { throw new IllegalStateException("boom"); });
            app.error(IllegalStateException.class, (ctx, failure) -> ctx.status(500).text("mapped"));
            app.start();
            var missing = app.handle(Request.get("/b"));
            assertThat(missing.status()).isEqualTo(409);
            assertThat(new String((byte[]) missing.body())).contains("\"code\":\"custom_missing\"");
            var mapped = app.handle(new Request("POST", "/a"));
            assertThat(mapped.status()).isEqualTo(500);
            assertThat(mapped.body()).isEqualTo("mapped");
        }
        try (var app = Axiom.create()) {
            app.get("/a", ctx -> "a");
            app.notImplemented(ctx -> { throw new IllegalStateException("boom"); });
            app.start();
            assertThatThrownBy(() -> app.handle(new Request("FOO", "/b"))).isInstanceOf(IllegalStateException.class);
        }
    }

    @Test
    void handlersAreConfiguredBeforeStartupWithNonNullValuesAndTheLastCallWins() throws Exception {
        try (var app = Axiom.create()) {
            assertThatThrownBy(() -> app.notFound(null)).isInstanceOf(NullPointerException.class);
            assertThatThrownBy(() -> app.methodNotAllowed(null)).isInstanceOf(NullPointerException.class);
            assertThatThrownBy(() -> app.notImplemented(null)).isInstanceOf(NullPointerException.class);
            app.notFound(ctx -> "first");
            app.notFound(ctx -> "second");
            app.start();
            assertThat(app.handle(Request.get("/missing")).body()).isEqualTo("second");
            assertThatThrownBy(() -> app.notFound(ctx -> "late")).isInstanceOf(IllegalStateException.class);
            assertThatThrownBy(() -> app.methodNotAllowed(ctx -> "late")).isInstanceOf(IllegalStateException.class);
            assertThatThrownBy(() -> app.notImplemented(ctx -> "late")).isInstanceOf(IllegalStateException.class);
        }
    }

    @Test
    void jsonBodiesCarryThePresetStatus() throws Exception {
        try (var app = Axiom.create()) {
            app.notFound(ctx -> ctx.json("{\"error\":\"not here\"}"));
            app.start();
            var response = app.handle(Request.get("/missing"));
            assertThat(response.status()).isEqualTo(404);
            assertThat(response.headers()).containsEntry("Content-Type", "application/json");
            assertThat(response.body()).isEqualTo("{\"error\":\"not here\"}");
        }
    }
}
