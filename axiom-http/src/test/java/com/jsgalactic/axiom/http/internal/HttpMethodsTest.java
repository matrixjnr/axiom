package com.jsgalactic.axiom.http.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Request methods over a real socket. */
@Tag("integration")
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
                assertThat(trace.headers()).containsEntry("Allow", "GET, HEAD, OPTIONS");
                assertThat(trace.headers()).containsEntry("Content-Type", "application/problem+json");
                assertThat(trace.headers().get("Content-Type")).isNotEqualTo("message/http");
                assertThat(trace.text()).doesNotContain("secret").doesNotContain("token").doesNotContain("TRACE");
                wire.write("TRACE /missing HTTP/1.1\r\nHost: a\r\n\r\n");
                assertThat(wire.read(false).status()).isEqualTo(404);
                assertThat(wire.get("/x").text()).isEqualTo("x");
            }
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"CONNECT a:443", "CONNECT /x", "CONNECT /missing", "CONNECT *"})
    void answersConnectWith501AndClosesWithoutReadingTunnelBytes(String requestLine) throws Exception {
        var calls = new AtomicInteger();
        try (var fixture = new Fixture()) {
            fixture.app.get("/x", ctx -> { calls.incrementAndGet(); return "x"; });
            try (var wire = new Wire(fixture.listen())) {
                // Bytes after a CONNECT head belong to a tunnel; they must not be parsed as requests.
                wire.write(requestLine + " HTTP/1.1\r\nHost: a:443\r\n\r\n"
                        + "GET /x HTTP/1.1\r\nHost: a\r\n\r\n\u0016\u0003\u0001");
                var reply = wire.read(false);
                assertThat(reply.status()).isEqualTo(501);
                assertThat(reply.headers()).containsEntry("Connection", "close").doesNotContainKey("Allow");
                assertThat(wire.socket.getInputStream().read()).isEqualTo(-1);
            }
            assertThat(calls).hasValue(0);
        }
    }

    @Test void routesCustomMethodsAndAnswersUnrecognizedMethodsWith501() throws Exception {
        try (var fixture = new Fixture()) {
            fixture.app.route("PROPFIND", "/dav", ctx -> "propfind");
            fixture.app.route("QUERY", "/search", ctx -> "query " + new String(ctx.request().body().bytes(),
                    java.nio.charset.StandardCharsets.UTF_8));
            try (var wire = new Wire(fixture.listen())) {
                wire.write("QUERY /search HTTP/1.1\r\nHost: a\r\nContent-Type: text/plain\r\nContent-Length: 5\r\n\r\nq=abc");
                assertThat(wire.read(false).text()).isEqualTo("query q=abc");
                wire.write("PROPFIND /dav HTTP/1.1\r\nHost: a\r\n\r\n");
                assertThat(wire.read(false).text()).isEqualTo("propfind");

                wire.write("FOO /dav HTTP/1.1\r\nHost: a\r\n\r\n");
                var mismatch = wire.read(false);
                assertThat(mismatch.status()).isEqualTo(405);
                assertThat(mismatch.headers()).containsEntry("Allow", "OPTIONS, PROPFIND");

                wire.write("PROPFIND /missing HTTP/1.1\r\nHost: a\r\n\r\n");
                assertThat(wire.read(false).status()).isEqualTo(404);

                wire.write("FOO /missing HTTP/1.1\r\nHost: a\r\n\r\n");
                var unknown = wire.read(false);
                assertThat(unknown.status()).isEqualTo(501);
                assertThat(unknown.headers()).doesNotContainKey("Allow");
                assertThat(unknown.text()).contains("\"code\":\"not_implemented\"");
                // An application-level 501 keeps the connection open.
                assertThat(unknown.headers().get("Connection")).isNotEqualToIgnoringCase("close");
                wire.write("get /missing HTTP/1.1\r\nHost: a\r\n\r\n");
                assertThat(wire.read(false).status()).isEqualTo(501);
                wire.write("PROPFIND /dav HTTP/1.1\r\nHost: a\r\n\r\n");
                assertThat(wire.read(false).text()).isEqualTo("propfind");
            }
        }
    }

    @Test void customRouterAnswersKeepAllowAndTheConnection() throws Exception {
        try (var fixture = new Fixture()) {
            fixture.app.get("/x", ctx -> "x");
            fixture.app.notFound(ctx -> ctx.text("custom 404"));
            fixture.app.methodNotAllowed(ctx -> ctx.text("custom 405"));
            fixture.app.notImplemented(ctx -> ctx.text("custom 501"));
            try (var wire = new Wire(fixture.listen())) {
                wire.write("GET /missing HTTP/1.1\r\nHost: a\r\n\r\n");
                var missing = wire.read(false);
                assertThat(missing.status()).isEqualTo(404);
                assertThat(missing.text()).isEqualTo("custom 404");
                wire.write("PUT /x HTTP/1.1\r\nHost: a\r\nContent-Length: 0\r\n\r\n");
                var mismatch = wire.read(false);
                assertThat(mismatch.status()).isEqualTo(405);
                assertThat(mismatch.text()).isEqualTo("custom 405");
                assertThat(mismatch.headers()).containsEntry("Allow", "GET, HEAD, OPTIONS");
                wire.write("FOO /missing HTTP/1.1\r\nHost: a\r\n\r\n");
                var unknown = wire.read(false);
                assertThat(unknown.status()).isEqualTo(501);
                assertThat(unknown.text()).isEqualTo("custom 501");
                assertThat(unknown.headers().get("Connection")).isNotEqualToIgnoringCase("close");
                // CONNECT is refused before routing, with the built-in problem body, and closes.
                wire.write("CONNECT example.com:443 HTTP/1.1\r\nHost: example.com:443\r\n\r\n");
                var connect = wire.read(false);
                assertThat(connect.status()).isEqualTo(501);
                assertThat(connect.text()).contains("\"code\":\"not_implemented\"");
            }
        }
    }

    @Test void ignoresMethodOverrideHeaders() throws Exception {
        try (var fixture = new Fixture()) {
            fixture.app.post("/x", ctx -> "post " + ctx.method());
            fixture.app.delete("/x", ctx -> "delete");
            fixture.app.delete("/only-delete", ctx -> "delete");
            try (var wire = new Wire(fixture.listen())) {
                for (var header : new String[] {"X-HTTP-Method-Override", "X-HTTP-Method", "X-Method-Override"}) {
                    wire.write("POST /x HTTP/1.1\r\nHost: a\r\n" + header + ": DELETE\r\nContent-Length: 0\r\n\r\n");
                    assertThat(wire.read(false).text()).as(header).isEqualTo("post POST");
                    wire.write("POST /only-delete HTTP/1.1\r\nHost: a\r\n" + header + ": DELETE\r\nContent-Length: 0\r\n\r\n");
                    var mismatch = wire.read(false);
                    assertThat(mismatch.status()).as(header).isEqualTo(405);
                    assertThat(mismatch.headers()).as(header).containsEntry("Allow", "DELETE, OPTIONS");
                }
            }
        }
    }

    @Test void readsBodiesOfGetHeadDeleteAndOptionsAndNeverSendsAHeadBody() throws Exception {
        try (var fixture = new Fixture()) {
            fixture.app.maxRequestBody(8);
            fixture.app.get("/x", ctx -> "get " + new String(ctx.request().body().bytes(), java.nio.charset.StandardCharsets.UTF_8));
            fixture.app.delete("/x", ctx -> "delete " + ctx.request().body().length());
            fixture.app.options("/explicit", ctx -> ctx.status(200).text("options " + ctx.request().body().length()));
            try (var wire = new Wire(fixture.listen())) {
                wire.write("GET /x HTTP/1.1\r\nHost: a\r\nContent-Length: 4\r\n\r\nbody");
                assertThat(wire.read(false).text()).isEqualTo("get body");
                wire.write("DELETE /x HTTP/1.1\r\nHost: a\r\nTransfer-Encoding: chunked\r\n\r\n3\r\nabc\r\n0\r\n\r\n");
                assertThat(wire.read(false).text()).isEqualTo("delete 3");
                wire.write("OPTIONS /explicit HTTP/1.1\r\nHost: a\r\nContent-Length: 2\r\n\r\nab");
                assertThat(wire.read(false).text()).isEqualTo("options 2");
                // HEAD with a body: the body is consumed, no response body bytes follow.
                wire.write("HEAD /x HTTP/1.1\r\nHost: a\r\nContent-Length: 4\r\n\r\nbody");
                var head = wire.read(true);
                assertThat(head.status()).isEqualTo(200);
                assertThat(head.headers()).containsEntry("Content-Length", "8");
                assertThat(wire.get("/x").text()).isEqualTo("get ");
            }
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"GET", "HEAD", "DELETE", "OPTIONS"})
    void rejectsOversizedBodiesForEveryMethodWith413(String method) throws Exception {
        try (var fixture = new Fixture()) {
            fixture.app.maxRequestBody(4);
            fixture.app.route(method, "/x", ctx -> "x");
            try (var wire = new Wire(fixture.listen())) {
                wire.write(method + " /x HTTP/1.1\r\nHost: a\r\nContent-Length: 5\r\n\r\nhello");
                var reply = wire.read(method.equals("HEAD"));
                assertThat(reply.status()).isEqualTo(413);
                assertThat(reply.headers()).containsEntry("Connection", "close");
                if (method.equals("HEAD")) { assertThat(reply.headers()).doesNotContainKey("Content-Length"); }
                assertThat(wire.socket.getInputStream().read()).isEqualTo(-1);
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
                assertThat(mixed.headers()).containsEntry("Allow", "GET, HEAD, M-SEARCH, OPTIONS, get");
                // A method mismatch is a runtime error: the connection stays open.
                assertThat(wire.get("/x").status()).isEqualTo(200);
            }
        }
    }
}
