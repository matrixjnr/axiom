package io.axiom.http.internal;

import static org.assertj.core.api.Assertions.assertThat;

import io.axiom.http.Response;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/**
 * HEAD over a real socket. Each HEAD reply is read without a body and followed by another
 * request on the same connection, so any body byte sent after a HEAD response would corrupt
 * the next status line and fail the test.
 */
class HttpHeadTest {
    private static Fixture fixture(AtomicInteger calls) {
        var fixture = new Fixture();
        fixture.app.get("/items/:id", ctx -> { calls.incrementAndGet(); return "item " + ctx.path("id"); });
        fixture.app.post("/items/:id", ctx -> "posted");
        fixture.app.post("/submit", ctx -> "submitted");
        return fixture;
    }

    @Test void headUsesTheGetRouteAndSendsItsHeadersWithoutABody() throws Exception {
        var calls = new AtomicInteger();
        try (var fixture = fixture(calls); var wire = new Wire(fixture.listen())) {
            var get = wire.get("/items/7");
            assertThat(get.status()).isEqualTo(200);
            assertThat(get.text()).isEqualTo("item 7");

            wire.write("HEAD /items/7 HTTP/1.1\r\nHost: a\r\n\r\n");
            var head = wire.read(true);
            assertThat(head.status()).isEqualTo(200);
            assertThat(head.headers()).containsEntry("Content-Type", get.headers().get("Content-Type"));
            assertThat(head.headers()).containsKey("X-Request-ID").containsKey("Date");
            assertThat(head.headers().get("Connection")).isNotEqualToIgnoringCase("close");
            // The length the GET representation would have, without its bytes.
            assertThat(head.headers()).containsEntry("Content-Length", get.headers().get("Content-Length"));
            assertThat(calls).hasValue(2);

            // Keep-alive: the connection carries the next request, and no HEAD body bytes precede it.
            var after = wire.get("/items/8");
            assertThat(after.status()).isEqualTo(200);
            assertThat(after.text()).isEqualTo("item 8");
        }
    }

    @Test void headMethodMismatchIs405WithAllowAndKeepsTheConnection() throws Exception {
        try (var fixture = fixture(new AtomicInteger()); var wire = new Wire(fixture.listen())) {
            // POST only: HEAD is not implied, so it is a method mismatch.
            wire.write("HEAD /submit HTTP/1.1\r\nHost: a\r\n\r\n");
            var postOnly = wire.read(true);
            assertThat(postOnly.status()).isEqualTo(405);
            assertThat(postOnly.headers()).containsEntry("Allow", "POST");
            assertThat(postOnly.headers()).containsEntry("Content-Type", "application/problem+json");

            // GET registered: Allow lists HEAD beside GET.
            wire.write("PUT /items/7 HTTP/1.1\r\nHost: a\r\nContent-Length: 0\r\n\r\n");
            var mismatch = wire.read(false);
            assertThat(mismatch.status()).isEqualTo(405);
            assertThat(mismatch.headers()).containsEntry("Allow", "GET, HEAD, POST");

            wire.write("HEAD /missing HTTP/1.1\r\nHost: a\r\n\r\n");
            assertThat(wire.read(true).status()).isEqualTo(404);

            wire.write("HEAD /items/9 HTTP/1.1\r\nHost: a\r\n\r\n");
            assertThat(wire.read(true).status()).isEqualTo(200);
            assertThat(wire.get("/items/10").text()).isEqualTo("item 10");
        }
    }

    @Test void pipelinedHeadRequestsAreAnsweredInOrderWithoutBodies() throws Exception {
        try (var fixture = fixture(new AtomicInteger()); var wire = new Wire(fixture.listen())) {
            wire.write("HEAD /items/1 HTTP/1.1\r\nHost: a\r\n\r\n"
                    + "GET /items/2 HTTP/1.1\r\nHost: a\r\n\r\n"
                    + "HEAD /items/3 HTTP/1.1\r\nHost: a\r\n\r\n"
                    + "GET /items/4 HTTP/1.1\r\nHost: a\r\n\r\n");
            var first = wire.read(true);
            assertThat(first.status()).isEqualTo(200);
            assertThat(first.headers()).containsEntry("Content-Length", "6");
            assertThat(wire.read(false).text()).isEqualTo("item 2");
            assertThat(wire.read(true).headers()).containsEntry("Content-Length", "6");
            assertThat(wire.read(false).text()).isEqualTo("item 4");
        }
    }

    @Test void headAdvertisesTheEncodedLengthOfTheGetRepresentation() throws Exception {
        try (var fixture = new Fixture()) {
            // Two UTF-8 bytes per character: the length is counted in encoded bytes.
            fixture.app.get("/text", ctx -> "é".repeat(5));
            fixture.app.get("/bytes", ctx -> new byte[300]);
            fixture.app.get("/empty", ctx -> ctx.status(200).response(null));
            // An application-supplied length is replaced by the real one, as for GET.
            fixture.app.get("/claimed", ctx -> Response.of(200, "abc").withHeader("Content-Length", "999"));
            fixture.app.head("/explicit", ctx -> "head route");
            fixture.app.get("/no-content", ctx -> null);
            fixture.app.get("/reset", ctx -> Response.of(205, null));
            fixture.app.get("/created", ctx -> ctx.status(201).text("made"));
            try (var wire = new Wire(fixture.listen())) {
                for (var path : new String[] {"/text", "/bytes", "/empty", "/claimed", "/explicit", "/created"}) {
                    var get = path.equals("/explicit") ? null : wire.get(path);
                    wire.write("HEAD " + path + " HTTP/1.1\r\nHost: a\r\n\r\n");
                    var head = wire.read(true);
                    var expected = get == null ? "10" : String.valueOf(get.body().length);
                    assertThat(head.headers()).as(path).containsEntry("Content-Length", expected);
                }
                // Bodiless statuses keep their framing: no length on 204, and 205 keeps zero as for GET.
                wire.write("HEAD /no-content HTTP/1.1\r\nHost: a\r\n\r\n");
                var noContent = wire.read(true);
                assertThat(noContent.status()).isEqualTo(204);
                assertThat(noContent.headers()).doesNotContainKey("Content-Length");
                wire.write("HEAD /reset HTTP/1.1\r\nHost: a\r\n\r\n");
                var reset = wire.read(true);
                assertThat(reset.status()).isEqualTo(205);
                assertThat(reset.headers()).containsEntry("Content-Length", "0");
                // Errors keep their own framing: no length advertised for the problem body.
                wire.write("HEAD /missing HTTP/1.1\r\nHost: a\r\n\r\n");
                var missing = wire.read(true);
                assertThat(missing.status()).isEqualTo(404);
                assertThat(missing.headers()).doesNotContainKey("Content-Length");
                // The connection is still usable: nothing was written after the HEAD responses.
                assertThat(wire.get("/text").text()).isEqualTo("é".repeat(5));
            }
        }
    }

    @Test void headOfARepresentationTheTransportCannotSendIs500LikeGet() throws Exception {
        try (var fixture = new Fixture()) {
            fixture.app.get("/huge", ctx -> new byte[1024 * 1024 + 1]);
            var server = fixture.listen();
            try (var wire = new Wire(server)) {
                assertThat(wire.get("/huge").status()).isEqualTo(500);
            }
            try (var wire = new Wire(server)) {
                wire.write("HEAD /huge HTTP/1.1\r\nHost: a\r\n\r\n");
                assertThat(wire.read(true).status()).isEqualTo(500);
            }
        }
    }
}
