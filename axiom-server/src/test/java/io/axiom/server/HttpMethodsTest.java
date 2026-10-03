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
    void refusesTraceRoutes() {
        try (var app = Axiom.create()) {
            assertThatIllegalArgumentException().isThrownBy(() -> app.route("TRACE", "/x", ctx -> ctx.request()))
                    .withMessageContaining("TRACE");
            assertThatIllegalArgumentException().isThrownBy(() -> app.route("TRACE", "/*any", ctx -> "x"));
            assertThat(app.routes()).isEmpty();
        }
    }

    @Test
    void answersTraceWith405OnRoutedPathsAnd404ElsewhereWithoutReflectingTheRequest() throws Exception {
        try (var app = Axiom.create()) {
            app.get("/x", ctx -> "x");
            app.start();
            var request = new Request("TRACE", "/x", Map.of("Cookie", "session=secret"),
                    Body.of("text/plain", "body-secret".getBytes(java.nio.charset.StandardCharsets.UTF_8)));
            var trace = app.handle(request);
            assertThat(trace.status()).isEqualTo(405);
            assertThat(trace.headers()).containsEntry("Allow", "GET, HEAD");
            assertThat(new String((byte[]) trace.body(), java.nio.charset.StandardCharsets.UTF_8))
                    .doesNotContain("secret").doesNotContain("Cookie").doesNotContain("TRACE");
            assertThat(app.handle(new Request("TRACE", "/missing")).status()).isEqualTo(404);
        }
    }

    @Test
    void refusesConnectRoutesAndAnswersConnectWith501() throws Exception {
        try (var app = Axiom.create()) {
            assertThatIllegalArgumentException().isThrownBy(() -> app.route("CONNECT", "/x", ctx -> "x"))
                    .withMessageContaining("CONNECT");
            app.get("/x", ctx -> "x");
            app.start();
            for (var path : new String[] {"/x", "/missing"}) {
                var connect = app.handle(new Request("CONNECT", path));
                assertThat(connect.status()).as(path).isEqualTo(501);
                assertThat(connect.headers()).as(path).doesNotContainKey("Allow");
            }
            assertThat(app.resolve(new Request("CONNECT", "/x"))).isEmpty();
        }
    }

    @Test
    void routesCustomMethodsAndAnswersUnrecognizedMethodsOnUnroutedPathsWith501() throws Exception {
        try (var app = Axiom.create()) {
            app.get("/dav/:id", ctx -> "get");
            app.route("PROPFIND", "/dav/:id", ctx -> "propfind " + ctx.path("id"));
            app.route("REPORT", "/dav/:id", ctx -> "report");
            app.route("QUERY", "/search", ctx -> "query " + ctx.request().body().length());
            app.start();
            assertThat(app.handle(new Request("PROPFIND", "/dav/7")).body()).isEqualTo("propfind 7");
            assertThat(app.handle(new Request("QUERY", "/search", Map.of(),
                    Body.of("application/json", new byte[3]))).body()).isEqualTo("query 3");

            // A routed path: any other method, recognized or not, is 405 with Allow.
            for (var method : new String[] {"FOO", "PROPPATCH", "QUERY", "get", "DELETE"}) {
                var mismatch = app.handle(new Request(method, "/dav/7"));
                assertThat(mismatch.status()).as(method).isEqualTo(405);
                assertThat(mismatch.headers()).as(method).containsEntry("Allow", "GET, HEAD, PROPFIND, REPORT");
            }
            // An unrouted path: 404 for standard methods and methods registered anywhere, 501 otherwise.
            for (var method : new String[] {"GET", "HEAD", "POST", "PUT", "DELETE", "PATCH", "OPTIONS", "TRACE",
                    "PROPFIND", "REPORT", "QUERY"}) {
                assertThat(app.handle(new Request(method, "/missing")).status()).as(method).isEqualTo(404);
            }
            for (var method : new String[] {"FOO", "PROPPATCH", "get", "Get", "query", "M-SEARCH"}) {
                var unknown = app.handle(new Request(method, "/missing"));
                assertThat(unknown.status()).as(method).isEqualTo(501);
                assertThat(unknown.headers()).as(method).doesNotContainKey("Allow")
                        .containsEntry("Content-Type", "application/problem+json");
                assertThat(new String((byte[]) unknown.body(), java.nio.charset.StandardCharsets.UTF_8))
                        .contains("\"code\":\"not_implemented\"").doesNotContain(method);
            }
            assertThat(app.resolve(new Request("FOO", "/missing"))).isEmpty();
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
