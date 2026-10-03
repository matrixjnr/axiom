package com.jsgalactic.axiom.context;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.jsgalactic.axiom.error.BadRequestException;
import com.jsgalactic.axiom.execution.ExecutionContext;
import com.jsgalactic.axiom.http.Request;
import com.jsgalactic.axiom.http.Response;
import com.jsgalactic.axiom.routing.Route;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Typed query and path accessors: strict parsing, and a safe 400 for every conversion failure. */
class TypedAccessorsTest {
    private static final String ID = "123e4567-e89b-12d3-a456-426614174000";

    @Test
    void readsNumbersAndUuidsFromQueryAndPath() {
        var ctx = context("a=42&b=-7&c=007&d=9223372036854775807&e=-9223372036854775808&u=" + ID.toUpperCase(),
                Map.of("n", "42", "m", "-9223372036854775808", "id", ID));
        assertThat(ctx.queryInt("a")).contains(42);
        assertThat(ctx.queryInt("b")).contains(-7);
        assertThat(ctx.queryInt("c")).contains(7);
        assertThat(ctx.queryLong("d")).contains(Long.MAX_VALUE);
        assertThat(ctx.queryLong("e")).contains(Long.MIN_VALUE);
        assertThat(ctx.queryLong("a")).contains(42L);
        assertThat(ctx.queryUuid("u")).contains(UUID.fromString(ID));
        assertThat(ctx.pathInt("n")).isEqualTo(42);
        assertThat(ctx.pathLong("m")).isEqualTo(Long.MIN_VALUE);
        assertThat(ctx.pathUuid("id")).isEqualTo(UUID.fromString(ID));
    }

    @Test
    void absentQueryParametersAreEmptyAndTheFirstValueIsUsed() {
        var ctx = context("a=1&a=bad", Map.of());
        assertThat(ctx.queryInt("missing")).isEmpty();
        assertThat(ctx.queryLong("missing")).isEmpty();
        assertThat(ctx.queryUuid("missing")).isEmpty();
        assertThat(ctx.queryInt("a")).contains(1);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "-", "+1", " 1", "1 ", "1.0", "1e3", "0x10", "1_000", "--1", "1-", "１", "١",
            "2147483648", "-2147483649", "99999999999999999999", "000000000000000000000000001", "%31", "null"})
    void rejectsValuesThatAreNotIntsWithASafeCode(String value) {
        var ctx = context("n=" + enc(value), Map.of("n", value));
        assertSafe(() -> ctx.queryInt("n"), "invalid_query_parameter", value);
        assertSafe(() -> ctx.pathInt("n"), "invalid_path_parameter", value);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "-", "+1", "1.5", "9223372036854775808", "-9223372036854775809", "１",
            "1234567890123456789012", "abc"})
    void rejectsValuesThatAreNotLongsWithASafeCode(String value) {
        var ctx = context("n=" + enc(value), Map.of("n", value));
        assertSafe(() -> ctx.queryLong("n"), "invalid_query_parameter", value);
        assertSafe(() -> ctx.pathLong("n"), "invalid_path_parameter", value);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "1-1-1-1-1", "123e4567e89b12d3a456426614174000", "{" + ID + "}", ID + "0",
            "123e4567-e89b-12d3-a456-42661417400", "123e4567-e89b-12d3-a456-42661417400g",
            "123e4567-e89b-12d3-a456_426614174000", "+23e4567-e89b-12d3-a456-426614174000",
            "１" + "23e4567-e89b-12d3-a456-426614174000", "-123e4567-e89b-12d3-a456-42661417400"})
    void rejectsValuesThatAreNotCanonicalUuidsWithASafeCode(String value) {
        var ctx = context("u=" + enc(value), Map.of("u", value));
        assertSafe(() -> ctx.pathUuid("u"), "invalid_path_parameter", value);
        assertSafe(() -> ctx.queryUuid("u"), "invalid_query_parameter", value);
    }

    @Test
    void undeclaredPathNamesRemainProgrammingErrors() {
        var ctx = context("", Map.of());
        assertThatIllegalArgumentException().isThrownBy(() -> ctx.pathInt("other"))
                .isNotInstanceOf(BadRequestException.class);
        assertThatIllegalArgumentException().isThrownBy(() -> ctx.pathLong("other"));
        assertThatIllegalArgumentException().isThrownBy(() -> ctx.pathUuid("other"));
    }

    /** Every failure is a 400 with a fixed code and a message that never contains the input. */
    private static void assertSafe(org.assertj.core.api.ThrowableAssert.ThrowingCallable call, String code, String input) {
        assertThatThrownBy(call).isInstanceOfSatisfying(BadRequestException.class, failure -> {
            assertThat(failure.status()).isEqualTo(400);
            assertThat(failure.code()).isEqualTo(code);
            assertThat(failure.getMessage()).isEqualTo("400 " + code);
            assertThat(failure.getCause()).isNull();
            if (!input.isEmpty()) { assertThat(failure.toString()).doesNotContain(input); }
        });
    }

    private static String enc(String value) {
        return java.net.URLEncoder.encode(value, java.nio.charset.StandardCharsets.UTF_8);
    }

    private static Context context(String query, Map<String, String> captures) {
        return new Fake(Request.fromTarget("GET", "/x" + (query.isEmpty() ? "" : "?" + query)), captures);
    }

    private record Fake(Request request, Map<String, String> captures) implements Context {
        @Override public ExecutionContext execution() { return ExecutionContext.create(Duration.ofSeconds(1)); }
        @Override public <T> T body(Class<T> type) { throw new UnsupportedOperationException(); }
        @Override public Context status(int status) { return this; }
        @Override public Route route() { return new Route("GET", "/x"); }
        @Override public String path(String name) {
            var value = captures.get(name);
            if (value == null) { throw new IllegalArgumentException("Unknown path parameter: " + name); }
            return value;
        }
        @Override public Map<String, String> pathParameters() { return captures; }
        @Override public Response response(Object body) { return Response.of(200, body); }
    }
}
