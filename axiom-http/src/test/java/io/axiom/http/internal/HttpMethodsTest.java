package io.axiom.http.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Request methods over a real socket. */
class HttpMethodsTest {
    @ParameterizedTest
    @ValueSource(strings = {"G(T", "G\"T", "G,T", "G/T", "G:T", "G@T", "G[T", "G\\T", "G{T", "G\u0001T", "G\u007fT"})
    void answersAMethodThatIsNotATokenWith400AndCloses(String method) throws Exception {
        var calls = new AtomicInteger();
        try (var fixture = new Fixture()) {
            fixture.app.get("/x", ctx -> { calls.incrementAndGet(); return "x"; });
            try (var wire = new Wire(fixture.listen())) {
                wire.write(method + " /x HTTP/1.1\r\nHost: a\r\n\r\n");
                var reply = wire.read(false);
                assertThat(reply.status()).isEqualTo(400);
                assertThat(reply.headers()).containsEntry("Content-Type", "application/problem+json");
                assertThat(reply.headers()).containsEntry("Connection", "close");
                assertThat(wire.socket.getInputStream().read()).isEqualTo(-1);
            }
            assertThat(calls).hasValue(0);
        }
    }

    @Test void answersOptionsWith204AndAllowWithoutRunningAHandler() throws Exception {
        var calls = new AtomicInteger();
        try (var fixture = new Fixture()) {
            fixture.app.get("/users", ctx -> { calls.incrementAndGet(); return "users"; });
            fixture.app.post("/users", ctx -> { calls.incrementAndGet(); return "created"; });
            fixture.app.options("/custom", ctx -> ctx.status(200).text("custom"));
            try (var wire = new Wire(fixture.listen())) {
                wire.write("OPTIONS /users HTTP/1.1\r\nHost: a\r\n\r\n");
                var options = wire.read(false);
                assertThat(options.status()).isEqualTo(204);
                assertThat(options.headers()).containsEntry("Allow", "GET, HEAD, OPTIONS, POST");
                assertThat(options.headers()).doesNotContainKey("Content-Length").doesNotContainKey("Content-Type");
                assertThat(options.headers()).containsKey("X-Request-ID").containsKey("Date");
                assertThat(options.body()).isEmpty();

                wire.write("OPTIONS /custom HTTP/1.1\r\nHost: a\r\n\r\n");
                assertThat(wire.read(false).text()).isEqualTo("custom");
                wire.write("OPTIONS /missing HTTP/1.1\r\nHost: a\r\n\r\n");
                assertThat(wire.read(false).status()).isEqualTo(404);

                // A body is read within the limit and discarded; the connection carries the next request.
                wire.write("OPTIONS /users HTTP/1.1\r\nHost: a\r\nContent-Length: 5\r\n\r\nhello"
                        + "OPTIONS /users HTTP/1.1\r\nHost: a\r\nTransfer-Encoding: chunked\r\n\r\n3\r\nabc\r\n0\r\n\r\n");
                assertThat(wire.read(false).status()).isEqualTo(204);
                assertThat(wire.read(false).status()).isEqualTo(204);
                assertThat(wire.get("/users").text()).isEqualTo("users");
            }
            assertThat(calls).hasValue(1);
        }
    }

    @Test void rejectsAnOversizedOptionsBodyWith413() throws Exception {
        try (var fixture = new Fixture()) {
            fixture.app.maxRequestBody(4);
            fixture.app.get("/users", ctx -> "users");
            try (var wire = new Wire(fixture.listen())) {
                wire.write("OPTIONS /users HTTP/1.1\r\nHost: a\r\nContent-Length: 5\r\n\r\nhello");
                var reply = wire.read(false);
                assertThat(reply.status()).isEqualTo(413);
                assertThat(reply.headers()).containsEntry("Connection", "close");
            }
        }
    }

    @Test void answersOptionsAsteriskWithTheServerWideAllow() throws Exception {
        var calls = new AtomicInteger();
        try (var fixture = new Fixture()) {
            fixture.app.get("/a", ctx -> { calls.incrementAndGet(); return "a"; });
            fixture.app.route("PROPFIND", "/b", ctx -> { calls.incrementAndGet(); return "b"; });
            try (var wire = new Wire(fixture.listen())) {
                wire.write("OPTIONS * HTTP/1.1\r\nHost: a\r\n\r\n");
                var asterisk = wire.read(false);
                assertThat(asterisk.status()).isEqualTo(204);
                assertThat(asterisk.headers()).containsEntry("Allow", "GET, HEAD, OPTIONS, PROPFIND");
                assertThat(asterisk.headers()).doesNotContainKey("Content-Length");
                // Keep-alive: the next request on the connection is served.
                assertThat(wire.get("/a").text()).isEqualTo("a");
                wire.write("OPTIONS * HTTP/1.0\r\n\r\n");
                assertThat(wire.read(false).status()).isEqualTo(204);
            }
            assertThat(calls).hasValue(1);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"GET *", "HEAD *", "POST *", "options *", "PROPFIND *", "OPTIONS *?x=1", "OPTIONS *?",
            "OPTIONS **", "OPTIONS */a", "OPTIONS *a", "OPTIONS http://a/a", "GET http://a/a", "OPTIONS a",
            "OPTIONS //a", "OPTIONS /a/../a", "OPTIONS /a%2Fb"})
    void rejectsOtherAsteriskAbsoluteAndUnsafeTargetsWith400(String requestLine) throws Exception {
        var calls = new AtomicInteger();
        try (var fixture = new Fixture()) {
            fixture.app.get("/a", ctx -> { calls.incrementAndGet(); return "a"; });
            fixture.app.options("/*any", ctx -> { calls.incrementAndGet(); return "any"; });
            try (var wire = new Wire(fixture.listen())) {
                wire.write(requestLine + " HTTP/1.1\r\nHost: a\r\nContent-Length: 0\r\n\r\n");
                var reply = wire.read(requestLine.startsWith("HEAD"));
                assertThat(reply.status()).isEqualTo(400);
                assertThat(reply.headers()).containsEntry("Connection", "close");
            }
            assertThat(calls).hasValue(0);
        }
    }

    @Test void answersTraceWith405WithoutReflectingTheRequest() throws Exception {
        try (var fixture = new Fixture()) {
            fixture.app.get("/x", ctx -> "x");
            try (var wire = new Wire(fixture.listen())) {
                wire.write("TRACE /x HTTP/1.1\r\nHost: a\r\nCookie: session=secret\r\nAuthorization: Bearer token\r\n\r\n");
                var trace = wire.read(false);
                assertThat(trace.status()).isEqualTo(405);
                assertThat(trace.headers()).containsEntry("Allow", "GET, HEAD");
                assertThat(trace.headers()).containsEntry("Content-Type", "application/problem+json");
                assertThat(trace.headers().get("Content-Type")).isNotEqualTo("message/http");
                assertThat(trace.text()).doesNotContain("secret").doesNotContain("token").doesNotContain("TRACE");
                wire.write("TRACE /missing HTTP/1.1\r\nHost: a\r\n\r\n");
                assertThat(wire.read(false).status()).isEqualTo(404);
                assertThat(wire.get("/x").text()).isEqualTo("x");
            }
        }
    }

    @Test void matchesMethodTokensCaseSensitively() throws Exception {
        try (var fixture = new Fixture()) {
            fixture.app.route("M-SEARCH", "/x", ctx -> ctx.method());
            fixture.app.route("get", "/x", ctx -> "lower " + ctx.method());
            fixture.app.get("/x", ctx -> "upper " + ctx.method());
            try (var wire = new Wire(fixture.listen())) {
                wire.write("M-SEARCH /x HTTP/1.1\r\nHost: a\r\n\r\n");
                assertThat(wire.read(false).text()).isEqualTo("M-SEARCH");
                wire.write("get /x HTTP/1.1\r\nHost: a\r\n\r\n");
                assertThat(wire.read(false).text()).isEqualTo("lower get");
                assertThat(wire.get("/x").text()).isEqualTo("upper GET");
                wire.write("Get /x HTTP/1.1\r\nHost: a\r\n\r\n");
                var mixed = wire.read(false);
                assertThat(mixed.status()).isEqualTo(405);
                assertThat(mixed.headers()).containsEntry("Allow", "GET, HEAD, M-SEARCH, get");
                // A method mismatch is a runtime error: the connection stays open.
                assertThat(wire.get("/x").status()).isEqualTo(200);
            }
        }
    }
}
