package example.rest;

import static org.assertj.core.api.Assertions.assertThat;

import io.axiom.http.Response;
import io.axiom.test.TestClient;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class NotesApiTest {
    private static String text(Response response) {
        return new String((byte[]) response.body(), StandardCharsets.UTF_8);
    }

    @Test void createsAndReadsNotes() throws Exception {
        try (var client = TestClient.start(NotesApi.create())) {
            var created = client.post("/owners/ada/notes", "application/json", "{\"title\":\"Plan\",\"text\":\"Ship it\"}");
            assertThat(created.status()).isEqualTo(201);
            assertThat(created.headers()).containsEntry("Location", "/notes/1").containsEntry("Content-Type", "application/json");
            assertThat(text(created)).isEqualTo("{\"id\":1,\"owner\":\"ada\",\"title\":\"Plan\",\"text\":\"Ship it\"}");
            assertThat(text(client.get("/notes/1"))).contains("\"title\":\"Plan\"");
        }
    }

    @Test void reportsClientErrorsWithoutEchoingInput() throws Exception {
        try (var client = TestClient.start(NotesApi.create())) {
            var invalid = client.post("/owners/ada/notes", "application/json", "{\"title\":\" \"}");
            assertThat(invalid.status()).isEqualTo(422);
            assertThat(text(invalid)).contains("\"violations\":[{\"field\":\"title\",\"code\":\"required\"}]");
            var unknown = client.post("/owners/ada/notes", "application/json", "{\"title\":\"x\",\"<script>\":1}");
            assertThat(unknown.status()).isEqualTo(400);
            assertThat(text(unknown)).contains("\"code\":\"unknown_field\"").doesNotContain("script");
            assertThat(client.post("/owners/ada/notes", "text/plain", "title").status()).isEqualTo(415);
            assertThat(client.post("/owners/ada/notes", "application/json", "x".repeat(16 * 1024 + 1)).status())
                    .isEqualTo(413);
            var missing = client.get("/notes/42");
            assertThat(missing.status()).isEqualTo(404);
            assertThat(text(missing)).contains("\"code\":\"note_not_found\"");
        }
    }

    @Test void servesTheSameApiOverHttp() throws Exception {
        var app = NotesApi.create();
        try (var http = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(5)).build()) {
            var server = app.listen(0);
            var base = "http://127.0.0.1:" + server.localAddress().getPort();
            var created = http.send(HttpRequest.newBuilder(URI.create(base + "/owners/grace/notes"))
                    .timeout(Duration.ofSeconds(5)).header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString("{\"title\":\"Wire\",\"text\":null}")).build(),
                    HttpResponse.BodyHandlers.ofString());
            assertThat(created.statusCode()).isEqualTo(201);
            assertThat(created.headers().firstValue("Location")).contains("/notes/1");
            assertThat(created.body()).isEqualTo("{\"id\":1,\"owner\":\"grace\",\"title\":\"Wire\",\"text\":null}");
            var malformed = http.send(HttpRequest.newBuilder(URI.create(base + "/owners/grace/notes"))
                    .timeout(Duration.ofSeconds(5)).header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString("{\"title\":")).build(), HttpResponse.BodyHandlers.ofString());
            assertThat(malformed.statusCode()).isEqualTo(400);
            assertThat(malformed.headers().firstValue("Content-Type")).contains("application/problem+json");
            assertThat(malformed.body()).contains("\"code\":\"malformed_json\"");
            app.close();
            server.termination().toCompletableFuture().get(10, TimeUnit.SECONDS);
        } finally { app.close(); }
    }
}
