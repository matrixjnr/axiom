import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** Starts the README's tasks API on a real socket (an ephemeral port, unlike its main method). */
@Tag("integration")
class TasksApiLiveTest {
    @Test void servesTheTasksApiOverHttp() throws Exception {
        var app = TasksApi.create();
        try (var http = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(5)).build()) {
            var server = app.listen(0);
            var base = "http://127.0.0.1:" + server.localAddress().getPort();
            var created = http.send(HttpRequest.newBuilder(URI.create(base + "/tasks"))
                    .timeout(Duration.ofSeconds(5)).header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString("{\"title\":\"Buy milk\"}")).build(),
                    HttpResponse.BodyHandlers.ofString());
            assertThat(created.statusCode()).isEqualTo(201);
            assertThat(created.headers().firstValue("Location")).contains("/tasks/1");
            assertThat(created.body()).isEqualTo("{\"id\":\"1\",\"title\":\"Buy milk\"}");
            var missing = http.send(HttpRequest.newBuilder(URI.create(base + "/tasks/42"))
                    .timeout(Duration.ofSeconds(5)).build(), HttpResponse.BodyHandlers.ofString());
            assertThat(missing.statusCode()).isEqualTo(404);
            assertThat(missing.body()).contains("\"code\":\"task_not_found\"");
            app.close();
            server.termination().toCompletableFuture().get(10, TimeUnit.SECONDS);
        } finally { app.close(); }
    }
}
