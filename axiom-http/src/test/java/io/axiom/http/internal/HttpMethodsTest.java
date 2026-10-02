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
