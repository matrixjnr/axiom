package example.modules;

import static org.assertj.core.api.Assertions.assertThat;

import com.jsgalactic.axiom.http.Response;
import com.jsgalactic.axiom.test.TestClient;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class GraphQlExampleTest {
  private static String text(Response response) {
    return response.body() instanceof String value
        ? value
        : new String((byte[]) response.body(), StandardCharsets.UTF_8);
  }

  @Test
  void answersQueriesAndReturnsOpaqueValidationProblems() throws Exception {
    try (var client = TestClient.start(GraphQlExample.create())) {
      var answer = client.post("/graphql", "application/json", "{\"query\":\"{ greeting }\"}");
      assertThat(answer.status()).isEqualTo(200);
      assertThat(text(answer)).isEqualTo("{\"data\":{\"greeting\":\"hello from graphql\"}}");

      var invalid = client.post("/graphql", "application/json", "{\"query\":\"{ missing }\"}");
      assertThat(invalid.status()).isEqualTo(400);
      assertThat(invalid.headers()).containsEntry("Content-Type", "application/problem+json");
      assertThat(text(invalid)).contains("\"code\":\"graphql_invalid_query\"");
    }
  }
}
