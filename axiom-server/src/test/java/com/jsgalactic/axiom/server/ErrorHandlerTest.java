package com.jsgalactic.axiom.server;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.jsgalactic.axiom.Axiom;
import com.jsgalactic.axiom.application.Application;
import com.jsgalactic.axiom.error.AxiomException;
import com.jsgalactic.axiom.error.ConflictException;
import com.jsgalactic.axiom.error.NotFoundException;
import com.jsgalactic.axiom.error.ValidationException;
import com.jsgalactic.axiom.error.Violation;
import com.jsgalactic.axiom.http.Request;
import com.jsgalactic.axiom.http.Response;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import org.junit.jupiter.api.Test;

class ErrorHandlerTest {
    private static final String POISON = "POISON<script>alert(1)</script> /etc/passwd";
    private final List<String> calls = new ArrayList<>();

    private static String text(Response response) {
        return response.body() instanceof byte[] bytes ? new String(bytes, StandardCharsets.UTF_8)
                : String.valueOf(response.body());
    }

    private static Application throwing(Exception failure) {
        var app = Axiom.create();
        app.get("/fail", ctx -> { throw failure; });
        return app;
    }

    @Test
    void mapsRegisteredExceptionsToTheirResponses() throws Exception {
        try (var app = throwing(new NoSuchElementException(POISON))) {
            app.error(NoSuchElementException.class, (ctx, failure) -> ctx.status(404).text("missing")
                    .withHeader("X-Mapped", "1"));
            app.start();
            var response = app.handle(Request.get("/fail"));
            assertThat(response.status()).isEqualTo(404);
            assertThat(response.body()).isEqualTo("missing");
            assertThat(response.headers()).containsEntry("X-Mapped", "1");
        }
    }

    @Test
    void theNearestRegisteredSuperclassWins() throws Exception {
        try (var app = Axiom.create()) {
            app.get("/number", ctx -> Integer.parseInt("x"));
            app.get("/state", ctx -> { throw new IllegalStateException(); });
            app.get("/checked", ctx -> { throw new java.io.IOException(); });
            app.error(Exception.class, (ctx, failure) -> Response.of(500, "exception"));
            app.error(RuntimeException.class, (ctx, failure) -> Response.of(500, "runtime"));
            app.error(IllegalArgumentException.class, (ctx, failure) -> Response.of(400, "argument"));
            app.start();
            assertThat(app.handle(Request.get("/number")).body()).isEqualTo("argument");
            assertThat(app.handle(Request.get("/state")).body()).isEqualTo("runtime");
            assertThat(app.handle(Request.get("/checked")).body()).isEqualTo("exception");
        }
    }

    @Test
    void axiomExceptionsKeepTheirProblemResponseUnlessOverridden() throws Exception {
        try (var app = Axiom.create()) {
            app.get("/missing", ctx -> { throw new NotFoundException("note_not_found"); });
            app.get("/invalid", ctx -> { throw new ValidationException(List.of(new Violation("title", "required"))); });
            app.get("/conflict", ctx -> { throw new ConflictException(); });
            app.error(Exception.class, (ctx, failure) -> Response.of(500, "generic mapper"));
            app.error(ConflictException.class, (ctx, failure) -> Response.of(409, "custom conflict"));
            app.start();
            var missing = app.handle(Request.get("/missing"));
            assertThat(missing.status()).isEqualTo(404);
            assertThat(missing.headers()).containsEntry("Content-Type", "application/problem+json");
            assertThat(text(missing)).contains("\"code\":\"note_not_found\"");
            assertThat(text(app.handle(Request.get("/invalid")))).contains("\"violations\"");
            assertThat(app.handle(Request.get("/conflict")).body()).isEqualTo("custom conflict");
        }
        try (var app = throwing(new NotFoundException())) {
            app.error(AxiomException.class, (ctx, failure) -> Response.of(failure.status(), "every axiom error"));
            app.start();
            assertThat(app.handle(Request.get("/fail")).body()).isEqualTo("every axiom error");
        }
    }

    @Test
    void runsAfterMiddlewareHaveUnwoundAndCoversMiddlewareFailures() throws Exception {
        try (var app = Axiom.create()) {
            app.use((ctx, next) -> {
                calls.add("global>");
                try {
                    return next.run();
                } catch (IllegalStateException observed) {
                    calls.add("observed");
                    throw observed;
                } finally {
                    calls.add("<global");
                }
            });
            app.get("/handler", ctx -> { throw new IllegalStateException(POISON); });
            app.get("/middleware", ctx -> "unreachable", (ctx, next) -> { throw new IllegalStateException(POISON); });
            app.error(IllegalStateException.class, (ctx, failure) -> {
                calls.add("error");
                return Response.of(409, "mapped");
            });
            app.start();
            assertThat(app.handle(Request.get("/handler")).status()).isEqualTo(409);
            assertThat(calls).containsExactly("global>", "observed", "<global", "error");
            calls.clear();
            assertThat(app.handle(Request.get("/middleware")).status()).isEqualTo(409);
            assertThat(calls).containsExactly("global>", "observed", "<global", "error");
        }
    }

    @Test
    void errorHandlersSeeAFreshStatusAndTheirResponsesAreEncodedWithoutNegotiation() throws Exception {
        record Problem(String reason) { }
        try (var app = throwing(new IllegalStateException())) {
            app.get("/created-then-failed", ctx -> {
                ctx.status(201);
                throw new IllegalStateException();
            });
            app.error(IllegalStateException.class, (ctx, failure) -> ctx.path().equals("/fail")
                    ? ctx.status(409).json(new Problem("busy")) : ctx.text("plain"));
            app.start();
            var json = app.handle(Request.get("/fail").withHeaders(Map.of("Accept", "text/plain")));
            assertThat(json.status()).isEqualTo(409);
            assertThat(json.headers()).containsEntry("Content-Type", "application/json");
            assertThat(text(json)).isEqualTo("reason=busy");
            assertThat(app.handle(Request.get("/created-then-failed")).status()).isEqualTo(200);
        }
    }

    @Test
    void failuresAfterTheContextStatusChangedIgnoreThatStatus() throws Exception {
        try (var app = Axiom.create()) {
            app.get("/builtin", ctx -> {
                ctx.status(201);
                throw new NotFoundException();
            });
            app.get("/mapped", ctx -> {
                ctx.status(204);
                throw new IllegalStateException();
            }, (ctx, next) -> {
                ctx.status(202);
                return next.run();
            });
            app.get("/unmapped", ctx -> {
                ctx.status(204);
                throw new UnsupportedOperationException();
            });
            app.error(IllegalStateException.class, (ctx, failure) -> ctx.text("recovered"));
            app.start();
            var builtin = app.handle(Request.get("/builtin"));
            assertThat(builtin.status()).isEqualTo(404);
            assertThat(text(builtin)).contains("\"status\":404");
            var mapped = app.handle(Request.get("/mapped"));
            assertThat(mapped.status()).isEqualTo(200);
            assertThat(mapped.body()).isEqualTo("recovered");
            assertThatThrownBy(() -> app.handle(Request.get("/unmapped")))
                    .isInstanceOf(UnsupportedOperationException.class);
        }
    }

    @Test
    void anAxiomExceptionThrownByAnErrorHandlerIsItsAnswerAndIsNotHandledAgain() throws Exception {
        try (var app = throwing(new NoSuchElementException(POISON))) {
            app.error(NoSuchElementException.class, (ctx, failure) -> { throw new NotFoundException("item_not_found"); });
            app.error(NotFoundException.class, (ctx, failure) -> {
                calls.add("handled again");
                return Response.of(500, "loop");
            });
            app.start();
            var response = app.handle(Request.get("/fail"));
            assertThat(response.status()).isEqualTo(404);
            assertThat(text(response)).contains("\"code\":\"item_not_found\"").doesNotContain("POISON");
            assertThat(calls).isEmpty();
        }
    }

    @Test
    void failingOrNullErrorHandlersFallBackToTheGenericProblemWithoutLeaking() throws Exception {
        try (var app = Axiom.create()) {
            app.get("/throws", ctx -> { throw new IllegalStateException(POISON); });
            app.get("/null", ctx -> { throw new IllegalArgumentException(POISON); });
            app.error(IllegalStateException.class, (ctx, failure) -> { throw new RuntimeException(POISON, failure); });
            app.error(IllegalArgumentException.class, (ctx, failure) -> null);
            app.start();
            for (var path : List.of("/throws", "/null")) {
                var response = app.handle(Request.get(path));
                assertThat(response.status()).isEqualTo(500);
                assertThat(response.headers()).containsEntry("Content-Type", "application/problem+json");
                assertThat(text(response)).matches(
                        "\\{\"status\":500,\"code\":\"internal_server_error\",\"requestId\":\"[A-Za-z0-9_-]+-[0-9a-f]+\"}");
                assertThat(text(response) + response.headers()).doesNotContain("POISON", "script", "passwd",
                        "Exception", "java.", "at io.");
            }
            var head = app.handle(new Request("HEAD", "/throws"));
            assertThat(head.status()).isEqualTo(500);
            assertThat(head.body()).isNull();
        }
    }

    @Test
    void unmappedExceptionsBehaveAsBefore() throws Exception {
        try (var app = throwing(new UnsupportedOperationException(POISON))) {
            app.error(IllegalStateException.class, (ctx, failure) -> Response.of(409, "unrelated"));
            app.start();
            assertThatThrownBy(() -> app.handle(Request.get("/fail"))).isInstanceOf(UnsupportedOperationException.class);
        }
    }

    @Test
    void requestsRejectedBeforeRoutingAndRouterAnswersAreNotOffered() throws Exception {
        try (var app = Axiom.create()) {
            app.maxRequestBody(0);
            app.post("/upload", ctx -> "stored");
            app.error(AxiomException.class, (ctx, failure) -> Response.of(599, "mapped"));
            app.start();
            var large = new Request("POST", "/upload").withBody(com.jsgalactic.axiom.http.Body.of("text/plain", new byte[1]));
            assertThat(app.handle(large).status()).isEqualTo(413);
            assertThat(app.handle(Request.get("/missing")).status()).isEqualTo(404);
        }
    }

    @Test
    void registrationRulesMatchRoutes() {
        try (var app = Axiom.create()) {
            app.error(IllegalStateException.class, (ctx, failure) -> Response.of(409, null));
            assertThatIllegalArgumentException().isThrownBy(
                    () -> app.error(IllegalStateException.class, (ctx, failure) -> Response.of(409, null)));
            assertThatThrownBy(() -> app.error(null, (ctx, failure) -> null)).isInstanceOf(NullPointerException.class);
            assertThatThrownBy(() -> app.error(Exception.class, null)).isInstanceOf(NullPointerException.class);
            app.start();
            assertThatIllegalStateException().isThrownBy(
                    () -> app.error(Exception.class, (ctx, failure) -> Response.of(500, null)));
        }
    }
}
