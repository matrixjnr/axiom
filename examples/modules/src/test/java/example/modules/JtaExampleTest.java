package example.modules;

import static org.assertj.core.api.Assertions.assertThat;

import com.jsgalactic.axiom.http.Response;
import com.jsgalactic.axiom.test.TestClient;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class JtaExampleTest {
  private static String text(Response response) {
    return response.body() instanceof String value
        ? value
        : new String((byte[]) response.body(), StandardCharsets.UTF_8);
  }

  @Test
  void commitsASuccessAndRollsBackAProblemResponse() throws Exception {
    var manager = new JtaExample.ExampleTransactionManager();
    try (var client = TestClient.start(JtaExample.create(manager))) {
      var created = client.post("/orders", "text/plain", "one");
      assertThat(created.status()).isEqualTo(200);
      assertThat(text(created)).isEqualTo("ACTIVE");
      assertThat(client.get("/conflict").status()).isEqualTo(409);
    }

    assertThat(manager.commits()).isEqualTo(1);
    assertThat(manager.rollbacks()).isEqualTo(1);
    assertThat(manager.events())
        .containsExactly("timeout:5", "begin", "commit", "timeout:5", "begin", "rollback");
  }
}
