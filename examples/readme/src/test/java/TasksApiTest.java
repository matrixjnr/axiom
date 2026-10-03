import static org.assertj.core.api.Assertions.assertThat;

import com.jsgalactic.axiom.test.TestClient;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

/** Runs the README's tasks API without a socket; the README quotes the marked region. */
class TasksApiTest {
    @Test void readmeSnippet() throws Exception {
        // region tested
        try (var client = TestClient.start(TasksApi.create())) {
            var created = client.post("/tasks", "application/json", "{\"title\":\"Buy milk\"}");
            assertThat(created.status()).isEqualTo(201);
            assertThat(client.get("/tasks/42").status()).isEqualTo(404);   // problem+json, code task_not_found
        }
        // endregion tested
    }

    @Test void validatesAndFiltersTasks() throws Exception {
        try (var client = TestClient.start(TasksApi.create())) {
            assertThat(client.post("/tasks", "application/json", "{\"title\":\" \"}").status()).isEqualTo(422);
            client.post("/tasks", "application/json", "{\"title\":\"Buy milk\"}");
            client.post("/tasks", "application/json", "{\"title\":\"Walk\"}");
            var found = client.get("/tasks?q=milk");
            assertThat(found.status()).isEqualTo(200);
            assertThat(found.headers()).containsEntry("X-Content-Type-Options", "nosniff");
            assertThat(new String((byte[]) found.body(), StandardCharsets.UTF_8))
                    .contains("Buy milk").doesNotContain("Walk");
        }
    }
}
