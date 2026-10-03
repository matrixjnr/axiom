package com.jsgalactic.axiom.server;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.jsgalactic.axiom.Axiom;
import com.jsgalactic.axiom.application.Application;
import com.jsgalactic.axiom.error.NotFoundException;
import com.jsgalactic.axiom.http.Request;
import com.jsgalactic.axiom.http.Response;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

/** Development errors are off by default and never leak details unless the application opts in. */
class DevelopmentErrorsTest {
    private static final String SECRET_DETAIL = "POISON-detail-42";

    private static String text(Response response) {
        return response.body() instanceof byte[] bytes ? new String(bytes, StandardCharsets.UTF_8)
                : String.valueOf(response.body());
    }

    private static Application app(boolean development) {
        var app = Axiom.create();
        if (development) { app.developmentErrors(); }
        app.get("/boom", ctx -> { throw new IllegalStateException(SECRET_DETAIL, new java.io.IOException("disk \"x\"")); });
        app.get("/missing", ctx -> { throw new NotFoundException("note_not_found"); });
        app.get("/handler-fails", ctx -> { throw new UnsupportedOperationException(SECRET_DETAIL); });
        app.get("/mapped", ctx -> { throw new ArithmeticException(SECRET_DETAIL); });
        app.error(UnsupportedOperationException.class, (ctx, failure) -> { throw new IllegalArgumentException("inner"); });
        app.error(ArithmeticException.class, (ctx, failure) -> ctx.status(418).text("teapot"));
        return app;
    }

    @Test
    void isOffByDefaultAndNothingLeaks() throws Exception {
        try (var app = app(false)) {
            app.start();
            assertThatThrownBy(() -> app.handle(Request.get("/boom"))).isInstanceOf(IllegalStateException.class);
            for (var path : new String[] {"/missing", "/handler-fails", "/mapped"}) {
                var body = text(app.handle(Request.get(path)));
                assertThat(body).as(path).doesNotContain("debug", "POISON", "Exception", "stack", "java.", "inner");
            }
        }
    }

    @Test
    void whenEnabledFailuresDescribeTheException() throws Exception {
        try (var app = app(true)) {
            app.start();
            var boom = app.handle(Request.get("/boom"));
            assertThat(boom.status()).isEqualTo(500);
            assertThat(boom.headers()).containsEntry("Content-Type", "application/problem+json")
                    .containsEntry("Connection", "close");
            var body = text(boom);
            assertThat(body).startsWith("{\"status\":500,\"code\":\"internal_server_error\",\"requestId\":\"")
                    .contains("\"debug\":{\"type\":\"java.lang.IllegalStateException\"")
                    .contains("\"message\":\"" + SECRET_DETAIL + "\"")
                    .contains("DevelopmentErrorsTest.lambda")
                    .contains("\"causes\":[{\"type\":\"java.io.IOException\",\"message\":\"disk \\\"x\\\"\"}]")
                    .endsWith("]}}");

            var missing = text(app.handle(Request.get("/missing")));
            assertThat(missing).contains("\"code\":\"note_not_found\"")
                    .contains("\"debug\":{\"type\":\"com.jsgalactic.axiom.error.NotFoundException\"");

            // The failure of the error handler is the one described.
            var failed = app.handle(Request.get("/handler-fails"));
            assertThat(failed.status()).isEqualTo(500);
            assertThat(text(failed)).contains("\"type\":\"java.lang.IllegalArgumentException\"")
                    .contains("\"message\":\"inner\"");

            // What an application error handler builds is its own responsibility and is left alone.
            var mapped = app.handle(Request.get("/mapped"));
            assertThat(mapped.status()).isEqualTo(418);
            assertThat(text(mapped)).isEqualTo("teapot");
        }
    }

    @Test
    void boundsFramesAndCauses() throws Exception {
        try (var app = Axiom.create().developmentErrors()) {
            app.get("/deep", ctx -> {
                Throwable cause = new RuntimeException("c0");
                for (int i = 1; i < 20; i++) { cause = new RuntimeException("c" + i, cause); }
                throw new IllegalStateException("top", cause);
            });
            app.start();
            var body = text(app.handle(Request.get("/deep")));
            assertThat(body.split("\"type\":\"java.lang.RuntimeException\"", -1)).hasSize(1 + 8);
            assertThat(body).contains("\"message\":\"c19\"").doesNotContain("\"message\":\"c11\"");
        }
    }

    @Test
    void refusesToListenOffLoopbackAndIsFixedAtStartup() throws Exception {
        try (var app = app(true)) {
            assertThatIllegalStateException().isThrownBy(() -> app.listen(new InetSocketAddress(0)))
                    .withMessageContaining("loopback");
            assertThatIllegalStateException().isThrownBy(() -> app.listen(
                    new InetSocketAddress(InetAddress.getByAddress(new byte[] {10, 1, 2, 3}), 0)));
            assertThatIllegalStateException().isThrownBy(() -> app.listen(
                    InetSocketAddress.createUnresolved("example.invalid", 8080)));
            // Refusing does not start the application.
            assertThat(app.state()).isEqualTo(Application.State.CONFIGURING);
            app.start();
            assertThatIllegalStateException().isThrownBy(app::developmentErrors);
        }
    }
}
