package com.jsgalactic.axiom.server;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.jsgalactic.axiom.Axiom;
import com.jsgalactic.axiom.context.Context;
import com.jsgalactic.axiom.context.Middleware;
import com.jsgalactic.axiom.error.ConflictException;
import com.jsgalactic.axiom.error.ForbiddenException;
import com.jsgalactic.axiom.error.InternalServerErrorException;
import com.jsgalactic.axiom.error.NotFoundException;
import com.jsgalactic.axiom.http.Request;
import com.jsgalactic.axiom.http.Response;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Middleware decorate responses that were produced from exceptions through {@code afterError}. */
class ErrorDecorationTest {
    private final List<String> calls = Collections.synchronizedList(new ArrayList<>());

    /** A middleware that adds one header to every response, successful or not. */
    private static Middleware everywhere(String name, String value) {
        return new Middleware() {
            @Override public Response handle(Context ctx, Next next) throws Exception {
                return next.run().withHeader(name, value);
            }
            @Override public Response afterError(Context ctx, Response response) {
                return response.withHeader(name, value);
            }
        };
    }

    private Middleware tracing(String name) {
        return new Middleware() {
            @Override public Response handle(Context ctx, Next next) throws Exception { return next.run(); }
            @Override public Response afterError(Context ctx, Response response) {
                calls.add(name + ":" + response.status());
                return response;
            }
        };
    }

    @Test
    void decoratesProblemErrorHandlerAndGenericResponsesOfEveryStatusClass() throws Exception {
        record Item(String name) { }
        try (var app = Axiom.create()) {
            app.use(everywhere("X-Content-Type-Options", "nosniff"));
            app.get("/missing", ctx -> { throw new NotFoundException(); });
            app.get("/broken", ctx -> { throw new InternalServerErrorException(); });
            app.get("/mapped", ctx -> { throw new IllegalStateException(); });
            app.get("/failing", ctx -> { throw new IllegalArgumentException(); });
            app.get("/item", ctx -> ctx.json(new Item("pen")));
            app.error(IllegalStateException.class, (ctx, failure) -> ctx.status(409).text("mapped"));
            app.error(IllegalArgumentException.class, (ctx, failure) -> { throw new IllegalStateException(); });
            app.start();
            for (var path : List.of("/missing", "/broken", "/mapped", "/failing")) {
                var response = app.handle(Request.get(path));
                assertThat(response.status()).as(path).isGreaterThanOrEqualTo(400);
                assertThat(response.headers()).as(path).containsEntry("X-Content-Type-Options", "nosniff");
            }
            assertThat(app.handle(Request.get("/mapped")).status()).isEqualTo(409);
            var refused = app.handle(Request.get("/item").withHeaders(Map.of("Accept", "text/plain")));
            assertThat(refused.status()).isEqualTo(406);
            assertThat(refused.headers()).containsEntry("X-Content-Type-Options", "nosniff");
        }
    }

    @Test
    void decoratesTheGenericFiveHundredAfterAFailingErrorHandler() throws Exception {
        try (var app = Axiom.create()) {
            app.use(everywhere("X-Frame-Options", "DENY"));
            app.get("/fail", ctx -> { throw new IllegalArgumentException(); });
            app.error(IllegalArgumentException.class, (ctx, failure) -> null);
            app.start();
            var response = app.handle(Request.get("/fail"));
            assertThat(response.status()).isEqualTo(500);
            assertThat(response.headers()).containsEntry("X-Frame-Options", "DENY")
                    .containsEntry("Content-Type", "application/problem+json");
        }
    }

    @Test
    void runsInnermostFirstAcrossGlobalGroupAndRouteMiddleware() throws Exception {
        try (var app = Axiom.create()) {
            app.use(tracing("global"));
            app.group("/api", api -> {
                api.use(tracing("group"));
                api.get("/fail", ctx -> { throw new ConflictException(); }, tracing("route"));
            });
            app.start();
            assertThat(app.handle(Request.get("/api/fail")).status()).isEqualTo(409);
            assertThat(calls).containsExactly("route:409", "group:409", "global:409");
        }
    }

    @Test
    void skipsMiddlewareThatWereNeverEntered() throws Exception {
        try (var app = Axiom.create()) {
            app.use(tracing("global"));
            app.use((ctx, next) -> {
                if (ctx.header("X-Deny").isPresent()) { throw new ForbiddenException(); }
                return next.run();
            });
            app.group("/api", api -> {
                api.use(tracing("group"));
                api.get("/ok", ctx -> { throw new ConflictException(); });
            });
            app.start();
            assertThat(app.handle(Request.get("/api/ok").withHeaders(Map.of("X-Deny", "1"))).status()).isEqualTo(403);
            assertThat(calls).containsExactly("global:403");
            calls.clear();
            assertThat(app.handle(Request.get("/api/ok")).status()).isEqualTo(409);
            assertThat(calls).containsExactly("group:409", "global:409");
        }
    }

    @Test
    void followsTheStackWhenAMiddlewareCatchesAndThrowsAnotherException() throws Exception {
        try (var app = Axiom.create()) {
            app.use(tracing("outer"));
            app.use((ctx, next) -> {
                try {
                    return next.run();
                } catch (ConflictException recovered) {
                    return Response.of(202, "recovered");
                }
            });
            app.use(tracing("inner"));
            app.get("/recovered", ctx -> { throw new ConflictException(); });
            app.start();
            assertThat(app.handle(Request.get("/recovered")).status()).isEqualTo(202);
            assertThat(calls).isEmpty();
        }
        try (var app = Axiom.create()) {
            app.use(tracing("outer"));
            app.use((ctx, next) -> {
                try {
                    next.run();
                } catch (ConflictException recovered) {
                    // Recovered, then fails differently, from this middleware's own frame.
                }
                throw new ForbiddenException();
            });
            app.use(tracing("inner"));
            app.get("/rethrown", ctx -> { throw new ConflictException(); });
            app.start();
            assertThat(app.handle(Request.get("/rethrown")).status()).isEqualTo(403);
            assertThat(calls).containsExactly("outer:403");
        }
        calls.clear();
        try (var app = Axiom.create()) {
            app.use(tracing("outer"));
            app.use((ctx, next) -> {
                try {
                    return next.run();
                } catch (ConflictException translated) {
                    throw new ForbiddenException();
                }
            });
            app.use(tracing("inner"));
            app.get("/translated", ctx -> { throw new ConflictException(); });
            app.start();
            assertThat(app.handle(Request.get("/translated")).status()).isEqualTo(403);
            // The translating middleware was entered, the inner one was only passed through by the original.
            assertThat(calls).containsExactly("outer:403");
        }
    }

    @Test
    void isNotCalledForSuccessesRouterAnswersOrResponsesTheChainBuiltItself() throws Exception {
        try (var app = Axiom.create()) {
            app.use(tracing("global"));
            app.get("/ok", ctx -> "fine");
            app.get("/built", ctx -> Response.of(500, "built here"));
            app.start();
            assertThat(app.handle(Request.get("/ok")).status()).isEqualTo(200);
            assertThat(app.handle(Request.get("/built")).status()).isEqualTo(500);
            assertThat(app.handle(Request.get("/nothing")).status()).isEqualTo(404);
            assertThat(app.handle(new Request("DELETE", "/ok")).status()).isEqualTo(405);
            assertThat(calls).isEmpty();
        }
    }

    @Test
    void decoratesExceptionsOfCustomRouterAnswerHandlersWithGlobalMiddleware() throws Exception {
        try (var app = Axiom.create()) {
            app.use(everywhere("X-Frame-Options", "DENY"));
            app.notFound(ctx -> { throw new ConflictException(); });
            app.start();
            var response = app.handle(Request.get("/nothing"));
            assertThat(response.status()).isEqualTo(409);
            assertThat(response.headers()).containsEntry("X-Frame-Options", "DENY");
        }
    }

    @Test
    void doesNotDecorateUnmappedExceptionsOrFailuresBeforeRouting() throws Exception {
        try (var app = Axiom.create()) {
            app.use(tracing("global"));
            app.maxRequestBody(2);
            app.post("/boom", ctx -> { throw new UnsupportedOperationException(); });
            app.start();
            assertThatThrownBy(() -> app.handle(new Request("POST", "/boom")))
                    .isInstanceOf(UnsupportedOperationException.class);
            var big = new Request("POST", "/boom").withBody(com.jsgalactic.axiom.http.Body.of("text/plain", new byte[10]));
            assertThat(app.handle(big).status()).isEqualTo(413);
            assertThat(calls).isEmpty();
        }
    }

    @Test
    void anExceptionFromAfterErrorIsNotMappedAgain() throws Exception {
        try (var app = Axiom.create()) {
            app.use(new Middleware() {
                @Override public Response handle(Context ctx, Next next) throws Exception { return next.run(); }
                @Override public Response afterError(Context ctx, Response response) {
                    if (ctx.path().equals("/translate")) { throw new ForbiddenException(); }
                    if (ctx.path().equals("/null")) { return null; }
                    throw new UnsupportedOperationException();
                }
            });
            app.error(Exception.class, (ctx, failure) -> Response.of(418, "teapot"));
            app.get("/translate", ctx -> { throw new ConflictException(); });
            app.get("/null", ctx -> { throw new ConflictException(); });
            app.get("/bug", ctx -> { throw new ConflictException(); });
            app.start();
            var translated = app.handle(Request.get("/translate"));
            assertThat(translated.status()).isEqualTo(403);
            assertThat(translated.headers()).containsEntry("Content-Type", "application/problem+json");
            assertThatThrownBy(() -> app.handle(Request.get("/null")))
                    .isInstanceOf(IllegalStateException.class).hasMessageContaining("afterError");
            assertThatThrownBy(() -> app.handle(Request.get("/bug")))
                    .isInstanceOf(UnsupportedOperationException.class);
        }
    }
}
