package io.axiom.http.internal;

import static org.assertj.core.api.Assertions.assertThat;

import io.axiom.http.Response;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class HttpBodyTest {
    private static Fixture echo(int limit) {
        var fixture = new Fixture();
        fixture.app.maxRequestBody(limit);
        fixture.app.post("/echo", ctx -> {
            var body = ctx.request().body();
            return Response.of(200, body.contentType().orElse("none") + "|" + body.length() + "|"
                    + new String(body.bytes(), StandardCharsets.UTF_8));
        });
        fixture.app.get("/next", ctx -> "next");
        return fixture;
    }

    @Test void deliversContentLengthBodiesWithTheirContentType() throws Exception {
        try (var fixture = echo(16); var wire = new Wire(fixture.listen())) {
            wire.write("POST /echo HTTP/1.1\r\nHost: a\r\nContent-Type: text/plain; charset=utf-8\r\n"
                    + "Content-Length: 5\r\n\r\nhello");
            var reply = wire.read(false);
            assertThat(reply.status()).isEqualTo(200);
            assertThat(reply.text()).isEqualTo("text/plain; charset=utf-8|5|hello");
            wire.write("POST /echo HTTP/1.1\r\nHost: a\r\nContent-Length: 16\r\n\r\n" + "x".repeat(16));
            assertThat(wire.read(false).text()).isEqualTo("none|16|" + "x".repeat(16));
        }
    }

    @Test void rejectsAnOversizedContentLengthBeforeReadingTheBody() throws Exception {
        var calls = new AtomicInteger();
        try (var fixture = echo(16)) {
            fixture.app.post("/count", ctx -> { calls.incrementAndGet(); return "called"; });
            try (var wire = new Wire(fixture.listen())) {
                // Only the head is sent: the 413 cannot depend on reading any body bytes.
                wire.write("POST /count HTTP/1.1\r\nHost: a\r\nContent-Length: 17\r\n\r\n");
                var reply = wire.read(false);
                assertThat(reply.status()).isEqualTo(413);
                assertThat(reply.headers()).containsEntry("connection", "close");
                assertThat(wire.socket.getInputStream().read()).isEqualTo(-1);
            }
        }
        assertThat(calls).hasValue(0);
    }

    @Test void deliversChunkedBodiesUnderTheLimit() throws Exception {
        try (var fixture = echo(16); var wire = new Wire(fixture.listen())) {
            wire.write("POST /echo HTTP/1.1\r\nHost: a\r\nTransfer-Encoding: chunked\r\nContent-Type: text/plain\r\n\r\n"
                    + "5\r\nhello\r\n6\r\n world\r\n0\r\n\r\n");
            assertThat(wire.read(false).text()).isEqualTo("text/plain|11|hello world");
            // Exactly at the limit is accepted.
            wire.write("POST /echo HTTP/1.1\r\nHost: a\r\nTransfer-Encoding: chunked\r\n\r\n"
                    + "10\r\n" + "y".repeat(16) + "\r\n0\r\n\r\n");
            assertThat(wire.read(false).text()).isEqualTo("none|16|" + "y".repeat(16));
        }
    }

    @Test void closesAChunkedBodyAsSoonAsItExceedsTheLimit() throws Exception {
        try (var fixture = echo(16); var wire = new Wire(fixture.listen())) {
            // The terminating chunk is never sent; the response must not wait for it.
            wire.write("POST /echo HTTP/1.1\r\nHost: a\r\nTransfer-Encoding: chunked\r\n\r\n"
                    + "a\r\n0123456789\r\n7\r\n0123456\r\n");
            var reply = wire.read(false);
            assertThat(reply.status()).isEqualTo(413);
            assertThat(reply.headers()).containsEntry("connection", "close");
            assertThat(wire.socket.getInputStream().read()).isEqualTo(-1);
        }
    }

    @Test void answersExpectContinueBeforeReadingTheBody() throws Exception {
        try (var fixture = echo(16); var wire = new Wire(fixture.listen())) {
            wire.write("POST /echo HTTP/1.1\r\nHost: a\r\nExpect: 100-continue\r\nContent-Length: 5\r\n\r\n");
            assertThat(wire.line()).isEqualTo("HTTP/1.1 100 Continue");
            assertThat(wire.line()).isEmpty();
            wire.write("hello");
            assertThat(wire.read(false).text()).isEqualTo("none|5|hello");
            wire.write("POST /echo HTTP/1.1\r\nHost: a\r\nExpect: 100-continue\r\nTransfer-Encoding: chunked\r\n\r\n");
            assertThat(wire.line()).isEqualTo("HTTP/1.1 100 Continue");
            assertThat(wire.line()).isEmpty();
            wire.write("2\r\nok\r\n0\r\n\r\n");
            assertThat(wire.read(false).text()).isEqualTo("none|2|ok");
        }
    }

    @Test void rejectsUnsatisfiableExpectationsWithoutAnInterimResponse() throws Exception {
        try (var fixture = echo(16)) {
            var server = fixture.listen();
            try (var wire = new Wire(server)) {
                wire.write("POST /echo HTTP/1.1\r\nHost: a\r\nExpect: 100-continue\r\nContent-Length: 17\r\n\r\n");
                assertThat(wire.read(false).status()).isEqualTo(413);
                assertThat(wire.socket.getInputStream().read()).isEqualTo(-1);
            }
            try (var wire = new Wire(server)) {
                wire.write("POST /echo HTTP/1.1\r\nHost: a\r\nExpect: something-else\r\nContent-Length: 5\r\n\r\n");
                assertThat(wire.read(false).status()).isEqualTo(417);
                assertThat(wire.socket.getInputStream().read()).isEqualTo(-1);
            }
        }
    }

    @Test void servesARequestPipelinedAfterABodyBearingRequest() throws Exception {
        try (var fixture = echo(16); var wire = new Wire(fixture.listen())) {
            wire.write("POST /echo HTTP/1.1\r\nHost: a\r\nContent-Length: 5\r\n\r\nhelloGET /next HTTP/1.1\r\nHost: a\r\n\r\n"
                    + "POST /echo HTTP/1.1\r\nHost: a\r\nTransfer-Encoding: chunked\r\n\r\n3\r\nabc\r\n0\r\n\r\n");
            assertThat(wire.read(false).text()).isEqualTo("none|5|hello");
            assertThat(wire.read(false).text()).isEqualTo("next");
            assertThat(wire.read(false).text()).isEqualTo("none|3|abc");
        }
    }

    @Test void countsASlowBodyAgainstTheRequestDeadline() throws Exception {
        var calls = new AtomicInteger();
        try (var fixture = echo(64)) {
            fixture.app.requestTimeout(Duration.ofMillis(300));
            fixture.app.post("/count", ctx -> { calls.incrementAndGet(); return "late"; });
            try (var wire = new Wire(fixture.listen())) {
                wire.write("POST /count HTTP/1.1\r\nHost: a\r\nContent-Length: 10\r\n\r\nabc");
                // The rest of the body never arrives; the read blocks until the deadline answers.
                var reply = wire.read(false);
                assertThat(reply.status()).isEqualTo(408);
                assertThat(wire.socket.getInputStream().read()).isEqualTo(-1);
            }
        }
        assertThat(calls).hasValue(0);
    }

    @Test void mapsBodyErrorsToTheSameProblemResponsesAsInMemoryCalls() throws Exception {
        try (var fixture = echo(16)) {
            fixture.app.post("/decode", ctx -> ctx.body(String.class));
            try (var wire = new Wire(fixture.listen())) {
                wire.write("POST /decode HTTP/1.1\r\nHost: a\r\nContent-Type: text/x-unknown\r\nContent-Length: 6\r\n\r\nPOISON");
                var unsupported = wire.read(false);
                assertThat(unsupported.status()).isEqualTo(415);
                assertThat(unsupported.headers()).containsEntry("content-type", "application/problem+json");
                assertThat(unsupported.text()).isEqualTo("{\"status\":415,\"code\":\"unsupported_media_type\",\"requestId\":\""
                        + unsupported.headers().get("X-Request-ID") + "\"}");
                wire.write("POST /decode HTTP/1.1\r\nHost: a\r\nContent-Length: 0\r\n\r\n");
                var empty = wire.read(false);
                assertThat(empty.status()).isEqualTo(400);
                assertThat(empty.text()).contains("\"code\":\"empty_body\"");
            }
        }
    }
}
