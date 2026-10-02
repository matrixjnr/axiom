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

    @Test void clientStillSendingAnOversizedBodyReadsThe413AndIsNotReset() throws Exception {
        try (var fixture = echo(16)) {
            fixture.app.start();
            // A long linger keeps the test independent of how fast a loaded machine moves the upload.
            var server = NettyServer.bind(fixture.app, new java.net.InetSocketAddress("127.0.0.1", 0),
                    NettyServer.SHUTDOWN_GRACE, Duration.ofSeconds(60));
            fixture.servers.add(server);
            var wire = new Wire(server);
            // Below the discard cap, so the server never needs to cut the upload short.
            int length = 8 * 1024 * 1024;
            var piece = new byte[16 * 1024];
            // The head and the start of the body arrive together, so body bytes are unread when the 413 is sent.
            var head = ("POST /echo HTTP/1.1\r\nHost: a\r\nContent-Length: " + length + "\r\n\r\n")
                    .getBytes(StandardCharsets.US_ASCII);
            wire.socket.getOutputStream().write(java.util.Arrays.copyOf(head, head.length + piece.length));
            // Keeps sending the declared body without waiting, as many clients do.
            var failure = new java.util.concurrent.atomic.AtomicReference<IOException>();
            var sender = Thread.ofVirtual().start(() -> {
                try {
                    for (int sent = piece.length; sent < length; sent += piece.length) {
                        wire.socket.getOutputStream().write(piece);
                    }
                    wire.socket.shutdownOutput();
                } catch (IOException reset) { failure.set(reset); }
            });
            try {
                var reply = wire.read(false);
                assertThat(reply.status()).isEqualTo(413);
                assertThat(reply.headers()).containsEntry("connection", "close");
                assertThat(wire.socket.getInputStream().read()).isEqualTo(-1);
                sender.join();
                // The server kept reading and discarding the body instead of resetting the connection.
                assertThat(failure.get()).isNull();
            } finally {
                wire.close();
                sender.join();
                server.close();
            }
        }
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

    @Test void receivesOneByteChunksUpToTheLimitAndLargeDeclaredBodiesIntact() throws Exception {
        int limit = 256 * 1024;
        try (var fixture = new Fixture()) {
            fixture.app.maxRequestBody(4 * 1024 * 1024);
            // Each one-byte chunk is a tracked buffer under paranoid leak detection, so this takes
            // seconds even unloaded; the default ten-second deadline made it fail with 408 under load.
            fixture.app.requestTimeout(Duration.ofMinutes(2));
            fixture.app.post("/digest", ctx -> {
                var digest = java.security.MessageDigest.getInstance("SHA-256");
                return ctx.request().body().length() + ":" + java.util.HexFormat.of().formatHex(digest.digest(ctx.request().body().bytes()));
            });
            try (var wire = new Wire(fixture.listen())) {
                wire.socket.setSoTimeout(120_000);
                var chunked = new StringBuilder(limit * 6 + 128)
                        .append("POST /digest HTTP/1.1\r\nHost: a\r\nTransfer-Encoding: chunked\r\n\r\n");
                var expected = new byte[limit];
                for (int i = 0; i < limit; i++) {
                    expected[i] = (byte) ('a' + i % 26);
                    chunked.append("1\r\n").append((char) expected[i]).append("\r\n");
                }
                long started = System.nanoTime();
                wire.write(chunked.append("0\r\n\r\n").toString());
                var reply = wire.read(false);
                // Only a coarse guard against pathological per-chunk cost; copy counts are checked
                // deterministically by HttpConnectionTest.tinyChunksAreCopiedOnArrivalWithoutNettyAccumulation.
                assertThat(java.time.Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(100));
                assertThat(reply.text()).isEqualTo(limit + ":" + java.util.HexFormat.of().formatHex(
                        java.security.MessageDigest.getInstance("SHA-256").digest(expected)));

                var large = new byte[4 * 1024 * 1024];
                new java.util.Random(7).nextBytes(large);
                wire.write("POST /digest HTTP/1.1\r\nHost: a\r\nContent-Length: " + large.length + "\r\n\r\n");
                wire.socket.getOutputStream().write(large);
                assertThat(wire.read(false).text()).isEqualTo(large.length + ":" + java.util.HexFormat.of().formatHex(
                        java.security.MessageDigest.getInstance("SHA-256").digest(large)));
            }
        }
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"+5", "-1", "", "abc", "5 5", "0x5", "5.0", "1234567890123456789"})
    void rejectsMalformedContentLengthAndCloses(String value) throws Exception {
        try (var fixture = echo(16); var wire = new Wire(fixture.listen())) {
            wire.write("POST /echo HTTP/1.1\r\nHost: a\r\nContent-Length: " + value + "\r\n\r\nhello");
            var reply = wire.read(false);
            assertThat(reply.status()).isEqualTo(400);
            HttpStatusMappingTest.assertProblem(reply, 400, "bad_request");
            assertThat(wire.socket.getInputStream().read()).isEqualTo(-1);
        }
    }

    @Test void acceptsOptionalWhitespaceAroundContentLength() throws Exception {
        try (var fixture = echo(16); var wire = new Wire(fixture.listen())) {
            // RFC 9110 5.5: surrounding whitespace is not part of a field value.
            wire.write("POST /echo HTTP/1.1\r\nHost: a\r\nContent-Length:  5 \r\n\r\nhello");
            assertThat(wire.read(false).text()).isEqualTo("none|5|hello");
        }
    }

    @Test void rejectsTransferEncodingOnHttp10() throws Exception {
        try (var fixture = echo(16); var wire = new Wire(fixture.listen())) {
            wire.write("POST /echo HTTP/1.0\r\nTransfer-Encoding: chunked\r\n\r\n5\r\nhello\r\n0\r\n\r\n");
            assertThat(wire.read(false).status()).isEqualTo(400);
            assertThat(wire.socket.getInputStream().read()).isEqualTo(-1);
        }
    }

    @Test void treatsABodyMethodWithoutFramingAsAnEmptyBody() throws Exception {
        try (var fixture = echo(16); var wire = new Wire(fixture.listen())) {
            // RFC 9112 6.3: neither Content-Length nor Transfer-Encoding means a zero-length body.
            wire.write("POST /echo HTTP/1.1\r\nHost: a\r\n\r\nGET /next HTTP/1.1\r\nHost: a\r\n\r\n");
            assertThat(wire.read(false).text()).isEqualTo("none|0|");
            assertThat(wire.read(false).text()).isEqualTo("next");
            wire.write("PUT /echo HTTP/1.0\r\nConnection: keep-alive\r\n\r\n");
            assertThat(wire.read(false).status()).isEqualTo(405);
        }
    }
}
