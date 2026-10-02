package io.axiom.server;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

import io.axiom.Axiom;
import io.axiom.application.Application;
import io.axiom.error.AxiomException;
import io.axiom.error.BadGatewayException;
import io.axiom.error.BadRequestException;
import io.axiom.error.ConflictException;
import io.axiom.error.DecodeException;
import io.axiom.error.ForbiddenException;
import io.axiom.error.GatewayTimeoutException;
import io.axiom.error.GoneException;
import io.axiom.error.InternalServerErrorException;
import io.axiom.error.LengthRequiredException;
import io.axiom.error.MethodNotAllowedException;
import io.axiom.error.NotAcceptableException;
import io.axiom.error.NotFoundException;
import io.axiom.error.NotImplementedException;
import io.axiom.error.PayloadTooLargeException;
import io.axiom.error.PreconditionFailedException;
import io.axiom.error.PreconditionRequiredException;
import io.axiom.error.RequestHeaderFieldsTooLargeException;
import io.axiom.error.RequestTimeoutException;
import io.axiom.error.ServiceUnavailableException;
import io.axiom.error.TooManyRequestsException;
import io.axiom.error.UnauthorizedException;
import io.axiom.error.UnprocessableContentException;
import io.axiom.error.UnsupportedMediaTypeException;
import io.axiom.error.UriTooLongException;
import io.axiom.error.ValidationException;
import io.axiom.error.Violation;
import io.axiom.execution.ExecutionContext;
import io.axiom.http.Body;
import io.axiom.http.Request;
import io.axiom.http.Response;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class BodiesAndErrorsTest {
    record Note(String title, int priority) { }

    private static Request post(String path, String contentType, String body) {
        return new Request("POST", path).withBody(Body.of(contentType, body.getBytes(StandardCharsets.UTF_8)));
    }

    private static String text(Response response) {
        var body = response.body();
        return body instanceof byte[] bytes ? new String(bytes, StandardCharsets.UTF_8) : (String) body;
    }

    private static Application notes() {
        var app = Axiom.create();
        app.post("/notes/:id", ctx -> {
            var note = ctx.body(Note.class);
            return ctx.status(201).json(new Note(ctx.path("id") + ":" + note.title(), note.priority()))
                    .withLocation("/notes/" + ctx.path("id"));
        });
        return app.start();
    }

    @Test void decodesRecordBodiesAndEncodesJsonResponsesWhenPrepared() throws Exception {
        try (var app = notes()) {
            var response = app.handle(post("/notes/7", "application/json; charset=UTF-8", "title=hello;priority=2"));
            assertThat(response.status()).isEqualTo(201);
            assertThat(response.headers()).containsEntry("Content-Type", "application/json")
                    .containsEntry("Location", "/notes/7");
            assertThat(text(response)).isEqualTo("title=7:hello;priority=2");
        }
    }

    @Test void sendsStringsAndBytesGivenToJsonVerbatim() throws Exception {
        try (var app = Axiom.create()) {
            app.get("/raw", ctx -> ctx.json("{\"already\":true}"));
            app.start();
            var response = app.handle(Request.get("/raw"));
            assertThat(response.body()).isEqualTo("{\"already\":true}");
            assertThat(response.headers()).containsEntry("content-type", "application/json");
        }
    }

    @Test void answersMissingOrUnsupportedTypesWith415AndEmptyBodiesWith400() throws Exception {
        try (var app = notes()) {
            assertProblem(app.handle(new Request("POST", "/notes/1")), 400, "empty_body");
            assertProblem(app.handle(post("/notes/1", "application/json", "")), 400, "empty_body");
            assertProblem(app.handle(post("/notes/1", null, "title=a;priority=1")), 415, "missing_content_type");
            assertProblem(app.handle(post("/notes/1", "not a type", "title=a;priority=1")), 415, "missing_content_type");
            assertProblem(app.handle(post("/notes/1", "text/xml", "<note/>")), 415, "unsupported_media_type");
            assertProblem(app.handle(post("/notes/1", "application/json; charset=iso-8859-1", "title=a;priority=1")),
                    415, "unsupported_charset");
            assertProblem(app.handle(post("/notes/1", "application/json", "null")), 400, "null_body");
            var mismatch = app.handle(post("/notes/1", "application/json", "title=a;priority=high"));
            assertProblem(mismatch, 400, "type_mismatch");
            assertThat(text(mismatch)).contains("\"violations\":[{\"field\":\"priority\",\"code\":\"type_mismatch\"}]");
        }
    }

    @Test void enforcesTheRequestBodyLimitBeforeRouting() throws Exception {
        try (var app = Axiom.create()) {
            assertThat(app.maxRequestBody()).isEqualTo(1024 * 1024);
            assertThatIllegalArgumentException().isThrownBy(() -> app.maxRequestBody(-1));
            assertThatIllegalArgumentException().isThrownBy(() -> app.maxRequestBody(64 * 1024 * 1024 + 1));
            app.maxRequestBody(4);
            app.post("/", ctx -> { throw new AssertionError("must not run"); });
            app.start();
            assertThatIllegalStateException().isThrownBy(() -> app.maxRequestBody(10));
            assertProblem(app.handle(post("/", "application/json", "12345")), 413, "content_too_large");
            assertProblem(app.handle(post("/missing", "application/json", "12345")), 413, "content_too_large");
        }
    }

    @Test void negotiatesCodecResponsesAgainstAccept() throws Exception {
        try (var app = Axiom.create()) {
            app.get("/note", ctx -> ctx.json(new Note("n", 1)));
            app.get("/text", ctx -> "plain");
            app.start();
            for (var accept : new String[] {"*/*", "application/*", "application/json", "text/html, application/json;q=0.5",
                    "APPLICATION/JSON", "*/*;q=0.1, application/json", "garbage", "application/json;q=2"}) {
                var accepted = app.handle(Request.get("/note").withHeaders(Map.of("Accept", accept)));
                assertThat(accepted.status()).as(accept).isEqualTo(200);
            }
            assertThat(app.handle(Request.get("/note")).status()).isEqualTo(200);
            for (var accept : new String[] {"text/html", "application/json;q=0", "application/xml, text/*",
                    "application/json;q=0, */*"}) {
                assertProblem(app.handle(Request.get("/note").withHeaders(Map.of("Accept", accept))), 406, "not_acceptable");
            }
            // Responses without a codec are not negotiated.
            assertThat(app.handle(Request.get("/text").withHeaders(Map.of("Accept", "application/json"))).status())
                    .isEqualTo(200);
        }
    }

    @Test void answersUnmatchedRoutesAndMethodsWithProblems() throws Exception {
        try (var app = Axiom.create()) {
            app.get("/users", ctx -> "users");
            app.post("/users", ctx -> "created");
            app.start();
            var missing = app.handle(Request.get("/missing"));
            assertProblem(missing, 404, "not_found");
            var mismatch = app.handle(new Request("DELETE", "/users"));
            assertProblem(mismatch, 405, "method_not_allowed");
            assertThat(mismatch.headers()).containsEntry("Allow", "GET, HEAD, POST");
            var head = app.handle(new Request("HEAD", "/missing"));
            assertThat(head.status()).isEqualTo(404);
            assertThat(head.body()).isNull();
        }
    }

    static Stream<Arguments> taxonomy() {
        return Stream.<Object[]>of(
                new Object[] {(Supplier<AxiomException>) BadRequestException::new, 400, Map.of()},
                new Object[] {(Supplier<AxiomException>) () -> new DecodeException("malformed_body"), 400, Map.of()},
                new Object[] {(Supplier<AxiomException>) () -> new UnauthorizedException("Bearer realm=\"api\""), 401,
                        Map.of("WWW-Authenticate", "Bearer realm=\"api\"")},
                new Object[] {(Supplier<AxiomException>) ForbiddenException::new, 403, Map.of()},
                new Object[] {(Supplier<AxiomException>) NotFoundException::new, 404, Map.of()},
                new Object[] {(Supplier<AxiomException>) () -> new MethodNotAllowedException(Set.of("GET", "POST")), 405,
                        Map.of("Allow", "GET, POST")},
                new Object[] {(Supplier<AxiomException>) NotAcceptableException::new, 406, Map.of()},
                new Object[] {(Supplier<AxiomException>) RequestTimeoutException::new, 408, Map.of()},
                new Object[] {(Supplier<AxiomException>) ConflictException::new, 409, Map.of()},
                new Object[] {(Supplier<AxiomException>) GoneException::new, 410, Map.of()},
                new Object[] {(Supplier<AxiomException>) LengthRequiredException::new, 411, Map.of()},
                new Object[] {(Supplier<AxiomException>) PreconditionFailedException::new, 412, Map.of()},
                new Object[] {(Supplier<AxiomException>) PayloadTooLargeException::new, 413, Map.of()},
                new Object[] {(Supplier<AxiomException>) UriTooLongException::new, 414, Map.of()},
                new Object[] {(Supplier<AxiomException>) UnsupportedMediaTypeException::new, 415, Map.of()},
                new Object[] {(Supplier<AxiomException>) UnprocessableContentException::new, 422, Map.of()},
                new Object[] {(Supplier<AxiomException>) PreconditionRequiredException::new, 428, Map.of()},
                new Object[] {(Supplier<AxiomException>) () -> new TooManyRequestsException(Duration.ofSeconds(30)), 429,
                        Map.of("Retry-After", "30")},
                new Object[] {(Supplier<AxiomException>) RequestHeaderFieldsTooLargeException::new, 431, Map.of()},
                new Object[] {(Supplier<AxiomException>) InternalServerErrorException::new, 500, Map.of()},
                new Object[] {(Supplier<AxiomException>) NotImplementedException::new, 501, Map.of()},
                new Object[] {(Supplier<AxiomException>) BadGatewayException::new, 502, Map.of()},
                new Object[] {(Supplier<AxiomException>) () -> new ServiceUnavailableException(Duration.ofMinutes(1)), 503,
                        Map.of("Retry-After", "60")},
                new Object[] {(Supplier<AxiomException>) GatewayTimeoutException::new, 504, Map.of()}
        ).map(Arguments::of);
    }

    @ParameterizedTest(name = "{1}")
    @MethodSource("taxonomy")
    void mapsEveryExceptionToAProblemWithoutInternals(Supplier<AxiomException> factory, int status,
            Map<String, String> headers) throws Exception {
        try (var app = Axiom.create()) {
            app.get("/", ctx -> {
                var failure = factory.get();
                failure.initCause(new IllegalStateException("POISON secret from " + ctx.path()));
                throw failure;
            });
            app.start();
            var execution = ExecutionContext.create(Duration.ofSeconds(5));
            var response = app.handle(Request.get("/").withHeaders(Map.of("X-Input", "POISON")), execution);
            var expected = factory.get();
            assertThat(response.status()).isEqualTo(status);
            assertThat(response.headers()).containsEntry("Content-Type", "application/problem+json")
                    .containsAllEntriesOf(headers);
            assertThat(response.headers().keySet()).containsExactlyInAnyOrderElementsOf(
                    Stream.concat(Stream.of("Content-Type"), headers.keySet().stream()).toList());
            assertThat(text(response)).isEqualTo("{\"status\":" + status + ",\"code\":\"" + expected.code()
                    + "\",\"requestId\":\"" + execution.requestId() + "\"}");
            assertNoInternals(text(response));
        }
    }

    @Test void mapsValidationViolationsAndKeepsInputOutOfDecodeFailures() throws Exception {
        try (var app = Axiom.create()) {
            app.post("/validate", ctx -> {
                throw new ValidationException(List.of(new Violation("title", "required"), new Violation("tags[1]", "too_long")));
            });
            app.post("/decode", ctx -> ctx.body(Note.class));
            app.start();
            var validation = app.handle(new Request("POST", "/validate"));
            assertProblem(validation, 422, "validation_failed");
            assertThat(text(validation)).endsWith(",\"violations\":[{\"field\":\"title\",\"code\":\"required\"},"
                    + "{\"field\":\"tags[1]\",\"code\":\"too_long\"}]}");
            for (var poisoned : new String[] {"POISON=java.lang.Object;title=x;priority=1", "title=x;priority=POISON",
                    "<script>POISON</script>"}) {
                var response = app.handle(post("/decode", "application/json", poisoned));
                assertThat(response.status()).isEqualTo(400);
                assertNoInternals(text(response));
            }
        }
    }

    @Test void failsStartupWhenTwoCodecsClaimAMediaTypeAndStaysConfiguring(@TempDir Path services) throws Exception {
        var file = services.resolve("META-INF/services/io.axiom.codec.spi.BodyCodec");
        Files.createDirectories(file.getParent());
        Files.writeString(file, OtherJsonCodec.class.getName() + "\n");
        var thread = Thread.currentThread();
        var original = thread.getContextClassLoader();
        try (var loader = new URLClassLoader(new java.net.URL[] {services.toUri().toURL()}, original);
                var app = Axiom.create()) {
            app.get("/", ctx -> "ok");
            thread.setContextClassLoader(loader);
            assertThatIllegalStateException().isThrownBy(app::start)
                    .withMessageContaining("application/json").withMessageContaining("OtherJsonCodec");
            assertThat(app.state()).isEqualTo(Application.State.CONFIGURING);
            thread.setContextClassLoader(original);
            assertThat(app.start().state()).isEqualTo(Application.State.RUNNING);
        } finally { thread.setContextClassLoader(original); }
    }

    private static void assertProblem(Response response, int status, String code) {
        assertThat(response.status()).isEqualTo(status);
        assertThat(response.headers()).containsEntry("Content-Type", "application/problem+json");
        assertThat(text(response)).startsWith("{\"status\":" + status + ",\"code\":\"" + code + "\",\"requestId\":\"");
        assertNoInternals(text(response));
    }

    private static void assertNoInternals(String body) {
        assertThat(body).doesNotContain("POISON", "secret", "Exception", "io.axiom", "java.", "\tat ", "<script>");
    }
}
