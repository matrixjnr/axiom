package com.jsgalactic.axiom.http.internal;

import static org.assertj.core.api.Assertions.assertThat;

import com.jsgalactic.axiom.error.ConflictException;
import java.util.List;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

@Tag("integration")
class HttpStatusMappingTest {
    static List<Arguments> transportFailures() {
        return List.of(
                Arguments.of("GET /" + "a".repeat(5000) + " HTTP/1.1\r\nHost: a\r\n\r\n", 414, "uri_too_long"),
                Arguments.of("GET / HTTP/1.1\r\nHost: a\r\nX-Large: " + "x".repeat(9000) + "\r\n\r\n", 431,
                        "request_header_fields_too_large"),
                // RFC 9112: without chunked as the final coding the body length is unknown, so 400.
                Arguments.of("POST /echo HTTP/1.1\r\nHost: a\r\nTransfer-Encoding: gzip\r\n\r\n", 400, "bad_request"),
                // A framed body with a coding the server does not implement.
                Arguments.of("POST /echo HTTP/1.1\r\nHost: a\r\nTransfer-Encoding: gzip, chunked\r\n\r\n", 501, "not_implemented"),
                Arguments.of("GET / HTTP/1.2\r\nHost: a\r\n\r\n", 505, "http_version_not_supported"),
                Arguments.of("POST /echo HTTP/1.1\r\nHost: a\r\nExpect: 200-ok\r\nContent-Length: 1\r\n\r\n", 417,
                        "expectation_failed"),
                Arguments.of("POST /echo HTTP/1.1\r\nHost: a\r\nContent-Length: 999999999\r\n\r\n", 413, "content_too_large"),
                Arguments.of("GET /a/../b HTTP/1.1\r\nHost: a\r\n\r\n", 400, "bad_request"),
                Arguments.of("GET / HTTP/1.1\r\nHost: a\r\nUpgrade: h2c\r\n\r\n", 501, "not_implemented"));
    }

    @ParameterizedTest(name = "[{index}] {1} {2}")
    @MethodSource("transportFailures")
    void answersTransportFailuresWithGenericProblemsAndCloses(String request, int status, String code) throws Exception {
        try (var fixture = new Fixture()) {
            fixture.app.post("/echo", ctx -> { throw new AssertionError("must not execute"); });
            fixture.app.put("/echo", ctx -> { throw new AssertionError("must not execute"); });
            try (var wire = new Wire(fixture.listen())) {
                wire.write(request);
                var reply = wire.read(false);
                assertThat(reply.status()).isEqualTo(status);
                assertProblem(reply, status, code);
                assertThat(reply.headers()).containsEntry("connection", "close");
                assertThat(wire.socket.getInputStream().read()).isEqualTo(-1);
            }
        }
    }

    @Test void answersRoutingAndNegotiationFailuresWithProblemsAndKeepsTheConnection() throws Exception {
        record Item(String name) { }
        try (var fixture = new Fixture()) {
            fixture.app.get("/items", ctx -> ctx.json(new Item("pen")));
            fixture.app.post("/items", ctx -> ctx.status(201).json(new Item("new")).withLocation("/items/1"));
            fixture.app.put("/conflict", ctx -> { throw new ConflictException("version_mismatch"); });
            try (var wire = new Wire(fixture.listen())) {
                assertProblem(wire.get("/missing"), 404, "not_found");
                wire.write("DELETE /items HTTP/1.1\r\nHost: a\r\n\r\n");
                var mismatch = wire.read(false);
                assertProblem(mismatch, 405, "method_not_allowed");
                assertThat(mismatch.headers()).containsEntry("allow", "GET, HEAD, POST");
                wire.write("GET /items HTTP/1.1\r\nHost: a\r\nAccept: text/html\r\n\r\n");
                assertProblem(wire.read(false), 406, "not_acceptable");
                wire.write("GET /items HTTP/1.1\r\nHost: a\r\nAccept: application/json\r\n\r\n");
                var ok = wire.read(false);
                assertThat(ok.status()).isEqualTo(200);
                assertThat(ok.headers()).containsEntry("content-type", "application/json");
                assertThat(ok.text()).isEqualTo("Item[name=pen]");
                wire.write("POST /items HTTP/1.1\r\nHost: a\r\nContent-Length: 0\r\n\r\n");
                var created = wire.read(false);
                assertThat(created.status()).isEqualTo(201);
                assertThat(created.headers()).containsEntry("location", "/items/1");
                wire.write("PUT /conflict HTTP/1.1\r\nHost: a\r\nContent-Length: 0\r\n\r\n");
                var conflict = wire.read(false);
                assertProblem(conflict, 409, "version_mismatch");
                assertThat(conflict.headers()).doesNotContainKey("connection");
                assertThat(wire.get("/items").status()).isEqualTo(200);
            }
        }
    }

    static void assertProblem(Reply reply, int status, String code) {
        assertThat(reply.status()).isEqualTo(status);
        assertThat(reply.headers()).containsEntry("content-type", "application/problem+json");
        assertThat(reply.text()).isEqualTo("{\"status\":" + status + ",\"code\":\"" + code + "\",\"requestId\":\""
                + reply.headers().get("X-Request-ID") + "\"}");
    }
}
