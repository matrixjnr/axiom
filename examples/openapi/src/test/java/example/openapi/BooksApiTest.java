package example.openapi;

import static org.assertj.core.api.Assertions.assertThat;

import com.jsgalactic.axiom.http.Body;
import com.jsgalactic.axiom.http.Request;
import com.jsgalactic.axiom.http.Response;
import com.jsgalactic.axiom.test.TestClient;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class BooksApiTest {
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

    @Test void theDocumentationIsServedOnlyToCallersWithTheKey() throws Exception {
        var key = UUID.randomUUID().toString();
        try (var client = TestClient.start(BooksApi.create(DocsAccess.requireKey(key)))) {
            assertThat(client.get("/openapi.json").status()).isEqualTo(401);
            assertThat(get(client, "/openapi.json", Map.of("X-Docs-Key", "wrong")).status()).isEqualTo(401);

            var openapi = get(client, "/openapi.json", Map.of("X-Docs-Key", key));
            assertThat(openapi.status()).isEqualTo(200);
            var document = text(openapi);
            assertThat(document).contains("\"openapi\": \"3.1.0\"").contains("\"operationId\": \"addBook\"")
                    .contains("\"maxLength\": 120").contains("\"minimum\": 1400").contains("\"maximum\": 2100")
                    .contains("\"Reader\"").contains("\"favourites\"").contains("\"active\"")
                    .contains("\"people\"").contains("Unexpected server error")
                    .doesNotContain("example.openapi").doesNotContain("/openapi.json");

            var swagger = text(get(client, "/swagger.json", Map.of("X-Docs-Key", key)));
            assertThat(swagger).contains("\"swagger\": \"2.0\"").contains("#/definitions/Book");
        }
    }
}
