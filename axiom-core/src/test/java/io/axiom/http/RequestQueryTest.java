package io.axiom.http;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class RequestQueryTest {
    @Test
    void returnsFirstAndAllDecodedValuesInRequestOrder() {
        var request = Request.fromTarget("GET", "/search?q=a+b&tag=x&tag=y%20z&q=second&empty=&flag&&");
        assertThat(request.query("q")).contains("a b");
        assertThat(request.queryAll("q")).containsExactly("a b", "second");
        assertThat(request.queryAll("tag")).containsExactly("x", "y z");
        assertThat(request.query("empty")).contains("");
        assertThat(request.query("flag")).contains("");
        assertThat(request.query("missing")).isEmpty();
        assertThat(request.queryAll("missing")).isEmpty();
        assertThatThrownBy(() -> request.queryAll("q").add("x")).isInstanceOf(UnsupportedOperationException.class);
        assertThatNullPointerException().isThrownBy(() -> request.query(null));
    }

    @Test
    void decodesNamesAndValuesOnceAsUtf8WithPlusAsSpace() {
        var request = Request.fromTarget("GET", "/?na%6De=%E2%82%AC&plus=%2B&twice=%2541&eq=a=b&slash=a/b?c&raw=é");
        assertThat(request.query("name")).contains("€");
        assertThat(request.query("na%6De")).isEmpty();
        assertThat(request.query("plus")).contains("+");
        assertThat(request.query("twice")).contains("%41");
        assertThat(request.query("eq")).contains("a=b");
        assertThat(request.query("slash")).contains("a/b?c");
        assertThat(request.query("raw")).contains("é");
        assertThat(Request.fromTarget("GET", "/?a+b=1").query("a b")).contains("1");
    }

    @Test
    void emptyAndAbsentQueriesHaveNoParameters() {
        assertThat(Request.get("/").query()).isEmpty();
        assertThat(Request.fromTarget("GET", "/?").query("")).isEmpty();
        assertThat(Request.fromTarget("GET", "/?&&").queryAll("")).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"q=%zz", "q=%2", "q=%", "%G1=x", "q=x y", "q=a#b", "q=\\", "q=\u0001", "q=%FF",
            "q=%C3%28", "%C3=x", "q=%ED%A0%80", "q=%C0%AF", "q=\uD800"})
    void rejectsMalformedQueriesWithoutEchoingThem(String query) {
        assertThatIllegalArgumentException().isThrownBy(() -> Request.fromTarget("GET", "/a?" + query))
                .satisfies(failure -> assertThat(failure.getMessage()).doesNotContain(query));
        assertThatIllegalArgumentException().isThrownBy(() -> new Request("GET", "/a", query, Map.of(), Body.empty()));
    }

    @Test
    void enforcesLengthAndParameterLimits() {
        var longest = "q=" + "x".repeat(Request.MAX_QUERY_LENGTH - 2);
        assertThat(Request.fromTarget("GET", "/?" + longest).query("q")).isPresent();
        assertThatIllegalArgumentException().isThrownBy(() -> Request.fromTarget("GET", "/?" + longest + "x"))
                .satisfies(failure -> assertThat(failure.getMessage()).doesNotContain("xxxx"));
        var most = String.join("&", java.util.Collections.nCopies(Request.MAX_QUERY_PARAMETERS, "a=1"));
        assertThat(Request.fromTarget("GET", "/?" + most).queryAll("a")).hasSize(Request.MAX_QUERY_PARAMETERS);
        // Empty pairs do not count against the parameter limit.
        assertThat(Request.fromTarget("GET", "/?" + most + "&&&").queryAll("a")).hasSize(Request.MAX_QUERY_PARAMETERS);
        assertThatIllegalArgumentException().isThrownBy(() -> Request.fromTarget("GET", "/?" + most + "&b"));
    }

    @Test
    void copiesKeepTheQueryAndToStringOmitsIt() {
        var request = Request.fromTarget("POST", "/notes?token=secret-value")
                .withHeaders(Map.of("Accept", "text/plain"))
                .withBody(Body.of("text/plain", new byte[] {'x'}));
        assertThat(request.query()).isEqualTo("token=secret-value");
        assertThat(request.query("token")).contains("secret-value");
        assertThat(request.toString()).doesNotContain("secret-value").doesNotContain("token");
        assertThat(new Request("GET", "/", "a=1", Map.of(), Body.empty()).queryAll("a")).isEqualTo(List.of("1"));
        assertThatNullPointerException().isThrownBy(() -> new Request("GET", "/", null, Map.of(), Body.empty()));
    }
}
