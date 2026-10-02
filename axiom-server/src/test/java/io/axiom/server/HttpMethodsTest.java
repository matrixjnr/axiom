package io.axiom.server;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import io.axiom.Axiom;
import io.axiom.http.Request;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Method handling through the in-memory application: registration, matching and status codes. */
class HttpMethodsTest {
    @ParameterizedTest
    @ValueSource(strings = {"", " ", "GET ", " GET", "G T", "G\tT", "G(T", "G)T", "G\"T", "G,T", "G/T", "G:T",
            "G;T", "G<T", "G=T", "G>T", "G?T", "G@T", "G[T", "G\\T", "G]T", "G{T", "G}T", "G\u0000T",
            "G\r\nT", "G\u007fT", "GÉT", "G T"})
    void rejectsMethodsThatAreNotTokensAtRegistration(String method) {
        try (var app = Axiom.create()) {
            assertThatIllegalArgumentException().isThrownBy(() -> app.route(method, "/x", ctx -> "x"))
                    .withMessageStartingWith("Invalid HTTP method");
            assertThat(app.routes()).isEmpty();
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"PROPFIND", "M-SEARCH", "QUERY", "get", "X!#$%&'*+.^_`|~9"})
    void acceptsEveryTokenAsAMethodAndMatchesItCaseSensitively(String method) throws Exception {
        try (var app = Axiom.create()) {
            app.route(method, "/x", ctx -> ctx.method());
            app.start();
            assertThat(app.handle(new Request(method, "/x")).body()).isEqualTo(method);
        }
    }

    @Test
    void doesNotFoldTheCaseOfMethods() throws Exception {
        try (var app = Axiom.create()) {
            app.get("/x", ctx -> "upper");
            app.route("get", "/x", ctx -> "lower");
            app.start();
            assertThat(app.handle(new Request("GET", "/x")).body()).isEqualTo("upper");
            assertThat(app.handle(new Request("get", "/x")).body()).isEqualTo("lower");
            assertThat(app.handle(new Request("Get", "/x")).status()).isEqualTo(405);
        }
    }
}
