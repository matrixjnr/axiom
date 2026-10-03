package com.jsgalactic.axiom.openapi;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import com.jsgalactic.axiom.Axiom;
import com.jsgalactic.axiom.http.Request;
import com.jsgalactic.axiom.routing.RouteDoc;
import com.jsgalactic.axiom.test.TestClient;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;

class OpenApiTest {
    private static String golden(String name, String actual) throws IOException {
        var file = Path.of("src/test/resources/golden", name);
        if (System.getenv("AXIOM_UPDATE_GOLDEN") != null) {
            Files.createDirectories(file.getParent());
            Files.writeString(file, actual, StandardCharsets.UTF_8);
        }
        return Files.readString(file, StandardCharsets.UTF_8);
    }

    @Test void theOpenApiDocumentMatchesItsGoldenFile() throws IOException {
        var actual = Fixtures.notesConfig().render(Fixtures.notesApi());
        assertThat(actual).isEqualTo(golden("notes-openapi-3.1.json", actual));
    }

    @Test void theSwaggerDocumentMatchesItsGoldenFile() throws IOException {
        var actual = Fixtures.notesConfig().renderSwagger(Fixtures.notesApi());
        assertThat(actual).isEqualTo(golden("notes-swagger-2.0.json", actual));
    }

    @Test void outputIsDeterministicWhateverTheRegistrationOrder() {
        var forward = Axiom.create();
        forward.get("/a", ctx -> "x");
        forward.post("/a", ctx -> "x");
        forward.get("/b/:id", ctx -> "x");
        var backward = Axiom.create();
        backward.get("/b/:id", ctx -> "x");
        backward.post("/a", ctx -> "x");
        backward.get("/a", ctx -> "x");
        var config = OpenApi.builder("T", "1").build();

        assertThat(config.render(forward)).isEqualTo(config.render(backward));
        assertThat(config.render(forward)).isEqualTo(config.render(forward));
        assertThat(Fixtures.notesConfig().render(Fixtures.notesApi()))
                .isEqualTo(Fixtures.notesConfig().render(Fixtures.notesApi()));
    }

    @Test void routesWithoutMetadataAppearWithMethodAndPathOnly() {
        var app = Axiom.create();
        app.get("/plain/:id", ctx -> "x");
        var document = OpenApi.builder("T", "1").build().render(app);
        assertThat(document).contains("\"/plain/{id}\"").contains("\"get\"").contains("\"default\"")
                .contains("\"required\": true").doesNotContain("summary");
    }

    @Test void theDocumentLeaksNoPackageOrBinaryClassNames() {
        var document = Fixtures.notesConfig().render(Fixtures.notesApi())
                + Fixtures.notesConfig().renderSwagger(Fixtures.notesApi());
        assertThat(document).doesNotContain("com.jsgalactic").doesNotContainPattern("[A-Za-z]\\$[A-Za-z]").doesNotContain("Fixtures")
                .doesNotContain("java.util");
    }

    @Test void schemaNamesAreChosenWithTheBuilder() {
        var app = Axiom.create();
        app.describe(app.get("/n", ctx -> "x"), RouteDoc.empty().response(200, "ok", Fixtures.Note.class));
        var document = OpenApi.builder("T", "1").schemaName(Fixtures.Note.class, "PublicNote")
                .schemaName(Fixtures.Priority.class, "Level").build().render(app);
        assertThat(document).contains("\"PublicNote\"").contains("\"Level\"").doesNotContain("\"Note\"");
    }

    @Test void defaultsAddTagsSecurityResponsesAndParametersBelowAPrefix() {
        var app = Axiom.create();
        app.describe(app.get("/api/a", ctx -> "x"), RouteDoc.summary("a").tags("own").security("key")
                .response(401, "Own 401"));
        app.get("/api/b", ctx -> "x");
        app.get("/apix", ctx -> "x");
        var document = OpenApi.builder("T", "1")
                .securityScheme("bearer", SecurityScheme.bearer()).securityScheme("key", SecurityScheme.basic())
                .defaults("/api", RouteDoc.empty().tags("api").security("bearer").response(401, "Default 401")
                        .headerParam("X-Tenant", String.class, null, true))
                .build().render(app);

        assertThat(document).contains("\"own\"").contains("\"api\"").contains("Own 401")
                .contains("\"X-Tenant\"");
        assertThat(count(document, "Default 401")).isEqualTo(1);
        assertThat(count(document, "\"bearer\": []")).isEqualTo(1);
        assertThat(count(document, "\"key\": []")).isEqualTo(1);
        assertThat(count(document, "X-Tenant")).isEqualTo(2);
    }

    private static int count(String text, String part) {
        return text.split(java.util.regex.Pattern.quote(part), -1).length - 1;
    }

    // ---- failures at build time

    @Test void failuresNameTheRouteAndTheProblem() {
        var config = OpenApi.builder("T", "1").build();

        var unknownScheme = Axiom.create();
        unknownScheme.describe(unknownScheme.get("/x", ctx -> "x"), RouteDoc.empty().security("nope"));
        assertThatIllegalArgumentException().isThrownBy(() -> config.render(unknownScheme))
                .withMessageContaining("GET /x").withMessageContaining("security scheme 'nope'");

        var duplicate = Axiom.create();
        duplicate.describe(duplicate.get("/x", ctx -> "x"), RouteDoc.empty().operationId("same"));
        duplicate.describe(duplicate.get("/y", ctx -> "x"), RouteDoc.empty().operationId("same"));
        assertThatIllegalArgumentException().isThrownBy(() -> config.render(duplicate))
                .withMessageContaining("operation id 'same'");

        var strayPath = Axiom.create();
        strayPath.describe(strayPath.get("/x/:id", ctx -> "x"), RouteDoc.empty().pathParam("other", String.class, null));
        assertThatIllegalArgumentException().isThrownBy(() -> config.render(strayPath))
                .withMessageContaining("GET /x/:id").withMessageContaining("'other'");

        var method = Axiom.create();
        method.route("PROPFIND", "/dav", ctx -> "x");
        assertThatIllegalArgumentException().isThrownBy(() -> config.render(method))
                .withMessageContaining("PROPFIND").withMessageContaining("hidden()");
        method.describe(new com.jsgalactic.axiom.routing.Route("PROPFIND", "/dav"), RouteDoc.empty().hidden());
        assertThat(config.render(method)).doesNotContain("dav");
    }

    @Test void unknownTypesFailClearlyWhenTheDocumentIsBuilt() {
        var app = Axiom.create();
        app.describe(app.post("/x", ctx -> "x"), RouteDoc.empty().requestBody(Object.class));
        assertThatIllegalArgumentException().isThrownBy(() -> OpenApi.builder("T", "1").build().render(app))
                .withMessageContaining("POST /x").withMessageContaining("request body").withMessageContaining("Object is not a supported type");

        var thread = Axiom.create();
        thread.describe(thread.get("/t", ctx -> "x"), RouteDoc.empty().response(200, "t", Thread.class));
        assertThatIllegalArgumentException().isThrownBy(() -> OpenApi.builder("T", "1").build().render(thread))
                .withMessageContaining("GET /t").withMessageContaining("response 200");

        var record = Axiom.create();
        record.describe(record.get("/r", ctx -> "x"), RouteDoc.empty().response(200, "r", Holder.class));
        assertThatIllegalArgumentException().isThrownBy(() -> OpenApi.builder("T", "1").build().render(record))
                .withMessageContaining("Holder.value");
    }

    record Holder(Runnable value) { }

    @Test void serveRendersImmediatelySoAWrongDescriptionStopsStartup() {
        var app = Axiom.create();
        app.describe(app.post("/x", ctx -> "x"), RouteDoc.empty().requestBody(Object.class));
        assertThatIllegalArgumentException().isThrownBy(() -> OpenApi.builder("T", "1").build().serve(app, "/openapi.json"));
        assertThat(app.routes()).hasSize(1);
    }

    // ---- serving

    @Test void theDocumentIsServedOnlyWhenRegisteredAndIsCachedWithAnETag() throws Exception {
        var app = Fixtures.notesApi();
        try (var client = TestClient.start(Fixtures.notesApi())) {
            assertThat(client.get("/openapi.json").status()).isEqualTo(404);
        }
        var config = Fixtures.notesConfig();
        var openapi = config.serve(app, "/openapi.json");
        var swagger = config.serveSwagger(app, "/swagger.json");
        assertThat(openapi.path()).isEqualTo("/openapi.json");
        assertThat(swagger.path()).isEqualTo("/swagger.json");
        var expected = config.render(Fixtures.notesApi());

        try (var client = TestClient.start(app)) {
            var first = client.get("/openapi.json");
            assertThat(first.status()).isEqualTo(200);
            assertThat(first.headers()).containsEntry("Content-Type", "application/json")
                    .containsEntry("Cache-Control", "no-cache").containsKey("ETag");
            assertThat(new String((byte[]) first.body(), StandardCharsets.UTF_8)).isEqualTo(expected);
            assertThat(client.get("/openapi.json").body()).isEqualTo(first.body());

            var etag = first.headers().get("ETag");
            var revalidate = client.execute(new Request("GET", "/openapi.json", Map.of("If-None-Match", etag), com.jsgalactic.axiom.http.Body.empty()));
            assertThat(revalidate.status()).isEqualTo(304);
            var other = client.execute(new Request("GET", "/openapi.json", Map.of("If-None-Match", "\"zzz\""), com.jsgalactic.axiom.http.Body.empty()));
            assertThat(other.status()).isEqualTo(200);

            var swaggerResponse = client.get("/swagger.json");
            assertThat(new String((byte[]) swaggerResponse.body(), StandardCharsets.UTF_8)).contains("\"swagger\": \"2.0\"");
            assertThat(client.get("/openapi.json").body()).isNotEqualTo(swaggerResponse.body());
        }
        assertThat(expected).doesNotContain("/openapi.json");
    }

    @Test void middlewareProtectsTheDocumentRoute() throws Exception {
        var app = Axiom.create();
        OpenApi.builder("T", "1").build().serve(app, "/openapi.json",
                (ctx, next) -> ctx.header("X-Key").isPresent() ? next.run() : com.jsgalactic.axiom.http.Response.of(401, null));
        try (var client = TestClient.start(app)) {
            assertThat(client.get("/openapi.json").status()).isEqualTo(401);
        }
    }
}
