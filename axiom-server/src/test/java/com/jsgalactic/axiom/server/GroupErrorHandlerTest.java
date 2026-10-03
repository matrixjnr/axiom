package com.jsgalactic.axiom.server;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.jsgalactic.axiom.Axiom;
import com.jsgalactic.axiom.error.AxiomException;
import com.jsgalactic.axiom.error.ConflictException;
import com.jsgalactic.axiom.error.NotFoundException;
import com.jsgalactic.axiom.error.TooManyRequestsException;
import com.jsgalactic.axiom.error.ValidationException;
import com.jsgalactic.axiom.error.Violation;
import com.jsgalactic.axiom.execution.ExecutionContext;
import com.jsgalactic.axiom.http.Request;
import com.jsgalactic.axiom.http.Response;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Group-scoped error handlers and the public problem-response helper. */
class GroupErrorHandlerTest {
    private static String text(Response response) {
        return response.body() instanceof byte[] bytes ? new String(bytes, StandardCharsets.UTF_8)
                : String.valueOf(response.body());
    }

    @Test
    void theInnermostScopeWinsEvenWithAFartherExceptionClass() throws Exception {
        try (var app = Axiom.create()) {
            app.error(IllegalStateException.class, (ctx, failure) -> Response.of(500, "app"));
            app.group("/api", api -> {
                api.error(IllegalStateException.class, (ctx, failure) -> Response.of(500, "api"));
                api.group("/v1", v1 -> {
                    v1.error(RuntimeException.class, (ctx, failure) -> Response.of(500, "v1 runtime"));
                    v1.get("/fail", ctx -> { throw new IllegalStateException(); });
                });
                api.get("/fail", ctx -> { throw new IllegalStateException(); });
            });
            app.get("/fail", ctx -> { throw new IllegalStateException(); });
            app.get("/other", ctx -> { throw new ArithmeticException(); });
            app.error(ArithmeticException.class, (ctx, failure) -> Response.of(500, "app arithmetic"));
            app.start();
            // v1's handler is for a superclass, yet it beats the exact class registered further out.
            assertThat(app.handle(Request.get("/api/v1/fail")).body()).isEqualTo("v1 runtime");
            assertThat(app.handle(Request.get("/api/fail")).body()).isEqualTo("api");
            assertThat(app.handle(Request.get("/fail")).body()).isEqualTo("app");
            assertThat(app.handle(Request.get("/other")).body()).isEqualTo("app arithmetic");
        }
    }

    @Test
    void fallsBackToOuterScopesAndNeverLeaksToSiblings() throws Exception {
        try (var app = Axiom.create()) {
            app.error(ArithmeticException.class, (ctx, failure) -> Response.of(500, "app"));
            app.group("/a", a -> {
                a.error(IllegalStateException.class, (ctx, failure) -> Response.of(409, "a"));
                a.get("/arith", ctx -> { throw new ArithmeticException(); });
                a.get("/state", ctx -> { throw new IllegalStateException(); });
            });
            app.group("/b", b -> b.get("/state", ctx -> { throw new IllegalStateException(); }));
            app.start();
            assertThat(app.handle(Request.get("/a/arith")).body()).isEqualTo("app");
            assertThat(app.handle(Request.get("/a/state")).body()).isEqualTo("a");
            assertThatThrownBy(() -> app.handle(Request.get("/b/state"))).isInstanceOf(IllegalStateException.class);
        }
    }

    @Test
    void appliesToExceptionsOfGlobalMiddlewareForTheGroupsRoutesOnly() throws Exception {
        try (var app = Axiom.create()) {
            app.use((ctx, next) -> {
                if (ctx.header("X-Fail").isPresent()) { throw new IllegalStateException(); }
                return next.run();
            });
            app.group("/g", g -> {
                g.error(IllegalStateException.class, (ctx, failure) -> Response.of(409, "group"));
                g.get("/x", ctx -> "x");
            });
            app.get("/plain", ctx -> "plain");
            app.error(IllegalStateException.class, (ctx, failure) -> Response.of(503, "app"));
            app.start();
            var fail = java.util.Map.of("X-Fail", "1");
            assertThat(app.handle(Request.get("/g/x").withHeaders(fail)).body()).isEqualTo("group");
            assertThat(app.handle(Request.get("/plain").withHeaders(fail)).body()).isEqualTo("app");
            // No route serves it: only the application's handlers apply.
            assertThat(app.handle(Request.get("/g/missing").withHeaders(fail)).body()).isEqualTo("app");
        }
    }

    @Test
    void axiomExceptionsKeepTheirProblemResponseUnlessAGroupMapsThem() throws Exception {
        try (var app = Axiom.create()) {
            app.group("/g", g -> {
                g.error(Exception.class, (ctx, failure) -> Response.of(500, "generic"));
                g.error(ConflictException.class, (ctx, failure) -> Response.of(409, "custom conflict"));
                g.get("/missing", ctx -> { throw new NotFoundException(); });
                g.get("/conflict", ctx -> { throw new ConflictException(); });
            });
            app.get("/conflict", ctx -> { throw new ConflictException(); });
            app.start();
            assertThat(app.handle(Request.get("/g/missing")).headers())
                    .containsEntry("Content-Type", "application/problem+json");
            assertThat(app.handle(Request.get("/g/conflict")).body()).isEqualTo("custom conflict");
            assertThat(app.handle(Request.get("/conflict")).headers())
                    .containsEntry("Content-Type", "application/problem+json");
        }
    }

    @Test
    void aGroupThatFailsToConfigureTakesItsHandlersWithIt() throws Exception {
        try (var app = Axiom.create()) {
            assertThatThrownBy(() -> app.group("/api", api -> {
                api.error(IllegalStateException.class, (ctx, failure) -> Response.of(409, "partial"));
                api.get("/fail", ctx -> { throw new IllegalStateException(); });
                throw new UnsupportedOperationException("configuration failed");
            })).isInstanceOf(UnsupportedOperationException.class);
            app.group("/api", api -> api.get("/fail", ctx -> { throw new IllegalStateException(); }));
            app.start();
            assertThat(app.routes()).hasSize(1);
            assertThatThrownBy(() -> app.handle(Request.get("/api/fail"))).isInstanceOf(IllegalStateException.class);
        }
    }

    @Test
    void registrationFollowsTheRulesOfRoutes() throws Exception {
        var leaked = new java.util.concurrent.atomic.AtomicReference<com.jsgalactic.axiom.routing.RouteGroup>();
        try (var app = Axiom.create()) {
            app.group("/g", g -> {
                leaked.set(g);
                g.error(IllegalStateException.class, (ctx, failure) -> Response.of(409, "g"));
                assertThatIllegalArgumentException()
                        .isThrownBy(() -> g.error(IllegalStateException.class, (ctx, failure) -> Response.of(409, "again")));
                // The same class in a nested group is a different scope.
                g.group("/n", n -> n.error(IllegalStateException.class, (ctx, failure) -> Response.of(409, "n")));
                assertThatNullPointerException().isThrownBy(() -> g.error(null, (ctx, failure) -> null));
                assertThatNullPointerException().isThrownBy(() -> g.error(Exception.class, null));
            });
            assertThatIllegalStateException()
                    .isThrownBy(() -> leaked.get().error(Exception.class, (ctx, failure) -> null));
            app.start();
            assertThatIllegalStateException()
                    .isThrownBy(() -> app.error(Exception.class, (ctx, failure) -> null));
        }
    }

    @Test
    void problemBuildsTheStandardResponseSoHandlersCanDecorateIt() throws Exception {
        try (var app = Axiom.create()) {
            app.get("/invalid", ctx -> { throw new ValidationException(List.of(new Violation("title", "required"))); });
            app.get("/limited", ctx -> { throw new TooManyRequestsException(Duration.ofSeconds(30)); });
            app.get("/state", ctx -> { throw new IllegalStateException("POISON"); });
            app.error(AxiomException.class, (ctx, failure) -> ctx.problem(failure).withHeader("Cache-Control", "no-store"));
            app.error(IllegalStateException.class, (ctx, failure) -> ctx.status(201).problem(new ConflictException("busy")));
            app.start();
            var execution = ExecutionContext.create(Duration.ofSeconds(10));
            var invalid = app.handle(Request.get("/invalid"), execution);
            assertThat(invalid.status()).isEqualTo(422);
            assertThat(invalid.headers()).containsEntry("Cache-Control", "no-store")
                    .containsEntry("Content-Type", "application/problem+json");
            assertThat(text(invalid)).isEqualTo("{\"status\":422,\"code\":\"validation_failed\",\"requestId\":\""
                    + execution.requestId() + "\",\"violations\":[{\"field\":\"title\",\"code\":\"required\"}]}");
            var limited = app.handle(Request.get("/limited"));
            assertThat(limited.status()).isEqualTo(429);
            assertThat(limited.headers()).containsEntry("Retry-After", "30").containsEntry("Cache-Control", "no-store");
            // Independent of the context's status, and usable from a handler for a non-Axiom exception.
            var state = app.handle(Request.get("/state"));
            assertThat(state.status()).isEqualTo(409);
            assertThat(text(state)).contains("\"code\":\"busy\"").doesNotContain("POISON");
        }
    }

    @Test
    void problemRejectsNull() throws Exception {
        try (var app = Axiom.create()) {
            app.get("/null", ctx -> ctx.problem(null));
            app.start();
            assertThatNullPointerException().isThrownBy(() -> app.handle(Request.get("/null")));
        }
    }
}
