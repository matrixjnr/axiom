package example.openapi;

import static org.assertj.core.api.Assertions.assertThat;

import com.jsgalactic.axiom.codec.spi.BodyCodec;
import com.jsgalactic.axiom.http.Body;
import com.jsgalactic.axiom.http.Request;
import com.jsgalactic.axiom.http.Response;
import com.jsgalactic.axiom.test.TestClient;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.ServiceLoader;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class BooksApiTest {
    @Test void bothDocumentsDescribeOneReaderOperationUnderTwoTags() throws Exception {
        var codec = ServiceLoader.load(BodyCodec.class).stream().map(ServiceLoader.Provider::get)
                .filter(candidate -> candidate.supports("application/json")).findFirst().orElseThrow();
        var password = UUID.randomUUID().toString();
        try (var client = TestClient.start(BooksApi.create(DocsAccess.requirePassword(password)))) {
            for (var path : List.of("/openapi.json", "/swagger.json")) {
                var response = get(client, path, basic("docs", password));
                assertThat(response.status()).as(path).isEqualTo(200);
                var document = codec.decode(ByteBuffer.wrap((byte[]) response.body()), Map.class);
                var paths = (Map<?, ?>) document.get("paths");
                var reader = (Map<?, ?>) paths.get("/readers/{name}");
                assertThat(reader.keySet()).as(path).hasSize(1);
                var operation = (Map<?, ?>) reader.get("put");
                assertThat(operation.get("operationId")).isEqualTo("saveReader");
                assertThat(operation.get("tags")).isEqualTo(List.of("readers", "people"));
            }
        }
    }

    private static String text(Response response) {
        return new String((byte[]) response.body(), StandardCharsets.UTF_8);
    }

    private static Response get(TestClient client, String path, Map<String, String> headers) throws Exception {
        return client.execute(new Request("GET", path, headers, Body.empty()));
    }

    @Test void theApiWorksWithoutAnyDocumentation() throws Exception {
        try (var client = TestClient.start(BooksApi.create(null))) {
            var created = client.post("/books", "application/json",
                    "{\"title\":\"Dune\",\"author\":\"Herbert\",\"year\":1965}");
            assertThat(created.status()).isEqualTo(201);
            assertThat(text(client.get("/books/1"))).contains("\"title\":\"Dune\"");
            assertThat(text(client.get("/books"))).startsWith("[{");
            assertThat(client.get("/books/7").status()).isEqualTo(404);
            var invalid = client.post("/books", "application/json", "{\"title\":\" \",\"author\":\"x\",\"year\":1}");
            assertThat(invalid.status()).isEqualTo(422);
            var saved = client.put("/readers/ada", "application/json",
                    "{\"name\":\"Ada\",\"active\":true,\"favourites\":[\"Dune\"]}");
            assertThat(text(saved)).contains("\"active\":true").contains("\"favourites\":[\"Dune\"]");
            // Documentation is not served unless it is registered.
            assertThat(client.get("/openapi.json").status()).isEqualTo(404);
            assertThat(client.get("/swagger.json").status()).isEqualTo(404);
        }
    }

    private static Map<String, String> basic(String user, String password) {
        var token = java.util.Base64.getEncoder().encodeToString((user + ":" + password).getBytes(StandardCharsets.UTF_8));
        return Map.of("Authorization", "Basic " + token);
    }

    @Test void theDocumentationIsServedOnlyToCallersWithThePassword() throws Exception {
        var password = UUID.randomUUID().toString();
        try (var client = TestClient.start(BooksApi.create(DocsAccess.requirePassword(password)))) {
            for (var path : java.util.List.of("/openapi.json", "/swagger.json", "/docs", "/docs/swagger-initializer.js")) {
                var anonymous = client.get(path);
                assertThat(anonymous.status()).as(path).isEqualTo(401);
                assertThat(anonymous.headers().get("WWW-Authenticate")).startsWith("Basic realm=\"docs\"");
                assertThat(get(client, path, basic("docs", "wrong")).status()).as(path).isEqualTo(401);
                assertThat(get(client, path, Map.of("Authorization", "Basic !!!")).status()).as(path).isEqualTo(401);
            }
            var credentials = basic("docs", password);

            var openapi = get(client, "/openapi.json", credentials);
            assertThat(openapi.status()).isEqualTo(200);
            var document = text(openapi);
            assertThat(document).contains("\"openapi\": \"3.1.0\"").contains("\"operationId\": \"addBook\"")
                    .contains("\"maxLength\": 120").contains("\"minimum\": 1400").contains("\"maximum\": 2100")
                    .contains("\"Reader\"").contains("\"favourites\"").contains("\"active\"")
                    .contains("\"people\"").contains("Unexpected server error")
                    .doesNotContain("example.openapi").doesNotContain("/openapi.json").doesNotContain("/docs");

            var swagger = text(get(client, "/swagger.json", credentials));
            assertThat(swagger).contains("\"swagger\": \"2.0\"").contains("#/definitions/Book");

            var page = get(client, "/docs", credentials);
            assertThat(page.status()).isEqualTo(200);
            assertThat(page.headers().get("Content-Security-Policy")).contains("script-src 'self'");
            assertThat(text(get(client, "/docs/swagger-initializer.js", credentials)))
                    .contains("{url: \"/openapi.json\", name: \"OpenAPI 3.1\"}")
                    .contains("{url: \"/swagger.json\", name: \"Swagger 2.0\"}");
        }
    }

    @Test void theUiAndDocumentsAreAbsentWhenDocumentationIsOff() throws Exception {
        try (var client = TestClient.start(BooksApi.create(null))) {
            assertThat(client.get("/docs").status()).isEqualTo(404);
            assertThat(client.get("/docs/swagger-initializer.js").status()).isEqualTo(404);
        }
    }
}
