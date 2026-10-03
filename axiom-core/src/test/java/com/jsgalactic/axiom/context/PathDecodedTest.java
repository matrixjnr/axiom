package com.jsgalactic.axiom.context;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.jsgalactic.axiom.error.AxiomException;
import com.jsgalactic.axiom.error.BadRequestException;
import com.jsgalactic.axiom.execution.ExecutionContext;
import com.jsgalactic.axiom.http.Request;
import com.jsgalactic.axiom.http.Response;
import com.jsgalactic.axiom.routing.Route;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.Test;

/**
 * {@link Request} rejects encoded separators, dot segments, NUL and malformed escapes before a
 * route matches, so these inputs cannot reach {@link Context#pathDecoded} through an
 * application. The decoder keeps its own checks as defence in depth for contexts built over
 * other capture sources; this test reaches them with a context over a hand-built capture map.
 */
class PathDecodedTest {
    @ParameterizedTest
    @ValueSource(strings = {"a%2Fb", "a%2fb", "%2F", "a%5Cb", "a\\b", "a%00", "\u0000", ".", "..", "%2E", "%2e%2E",
            "a/%2E/b", "a/../b", "a/./b"})
    void rejectsCapturesThatDecodeToSeparatorsNulOrDotSegments(String raw) {
        assertRejected(raw);
    }

    @ParameterizedTest
    @ValueSource(strings = {"%", "a%", "a%4", "%zz", "%4g", "a/%2", "%FF", "%C3%28", "%ED%A0%80"})
    void rejectsMalformedEscapesAndUtf8(String raw) {
        assertRejected(raw);
    }

    @Test
    void undeclaredNamesRemainProgrammingErrors() {
        assertThatIllegalArgumentException().isThrownBy(() -> capture("a").pathDecoded("other"))
                .isNotInstanceOf(AxiomException.class);
    }

    /** Every decoding failure is a client error (400) with a fixed code that never echoes the input. */
    private static void assertRejected(String raw) {
        assertThatThrownBy(() -> capture(raw).pathDecoded("value"))
                .isInstanceOfSatisfying(BadRequestException.class, failure -> {
                    assertThat(failure.status()).isEqualTo(400);
                    assertThat(failure.code()).isEqualTo("invalid_path_encoding");
                    assertThat(failure.getMessage()).isEqualTo("400 invalid_path_encoding");
                });
    }

    @Test
    void decodesEachSegmentOnceAndKeepsRawSeparators() {
        assertThat(capture("a%20b/c%E2%82%AC/").pathDecoded("value")).isEqualTo("a b/c€/");
        assertThat(capture("%2541").pathDecoded("value")).isEqualTo("%41");
        assertThat(capture("..a/.b.").pathDecoded("value")).isEqualTo("..a/.b.");
        assertThat(capture("").pathDecoded("value")).isEmpty();
        assertThat(capture("😀+").pathDecoded("value")).isEqualTo("😀+");
    }

    private static Context capture(String raw) {
        return new CaptureContext(Map.of("value", raw));
    }

    /** Test-only context whose captures are not produced by the router. */
    private record CaptureContext(Map<String, String> captures) implements Context {
        @Override public Request request() { return Request.get("/"); }
        @Override public ExecutionContext execution() { return ExecutionContext.create(Duration.ofSeconds(1)); }
        @Override public <T> T body(Class<T> type) { throw new UnsupportedOperationException(); }
        @Override public Context status(int status) { return this; }
        @Override public Route route() { return new Route("GET", "/*value"); }
        @Override public String path(String name) {
            var value = captures.get(name);
            if (value == null) { throw new IllegalArgumentException("Undeclared capture"); }
            return value;
        }
        @Override public Map<String, String> pathParameters() { return captures; }
        @Override public Response response(Object body) { return Response.of(200, body); }
    }
}
