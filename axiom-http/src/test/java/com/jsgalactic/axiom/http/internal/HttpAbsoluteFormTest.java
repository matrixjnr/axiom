package com.jsgalactic.axiom.http.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Absolute-form request targets over a real socket (RFC 9112 section 3.2.2). */
@Tag("integration")
class HttpAbsoluteFormTest {
    @Test void servesAbsoluteFormTargetsLikeOriginFormForEveryMethod() throws Exception {
        try (var fixture = new Fixture()) {
            fixture.app.get("/items/:id", ctx -> ctx.path("id") + ":" + ctx.query("q").orElse("none") + ":" + ctx.request().path());
            fixture.app.post("/items", ctx -> ctx.status(201).text("made"));
            fixture.app.get("/", ctx -> "root");
            try (var wire = new Wire(fixture.listen())) {
                wire.write("GET http://a/items/7?q=x HTTP/1.1\r\nHost: a\r\n\r\n");
                assertThat(wire.read(false).text()).isEqualTo("7:x:/items/7");
                wire.write("GET HTTPS://A:8443/items/8 HTTP/1.1\r\nHost: a:8443\r\n\r\n");
                assertThat(wire.read(false).text()).isEqualTo("8:none:/items/8");
                wire.write("POST http://a/items HTTP/1.1\r\nHost: a\r\nContent-Length: 0\r\n\r\n");
                assertThat(wire.read(false).status()).isEqualTo(201);
                wire.write("GET http://a HTTP/1.1\r\nHost: a\r\n\r\n");
                assertThat(wire.read(false).text()).isEqualTo("root");
                wire.write("HEAD http://a/ HTTP/1.1\r\nHost: a\r\n\r\n");
                assertThat(wire.read(true).status()).isEqualTo(200);
                wire.write("OPTIONS http://a/items HTTP/1.1\r\nHost: a\r\n\r\n");
                var options = wire.read(false);
                assertThat(options.status()).isEqualTo(204);
                assertThat(options.headers()).containsEntry("Allow", "OPTIONS, POST");
                // The authority is dropped, not routed on: an unknown path is still 404.
                wire.write("GET http://a/nothing HTTP/1.1\r\nHost: a\r\n\r\n");
                assertThat(wire.read(false).status()).isEqualTo(404);
            }
        }
    }

    @Test void acceptsAnAbsoluteFormTargetFromAnHttp10ClientWithoutHost() throws Exception {
        try (var fixture = new Fixture()) {
            fixture.app.get("/x", ctx -> "x");
            try (var wire = new Wire(fixture.listen())) {
                wire.write("GET http://a/x HTTP/1.0\r\n\r\n");
                assertThat(wire.read(false).text()).isEqualTo("x");
            }
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "GET http://other/x HTTP/1.1\r\nHost: a\r\n\r\n",
            "GET http://a:80/x HTTP/1.1\r\nHost: a\r\n\r\n",
            "GET http://u@a/x HTTP/1.1\r\nHost: a\r\n\r\n",
            "GET ftp://a/x HTTP/1.1\r\nHost: a\r\n\r\n",
            "GET http:///x HTTP/1.1\r\nHost: a\r\n\r\n",
            "GET http://a#f HTTP/1.1\r\nHost: a\r\n\r\n",
            "GET http://a/x#f HTTP/1.1\r\nHost: a\r\n\r\n",
            "GET http://a/../x HTTP/1.1\r\nHost: a\r\n\r\n",
            "GET http://a/%2e%2e/x HTTP/1.1\r\nHost: a\r\n\r\n",
            "GET http://a//x HTTP/1.1\r\nHost: a\r\n\r\n",
            "GET http://a/x%2Fy HTTP/1.1\r\nHost: a\r\n\r\n",
            "GET http://a/x HTTP/1.1\r\n\r\n",
            "GET http://a/x HTTP/1.1\r\nHost: a\r\nHost: a\r\n\r\n",
            "OPTIONS http://a/ HTTP/1.1\r\nHost: b\r\n\r\n"
    })
    void keepsRejectingUnsafeOrInconsistentAbsoluteFormTargets(String request) throws Exception {
        var calls = new AtomicInteger();
        try (var fixture = new Fixture()) {
            fixture.app.get("/x", ctx -> { calls.incrementAndGet(); return "x"; });
            fixture.app.get("/", ctx -> { calls.incrementAndGet(); return "root"; });
            try (var wire = new Wire(fixture.listen())) {
                wire.write(request);
                var reply = wire.read(false);
                assertThat(reply.status()).isEqualTo(400);
                assertThat(reply.headers()).containsEntry("Connection", "close");
            }
            assertThat(calls).hasValue(0);
        }
    }
}
