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

    @Test
    void rejectsWithServiceUnavailableWhenAdmissionCapacityIsHeld() throws Exception {
        var entered = new java.util.concurrent.CountDownLatch(1);
        var release = new java.util.concurrent.CountDownLatch(1);
        var app = Axiom.create();
        app.admissionPolicy(io.axiom.execution.AdmissionPolicy.reject(1));
        app.get("/slow", ctx -> { entered.countDown(); release.await(); return "done"; });
        try (var client = TestClient.start(app)) {
            var first = client.submit(Request.get("/slow"));
            assertThat(entered.await(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            assertProblem(client.get("/slow"), 503, "service_unavailable");
            release.countDown();
            assertThat(first.get(5, java.util.concurrent.TimeUnit.SECONDS).body()).isEqualTo("done");
        } finally { release.countDown(); }
    }

    @Test
    void answersGatewayTimeoutWhenTheDeadlineExpires() throws Exception {
        var app = Axiom.create();
        app.requestTimeout(java.time.Duration.ofMillis(50));
        app.get("/hang", ctx -> { new java.util.concurrent.CountDownLatch(1).await(); return "never"; });
        try (var client = TestClient.start(app)) {
            assertProblem(client.get("/hang"), 504, "gateway_timeout");
        }
    }

    @Test
    void failsWhereTheTransportWouldAnswer500() throws Exception {
        var app = Axiom.create();
        app.get("/object", ctx -> java.util.List.of("not serializable"));
        try (var client = TestClient.start(app)) {
            org.assertj.core.api.Assertions.assertThatThrownBy(() -> client.get("/object"))
                    .isInstanceOf(IllegalStateException.class).hasMessageContaining("cannot be serialized");
        }
    }

    private static String text(io.axiom.http.Response response) {
        var body = response.body();
        return body instanceof byte[] bytes ? new String(bytes, java.nio.charset.StandardCharsets.UTF_8) : (String) body;
    }

    private static void assertProblem(io.axiom.http.Response response, int status, String code) {
        assertThat(response.status()).isEqualTo(status);
        assertThat(response.headers()).containsEntry("Content-Type", "application/problem+json");
        assertThat(text(response)).matches("\\{\"status\":" + status + ",\"code\":\"" + code
                + "\",\"requestId\":\"[A-Za-z0-9_-]+-[0-9a-f]+\"}");
    }

    @Test
    void sendsRawBodiesWithTheirContentType() throws Exception {
        var app = Axiom.create();
        app.post("/echo/:id", ctx -> ctx.path("id") + "|" + ctx.request().body().contentType().orElse("none") + "|"
                + new String(ctx.request().body().bytes(), java.nio.charset.StandardCharsets.UTF_8));
        app.put("/echo/:id", ctx -> "put " + ctx.request().body().length());
        app.patch("/echo/:id", ctx -> "patch " + ctx.header("content-type").orElse("none"));
        try (var client = TestClient.start(app)) {
            assertThat(client.post("/echo/1", "application/json", "{\"name\":\"pen\"}").body())
                    .isEqualTo("1|application/json|{\"name\":\"pen\"}");
            assertThat(client.post("/echo/2", null, new byte[] {'x'}).body()).isEqualTo("2|none|x");
            assertThat(client.put("/echo/3", "application/octet-stream", new byte[3]).body()).isEqualTo("put 3");
            assertThat(client.put("/echo/3", "text/plain", "four").body()).isEqualTo("put 4");
            assertThat(client.patch("/echo/4", "application/merge-patch+json", "{}").body())
                    .isEqualTo("patch application/merge-patch+json");
            assertThat(client.patch("/echo/4", null, new byte[0]).body()).isEqualTo("patch none");
        }
    }

    @Test
    void answersFrameworkErrorsWithTheListenersProblemBodies() throws Exception {
        var app = Axiom.create();
        app.maxRequestBody(8);
        app.post("/decode", ctx -> ctx.body(String.class));
        app.get("/conflict", ctx -> { throw new io.axiom.error.ConflictException(); });
        app.get("/limited", ctx -> {
            throw new io.axiom.error.TooManyRequestsException(java.time.Duration.ofSeconds(5));
        });
        try (var client = TestClient.start(app)) {
            assertProblem(client.post("/decode", "text/plain", "123456789"), 413, "content_too_large");
            assertProblem(client.post("/decode", "text/x-unknown", "POISON"), 415, "unsupported_media_type");
            assertProblem(client.post("/decode", null, "POISON"), 415, "missing_content_type");
            assertProblem(client.post("/decode", "text/plain", ""), 400, "empty_body");
            assertProblem(client.get("/missing"), 404, "not_found");
            var mismatch = client.execute(new Request("DELETE", "/decode"));
            assertProblem(mismatch, 405, "method_not_allowed");
            assertThat(mismatch.headers()).containsEntry("Allow", "POST");
            assertProblem(client.get("/conflict"), 409, "conflict");
            var limited = client.get("/limited");
            assertProblem(limited, 429, "too_many_requests");
            assertThat(limited.headers()).containsEntry("Retry-After", "5");
        }
    }
}
