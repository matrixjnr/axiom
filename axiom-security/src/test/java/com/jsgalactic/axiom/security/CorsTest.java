package com.jsgalactic.axiom.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

import com.jsgalactic.axiom.Axiom;
import com.jsgalactic.axiom.http.Request;
import com.jsgalactic.axiom.http.Response;
import com.jsgalactic.axiom.test.TestClient;
import java.time.Duration;
import java.util.HashMap;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** CORS through the real router, including automatic OPTIONS. */
class CorsTest {
    private static final String APP = "https://app.example.com";

    private static Cors.Builder strict() {
        return Cors.builder().allowOrigin(APP).allowMethods("GET", "POST", "DELETE").allowHeaders("Content-Type", "Authorization")
                .exposeHeaders("X-Request-ID").allowCredentials().maxAge(Duration.ofMinutes(5));
    }

    private static TestClient client(Cors cors) {
        var app = Axiom.create();
        app.use(cors);
        app.get("/notes", ctx -> "notes");
        app.post("/notes", ctx -> "created");
        app.get("/vary", ctx -> Response.of(200, "v").withHeader("Vary", "Accept-Encoding"));
        app.put("/put-only", ctx -> "put");
        app.options("/custom", ctx -> Response.of(204, null).withHeader("X-Custom", "yes"));
        return TestClient.start(app);
    }

    private static Request request(String method, String path, String... headers) {
        var map = new HashMap<String, String>();
        for (int i = 0; i < headers.length; i += 2) { map.put(headers[i], headers[i + 1]); }
        return Request.fromTarget(method, path).withHeaders(map);
    }

    private static Request preflight(String path, String origin, String method, String headers) {
        return headers == null
                ? request("OPTIONS", path, "Origin", origin, "Access-Control-Request-Method", method)
                : request("OPTIONS", path, "Origin", origin, "Access-Control-Request-Method", method, "Access-Control-Request-Headers", headers);
    }

    private static void assertNoCorsHeaders(Response response) {
        assertThat(response.headers().keySet()).noneMatch(name -> name.toLowerCase().startsWith("access-control-"));
    }

    @Test
    void decoratesResponsesMappedFromExceptionsLikeOthers() throws Exception {
        var app = Axiom.create();
        app.use(strict().build());
        app.get("/denied", ctx -> { throw new com.jsgalactic.axiom.error.UnauthorizedException("Bearer realm=\"api\""); });
        try (var client = TestClient.start(app)) {
            var denied = client.execute(request("GET", "/denied", "Origin", APP));
            assertThat(denied.status()).isEqualTo(401);
            assertThat(denied.headers()).containsEntry("Access-Control-Allow-Origin", APP)
                    .containsEntry("Access-Control-Allow-Credentials", "true")
                    .containsEntry("Access-Control-Expose-Headers", "X-Request-ID").containsEntry("Vary", "Origin");
            var other = client.execute(request("GET", "/denied", "Origin", "https://evil.example.com"));
            assertThat(other.status()).isEqualTo(401);
            assertNoCorsHeaders(other);
            assertThat(other.headers()).containsEntry("Vary", "Origin");
        }
    }

    @Test
    void answersAnAllowedPreflightOnTopOfAutomaticOptions() throws Exception {
        try (var client = client(strict().build())) {
            var response = client.execute(preflight("/notes", APP, "POST", "content-type, Authorization"));
            assertThat(response.status()).isEqualTo(204);
            assertThat(response.headers()).containsEntry("Access-Control-Allow-Origin", APP)
                    .containsEntry("Access-Control-Allow-Methods", "GET, POST")
                    .containsEntry("Access-Control-Allow-Headers", "content-type, authorization")
                    .containsEntry("Access-Control-Allow-Credentials", "true")
                    .containsEntry("Access-Control-Max-Age", "300")
                    .containsEntry("Vary", "Origin, Access-Control-Request-Method, Access-Control-Request-Headers");
            assertThat(response.headers().get("Allow")).contains("POST").contains("OPTIONS"); // The router's answer is kept.
            var plain = client.execute(preflight("/notes", APP, "GET", null));
            assertThat(plain.headers()).containsEntry("Access-Control-Allow-Origin", APP).doesNotContainKey("Access-Control-Allow-Headers");
        }
    }

    @Test
    void refusesPreflightsFromOtherOriginsWithoutCorsHeaders() throws Exception {
        try (var client = client(strict().build())) {
            for (var origin : new String[] {"https://evil.test", "https://app.example.com.evil.test", "http://app.example.com",
                    "https://app.example.com:8443", "https://APP.example.com", "null", APP + ", https://evil.test", APP + "/"}) {
                var response = client.execute(preflight("/notes", origin, "GET", null));
                assertNoCorsHeaders(response);
                assertThat(response.headers()).containsEntry("Vary", "Origin, Access-Control-Request-Method, Access-Control-Request-Headers");
            }
        }
    }

    @Test
    void refusesUnconfiguredOrUnroutedMethodsAndHeaders() throws Exception {
        try (var client = client(strict().build())) {
            assertNoCorsHeaders(client.execute(preflight("/notes", APP, "DELETE", null))); // configured, but the route lacks it
            assertNoCorsHeaders(client.execute(preflight("/put-only", APP, "PUT", null))); // routed, but not configured
            assertNoCorsHeaders(client.execute(preflight("/notes", APP, "PATCH", null)));
            assertNoCorsHeaders(client.execute(preflight("/notes", APP, "get", null))); // methods are case-sensitive
            assertNoCorsHeaders(client.execute(preflight("/notes", APP, "GET", "X-Secret")));
            assertNoCorsHeaders(client.execute(preflight("/notes", APP, "GET", "Content-Type, X-Secret")));
            assertNoCorsHeaders(client.execute(preflight("/notes", APP, "GET", "Content-Type,,")));
            assertNoCorsHeaders(client.execute(preflight("/notes", APP, "GET", "Bad Header")));
            assertNoCorsHeaders(client.execute(preflight("/notes", APP, "GET", "*")));
            var manyHeaders = String.join(",", java.util.Collections.nCopies(65, "content-type"));
            assertNoCorsHeaders(client.execute(preflight("/notes", APP, "GET", manyHeaders)));
        }
    }

    @Test
    void neverSucceedsForAPathThatDoesNotExist() throws Exception {
        try (var client = client(strict().build())) {
            var response = client.execute(preflight("/missing", APP, "GET", null));
            assertThat(response.status()).isEqualTo(404);
            assertNoCorsHeaders(response);
        }
    }

    @Test
    void anOptionsRequestWithoutRequestMethodIsNotAPreflight() throws Exception {
        try (var client = client(strict().build())) {
            var response = client.execute(request("OPTIONS", "/notes", "Origin", APP));
            assertThat(response.status()).isEqualTo(204);
            assertThat(response.headers()).containsEntry("Access-Control-Allow-Origin", APP).doesNotContainKey("Access-Control-Allow-Methods");
        }
    }

    @Test
    void decoratesAnApplicationsOwnOptionsRoute() throws Exception {
        try (var client = client(strict().build())) {
            var response = client.execute(preflight("/custom", APP, "GET", null));
            assertThat(response.headers()).containsEntry("X-Custom", "yes").containsEntry("Access-Control-Allow-Origin", APP);
        }
    }

    @Test
    void worksWithAWildcardOptionsRouteThatDefersToTheAutomaticAnswer() throws Exception {
        var app = Axiom.create();
        app.use(strict().build());
        app.get("/notes", ctx -> "notes");
        app.options("/*any", ctx -> ctx.automaticOptions());
        try (var client = TestClient.start(app)) {
            var response = client.execute(preflight("/notes", APP, "GET", null));
            assertThat(response.status()).isEqualTo(204);
            assertThat(response.headers()).containsEntry("Access-Control-Allow-Origin", APP)
                    .containsEntry("Access-Control-Allow-Methods", "GET");
            assertNoCorsHeaders(client.execute(preflight("/notes", "https://evil.test", "GET", null)));
            // A method the path does not serve is not granted, even though the wildcard route answers.
            assertNoCorsHeaders(client.execute(preflight("/notes", APP, "POST", null)));
        }
    }

    @Test
    void decoratesActualRequestsFromAllowedOriginsOnly() throws Exception {
        try (var client = client(strict().build())) {
            var allowed = client.execute(request("GET", "/notes", "Origin", APP));
            assertThat(allowed.body()).isEqualTo("notes");
            assertThat(allowed.headers()).containsEntry("Access-Control-Allow-Origin", APP)
                    .containsEntry("Access-Control-Allow-Credentials", "true")
                    .containsEntry("Access-Control-Expose-Headers", "X-Request-ID").containsEntry("Vary", "Origin")
                    .doesNotContainKey("Access-Control-Allow-Methods");
            var denied = client.execute(request("GET", "/notes", "Origin", "https://evil.test"));
            assertThat(denied.body()).isEqualTo("notes"); // CORS is not authentication: the request still runs.
            assertNoCorsHeaders(denied);
            assertThat(denied.headers()).containsEntry("Vary", "Origin");
            var none = client.get("/notes");
            assertNoCorsHeaders(none);
            assertThat(none.headers()).containsEntry("Vary", "Origin"); // caches must not mix origin and non-origin answers
        }
    }

    @Test
    void keepsExistingVaryValues() throws Exception {
        try (var client = client(strict().build())) {
            assertThat(client.execute(request("GET", "/vary", "Origin", APP)).headers()).containsEntry("Vary", "Accept-Encoding, Origin");
        }
    }

    @Test
    void allowsSeveralExactOriginsAndEchoesOnlyTheRequestingOne() throws Exception {
        var cors = strict().allowOrigin("https://admin.example.com").allowOrigin("http://localhost:3000").allowOrigin("http://[::1]:3000").build();
        try (var client = client(cors)) {
            for (var origin : new String[] {APP, "https://admin.example.com", "http://localhost:3000", "http://[::1]:3000"}) {
                assertThat(client.execute(request("GET", "/notes", "Origin", origin)).headers())
                        .containsEntry("Access-Control-Allow-Origin", origin);
            }
        }
    }

    @Test
    void anyOriginAnswersWithAWildcardAndNoVaryWithoutCredentials() throws Exception {
        var cors = Cors.builder().anyOrigin().allowMethods("GET").build();
        try (var client = client(cors)) {
            var response = client.execute(request("GET", "/notes", "Origin", "https://anything.test"));
            assertThat(response.headers()).containsEntry("Access-Control-Allow-Origin", "*").doesNotContainKey("Vary")
                    .doesNotContainKey("Access-Control-Allow-Credentials");
            assertThat(client.execute(preflight("/notes", "https://anything.test", "GET", null)).headers())
                    .containsEntry("Access-Control-Allow-Origin", "*");
        }
    }

    @Test
    void omitsMaxAgeWhenZero() throws Exception {
        try (var client = client(strict().maxAge(Duration.ZERO).build())) {
            assertThat(client.execute(preflight("/notes", APP, "GET", null)).headers()).doesNotContainKey("Access-Control-Max-Age");
        }
    }

    // Misconfiguration is rejected when the middleware is built.

    @Test
    void rejectsAWildcardOriginWithCredentials() {
        assertThatIllegalStateException().isThrownBy(() -> Cors.builder().anyOrigin().allowCredentials().build())
                .withMessageContaining("credentials");
        assertThatIllegalStateException().isThrownBy(() -> Cors.builder().anyOrigin().allowCredentials().allowOrigin(APP).build());
    }

    @Test
    void rejectsAnEmptyOrAmbiguousOriginSet() {
        assertThatIllegalStateException().isThrownBy(() -> Cors.builder().build()).withMessageContaining("origin");
        assertThatIllegalStateException().isThrownBy(() -> Cors.builder().anyOrigin().allowOrigin(APP).build());
    }

    @ParameterizedTest
    @ValueSource(strings = {"*", "null", "", "app.example.com", "https://*.example.com", "https://app.example.com/", "https://app.example.com/x",
            "ftp://app.example.com", "https://user@app.example.com", "https://App.example.com", "https://app.example.com:0",
            "https://app.example.com:99999", "https://app.example.com ", "https://", "https://a b.test", "HTTPS://app.example.com",
            "https://app.example.com?x=1", "https://app.example.com#x", "https://app.example.com, https://b.test"})
    void rejectsOriginsThatAreNotExactSerializedOrigins(String origin) {
        assertThatIllegalArgumentException().isThrownBy(() -> Cors.builder().allowOrigin(origin));
    }

    @Test
    void rejectsWildcardsAndInvalidTokensInMethodsAndHeaders() {
        assertThatIllegalArgumentException().isThrownBy(() -> Cors.builder().allowMethods("*"));
        assertThatIllegalArgumentException().isThrownBy(() -> Cors.builder().allowMethods("get"));
        assertThatIllegalArgumentException().isThrownBy(() -> Cors.builder().allowMethods());
        assertThatIllegalArgumentException().isThrownBy(() -> Cors.builder().allowMethods("GET POST"));
        assertThatIllegalArgumentException().isThrownBy(() -> Cors.builder().allowHeaders("*"));
        assertThatIllegalArgumentException().isThrownBy(() -> Cors.builder().allowHeaders("Bad Header"));
        assertThatIllegalArgumentException().isThrownBy(() -> Cors.builder().allowHeaders("X\r\nSet-Cookie"));
        assertThatIllegalArgumentException().isThrownBy(() -> Cors.builder().exposeHeaders("*"));
        assertThatIllegalArgumentException().isThrownBy(() -> Cors.builder().maxAge(Duration.ofSeconds(-1)));
        assertThatIllegalArgumentException().isThrownBy(() -> Cors.builder().maxAge(Duration.ofHours(25)));
        assertThatIllegalArgumentException().isThrownBy(() -> Cors.builder().maxAge(Duration.ofMillis(1500)));
    }
}
