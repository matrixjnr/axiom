package io.axiom.server;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import io.axiom.Axiom;
import io.axiom.http.Body;
import io.axiom.http.Request;
import io.axiom.routing.Route;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
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
    void answersOptionsForARoutedPathWithoutRunningAHandler() throws Exception {
        var calls = new AtomicInteger();
        try (var app = Axiom.create()) {
            app.get("/users", ctx -> { calls.incrementAndGet(); return "get"; });
            app.post("/users", ctx -> { calls.incrementAndGet(); return "post"; });
            app.route("PROPFIND", "/users", ctx -> { calls.incrementAndGet(); return "propfind"; });
            app.post("/submit", ctx -> { calls.incrementAndGet(); return "submit"; });
            app.start();
            var options = app.handle(new Request("OPTIONS", "/users"));
            assertThat(options.status()).isEqualTo(204);
            assertThat(options.body()).isNull();
            assertThat(options.headers()).containsExactly(Map.entry("Allow", "GET, HEAD, OPTIONS, POST, PROPFIND"));
            // HEAD is listed only where GET is registered.
            assertThat(app.handle(new Request("OPTIONS", "/submit")).headers())
                    .containsEntry("Allow", "OPTIONS, POST");
            assertThat(app.resolve(new Request("OPTIONS", "/users"))).isEmpty();
            assertThat(calls).hasValue(0);
        }
    }

    @Test
    void listsTheMethodsOfEveryTemplateMatchingTheOptionsTarget() throws Exception {
        try (var app = Axiom.create()) {
            app.get("/users/me", ctx -> "me");
            app.put("/users/:id", ctx -> "put");
            app.delete("/users/*rest", ctx -> "delete");
            app.start();
            assertThat(app.handle(new Request("OPTIONS", "/users/me")).headers())
                    .containsEntry("Allow", "DELETE, GET, HEAD, OPTIONS, PUT");
            assertThat(app.handle(new Request("OPTIONS", "/users/7")).headers())
                    .containsEntry("Allow", "DELETE, OPTIONS, PUT");
            assertThat(app.handle(new Request("OPTIONS", "/users/7/x")).headers())
                    .containsEntry("Allow", "DELETE, OPTIONS");
        }
    }

    @Test
    void anExplicitOptionsRouteWinsAndAnUnknownPathStays404() throws Exception {
        try (var app = Axiom.create()) {
            app.get("/users/me", ctx -> "me");
            app.options("/users/:id", ctx -> ctx.status(200).text("explicit " + ctx.path("id")));
            app.get("/plain", ctx -> "plain");
            app.start();
            // As for every method, the most specific template that has OPTIONS serves it.
            var explicit = app.handle(new Request("OPTIONS", "/users/me"));
            assertThat(explicit.status()).isEqualTo(200);
            assertThat(explicit.body()).isEqualTo("explicit me");
            assertThat(explicit.headers()).doesNotContainKey("Allow");
            assertThat(app.resolve(new Request("OPTIONS", "/users/me")))
                    .contains(new Route("OPTIONS", "/users/:id"));
            assertThat(app.handle(new Request("OPTIONS", "/plain")).status()).isEqualTo(204);
            var missing = app.handle(new Request("OPTIONS", "/missing"));
            assertThat(missing.status()).isEqualTo(404);
            assertThat(missing.headers()).doesNotContainKey("Allow");
        }
    }

    @Test
    void readsAndLimitsAnOptionsBodyLikeAnyOther() throws Exception {
        try (var app = Axiom.create()) {
            app.maxRequestBody(4);
            app.get("/x", ctx -> "x");
            app.start();
            var within = app.handle(new Request("OPTIONS", "/x", Map.of(), Body.of("text/plain", new byte[4])));
            assertThat(within.status()).isEqualTo(204);
            var over = app.handle(new Request("OPTIONS", "/x", Map.of(), Body.of("text/plain", new byte[5])));
            assertThat(over.status()).isEqualTo(413);
        }
    }

    @Test
    void answersOptionsAsteriskWithEveryRegisteredMethodWithoutRouteLookup() throws Exception {
        var calls = new AtomicInteger();
        try (var app = Axiom.create()) {
            app.get("/a", ctx -> { calls.incrementAndGet(); return "a"; });
            app.route("PROPFIND", "/b/:id", ctx -> { calls.incrementAndGet(); return "b"; });
            app.post("/c/*rest", ctx -> { calls.incrementAndGet(); return "c"; });
            // An OPTIONS route never serves the asterisk-form, even one matching every path.
            app.options("/*any", ctx -> { calls.incrementAndGet(); return "options"; });
            app.start();
            var asterisk = app.handle(Request.fromTarget("OPTIONS", "*"));
            assertThat(asterisk.status()).isEqualTo(204);
            assertThat(asterisk.body()).isNull();
            assertThat(asterisk.headers()).containsExactly(Map.entry("Allow", "GET, HEAD, OPTIONS, POST, PROPFIND"));
            assertThat(app.resolve(Request.fromTarget("OPTIONS", "*"))).isEmpty();
            assertThat(calls).hasValue(0);
        }
        try (var empty = Axiom.create()) {
            empty.start();
            assertThat(empty.handle(new Request("OPTIONS", "*")).headers()).containsEntry("Allow", "OPTIONS");
        }
    }

    @Test
    void refusesTheAsteriskFormAsARouteTemplate() {
        try (var app = Axiom.create()) {
            assertThatIllegalArgumentException().isThrownBy(() -> app.options("*", ctx -> "x"));
            assertThatIllegalArgumentException().isThrownBy(() -> app.get("*", ctx -> "x"));
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
