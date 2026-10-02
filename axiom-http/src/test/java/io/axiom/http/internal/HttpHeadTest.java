package io.axiom.http.internal;

import static org.assertj.core.api.Assertions.assertThat;

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
            // A HEAD response must never advertise a length different from the GET representation.
            assertThat(head.headers().get("Content-Length")).isIn(null, get.headers().get("Content-Length"));
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
            assertThat(wire.read(true).status()).isEqualTo(200);
            assertThat(wire.read(false).text()).isEqualTo("item 2");
            assertThat(wire.read(true).status()).isEqualTo(200);
            assertThat(wire.read(false).text()).isEqualTo("item 4");
        }
    }
}
