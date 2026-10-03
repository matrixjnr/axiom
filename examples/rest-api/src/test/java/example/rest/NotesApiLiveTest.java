package example.rest;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** The notes API on a real socket; the {@code TestClient} cases are in {@link NotesApiTest}. */
@Tag("integration")
class NotesApiLiveTest {
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
            assertThat(created.headers().firstValue("Location")).contains("/owners/grace/notes/1");
            assertThat(created.headers().firstValue("Server-Timing")).isPresent();
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
