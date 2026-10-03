package com.jsgalactic.axiom.openapi.ui;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

import com.jsgalactic.axiom.Axiom;
import com.jsgalactic.axiom.http.Body;
import com.jsgalactic.axiom.http.InvalidRequestPathException;
import com.jsgalactic.axiom.http.Request;
import com.jsgalactic.axiom.http.Response;
import com.jsgalactic.axiom.test.TestClient;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

class SwaggerUiTest {
    private static String text(Response response) {
        return new String((byte[]) response.body(), StandardCharsets.UTF_8);
    }

    private static TestClient start(SwaggerUi ui) {
        var app = Axiom.create();
        ui.register(app);
        return TestClient.start(app);
    }

    private static SwaggerUi single() {
        return SwaggerUi.builder().spec("OpenAPI 3.1", "/openapi.json").build();
    }

    private static Response send(TestClient client, String method, String path, Map<String, String> headers) throws Exception {
        return client.execute(new Request(method, path, headers, Body.empty()));
    }

    private static String assetPath(String page, String file) {
        var matcher = Pattern.compile("(/docs/assets/[0-9][^\"]*/" + Pattern.quote(file) + ")").matcher(page);
        assertThat(matcher.find()).as(file).isTrue();
        return matcher.group(1);
    }

    @Test void thePageHasNoInlineScriptAndReferencesOnlySameOriginResources() throws Exception {
        try (var client = start(single())) {
            var page = client.get("/docs");
            assertThat(page.status()).isEqualTo(200);
            var html = text(page);
            assertThat(page.headers()).containsEntry("Content-Type", "text/html; charset=utf-8");
            // every <script> has a src and no body; nothing inline, no handlers, no external hosts
            var scripts = Pattern.compile("<script([^>]*)>(.*?)</script>", Pattern.DOTALL).matcher(html);
            var count = 0;
            while (scripts.find()) {
                count++;
                assertThat(scripts.group(1)).contains("src=\"/docs/");
                assertThat(scripts.group(2)).isEmpty();
            }
            assertThat(count).isEqualTo(3);
            assertThat(html).doesNotContainPattern("(?i)\\son[a-z]+=").doesNotContain("javascript:")
                    .doesNotContain("http://").doesNotContain("https://").doesNotContain("//cdn")
                    .doesNotContainPattern("(?i)(src|href)=\"(?!/docs/)");
            // every referenced resource is served by this application
            var references = Pattern.compile("(?:src|href)=\"([^\"]+)\"").matcher(html);
            var seen = 0;
            while (references.find()) {
                seen++;
                var reference = client.get(references.group(1));
                assertThat(reference.status()).as(references.group(1)).isEqualTo(200);
            }
            assertThat(seen).isEqualTo(7);
        }
    }

    @Test void serverAndTrailingSlashServeThePage() throws Exception {
        try (var client = start(single())) {
            assertThat(client.get("/docs/").body()).isEqualTo(client.get("/docs").body());
        }
    }

    @Test void theInitializerNamesOnlyTheConfiguredDocuments() throws Exception {
        try (var client = start(SwaggerUi.builder().spec("OpenAPI 3.1", "/openapi.json")
                .spec("Swagger \"2.0\"", "/swagger.json").build())) {
            var script = text(client.get("/docs/swagger-initializer.js"));
            assertThat(script).contains("{url: \"/openapi.json\", name: \"OpenAPI 3.1\"}")
                    .contains("{url: \"/swagger.json\", name: \"Swagger \\\"2.0\\\"\"}")
                    .contains("\"urls.primaryName\": \"OpenAPI 3.1\"").doesNotContain("petstore")
                    .doesNotContain("http");
            assertThat(client.get("/docs/swagger-initializer.js").headers())
                    .containsEntry("Content-Type", "text/javascript; charset=utf-8");
        }
        try (var client = start(single())) {
            assertThat(text(client.get("/docs/swagger-initializer.js"))).contains("url: \"/openapi.json\",")
                    .doesNotContain("urls");
        }
    }

    @Test void assetsAreServedWithTheRightTypesAndImmutableCaching() throws Exception {
        try (var client = start(single())) {
            var html = text(client.get("/docs"));
            var expected = Map.of("swagger-ui.css", "text/css; charset=utf-8",
                    "swagger-ui-bundle.js", "text/javascript; charset=utf-8",
                    "swagger-ui-standalone-preset.js", "text/javascript; charset=utf-8",
                    "index.css", "text/css; charset=utf-8", "favicon-32x32.png", "image/png",
                    "favicon-16x16.png", "image/png");
            for (var entry : expected.entrySet()) {
                var response = client.get(assetPath(html, entry.getKey()));
                assertThat(response.status()).as(entry.getKey()).isEqualTo(200);
                assertThat(response.headers()).containsEntry("Content-Type", entry.getValue())
                        .containsEntry("Cache-Control", "public, max-age=31536000, immutable")
                        .containsEntry("X-Content-Type-Options", "nosniff").containsKey("ETag");
                assertThat((byte[]) response.body()).isNotEmpty();
            }
        }
    }

    @Test void everyResponseCarriesAStrictContentSecurityPolicy() throws Exception {
        try (var client = start(single())) {
            var html = text(client.get("/docs"));
            for (var path : List.of("/docs", "/docs/swagger-initializer.js", assetPath(html, "swagger-ui-bundle.js"))) {
                var csp = client.get(path).headers().get("Content-Security-Policy");
                assertThat(csp).as(path).startsWith("default-src 'none'; script-src 'self';")
                        .contains("connect-src 'self'").contains("frame-ancestors 'none'").contains("base-uri 'none'")
                        .doesNotContain("unsafe-eval").doesNotContain("http:").doesNotContain("https:")
                        .doesNotContain("*");
                assertThat(csp.split("script-src")[1].split(";")[0]).isEqualTo(" 'self'");
                assertThat(client.get(path).headers()).containsEntry("Referrer-Policy", "no-referrer");
            }
        }
    }

    @Test void pagesAndAssetsRevalidateWithEntityTags() throws Exception {
        try (var client = start(single())) {
            var page = client.get("/docs");
            assertThat(page.headers()).containsEntry("Cache-Control", "no-cache");
            var etag = page.headers().get("ETag");
            var same = send(client, "GET", "/docs", Map.of("If-None-Match", etag));
            assertThat(same.status()).isEqualTo(304);
            assertThat(same.headers()).containsEntry("ETag", etag).containsKey("Content-Security-Policy");
            assertThat(send(client, "GET", "/docs", Map.of("If-None-Match", "\"other\"")).status()).isEqualTo(200);
            var bundle = assetPath(text(page), "swagger-ui-bundle.js");
            var bundleEtag = client.get(bundle).headers().get("ETag");
            assertThat(send(client, "GET", bundle, Map.of("If-None-Match", bundleEtag + ", \"x\"")).status()).isEqualTo(304);
        }
    }

    @Test void pathTraversalAndUnknownNamesAreRejected() throws Exception {
        try (var client = start(single())) {
            var html = text(client.get("/docs"));
            var asset = assetPath(html, "swagger-ui.css");
            var version = asset.split("/")[3];
            var attempts = List.of(
                    "/docs/../etc/passwd", "/docs/assets/" + version + "/../../../etc/passwd",
                    "/docs/assets/%2e%2e/%2e%2e/etc/passwd", "/docs/assets/" + version + "/..%2f..%2fetc%2fpasswd",
                    "/docs/assets/" + version + "/%2e%2e%2fswagger-ui.css", "/docs/assets/" + version + "\\..\\x",
                    "/docs/assets/" + version + "/swagger-ui.css/", "/docs/assets/" + version + "/swagger-ui.css%00.png",
                    "/docs/assets/" + version + "/index.html", "/docs/assets/" + version + "/oauth2-redirect.js",
                    "/docs/assets/0.0.0/swagger-ui.css", "/docs/assets/" + version + "/", "/docs/assets", "/docs/assets/",
                    "/docs/META-INF/MANIFEST.MF", "/docs/index.html", "/docs//etc/passwd", "/docs/.hidden",
                    "/docs/swagger-ui.css", "/docsx", "/docs/swagger-initializer.js/x");
            for (var attempt : attempts) {
                Response response;
                try {
                    response = client.get(attempt);
                } catch (InvalidRequestPathException dotSegment) {
                    continue; // the request itself is refused (a listener answers 400)
                }
                assertThat(response.status()).as(attempt).isIn(400, 404);
                assertThat(text(response)).as(attempt).doesNotContain("root:").doesNotContain("Manifest");
            }
        }
    }

    @Test void headAndOptionsBehaveAsForAnyRoute() throws Exception {
        try (var client = start(single())) {
            var head = send(client, "HEAD", "/docs", Map.of());
            assertThat(head.status()).isEqualTo(200);
            assertThat(head.headers()).containsEntry("Content-Type", "text/html; charset=utf-8")
                    .containsKey("Content-Length");
            var options = send(client, "OPTIONS", "/docs", Map.of());
            assertThat(options.status()).isEqualTo(204);
            assertThat(options.headers().get("Allow")).isEqualTo("GET, HEAD, OPTIONS");
            assertThat(send(client, "POST", "/docs", Map.of()).status()).isEqualTo(405);
        }
    }

    @Test void middlewareGuardsEveryUiRouteAndTheUiIsAbsentUnlessRegistered() throws Exception {
        var guarded = Axiom.create();
        single().register(guarded, (ctx, next) -> ctx.header("X-Key").isPresent() ? next.run() : Response.of(401, null));
        try (var client = TestClient.start(guarded)) {
            for (var path : List.of("/docs", "/docs/", "/docs/swagger-initializer.js")) {
                assertThat(client.get(path).status()).as(path).isEqualTo(401);
            }
            assertThat(send(client, "GET", "/docs", Map.of("X-Key", "1")).status()).isEqualTo(200);
        }
        try (var client = TestClient.start(Axiom.create())) {
            assertThat(client.get("/docs").status()).isEqualTo(404);
        }
    }

    @Test void theUiCanLiveAtAnotherPathAndIsHiddenFromDocuments() throws Exception {
        var app = Axiom.create();
        var route = SwaggerUi.builder().path("/api-docs").title("Notes <docs>").spec("v1", "/v1/openapi.json").build()
                .register(app);
        assertThat(route.path()).isEqualTo("/api-docs");
        assertThat(app.doc(route).orElseThrow().isHidden()).isTrue();
        assertThat(app.routes()).extracting(r -> r.path()).containsExactlyInAnyOrder("/api-docs", "/api-docs/*rest");
        try (var client = TestClient.start(app)) {
            var html = text(client.get("/api-docs"));
            assertThat(html).contains("<title>Notes &lt;docs&gt;</title>").contains("src=\"/api-docs/swagger-initializer.js\"")
                    .doesNotContain("\"/docs/");
            assertThat(client.get("/docs").status()).isEqualTo(404);
        }
    }

    @Test void configurationIsValidated() {
        assertThatIllegalStateException().isThrownBy(() -> SwaggerUi.builder().build());
        for (var bad : List.of("docs", "/docs/", "/", "", "/a/:id", "/a/*b", "/a b", "/a?x")) {
            assertThatIllegalArgumentException().as(bad).isThrownBy(() -> SwaggerUi.builder().path(bad));
        }
        for (var bad : List.of("https://example.com/openapi.json", "//example.com/o.json", "openapi.json", "/o.json?x=\"",
                "/o\".json", "javascript:alert(1)")) {
            assertThatIllegalArgumentException().as(bad).isThrownBy(() -> SwaggerUi.builder().spec("x", bad));
        }
        assertThatIllegalArgumentException().isThrownBy(() -> SwaggerUi.builder().spec(" ", "/o.json"));
        assertThat(single().path()).isEqualTo("/docs");
    }
}
