package example.modules;

import static org.assertj.core.api.Assertions.assertThat;

import com.jsgalactic.axiom.test.TestClient;
import example.modules.persistence.FakePersistence;
import org.junit.jupiter.api.Test;

class JpaExampleTest {
  @Test
  void commitsOneRequestAndRollsBackTheNext() throws Exception {
    var persistence = new FakePersistence();
    try (var client = TestClient.start(JpaExample.create(persistence.factory()))) {
      assertThat(client.get("/items").status()).isEqualTo(200);
      assertThat(client.get("/conflict").status()).isEqualTo(409);
    }

    assertThat(persistence.events())
        .containsExactly(
            "em1.create",
            "em1.begin",
            "em1.commit",
            "em1.close",
            "em2.create",
            "em2.begin",
            "em2.rollback",
            "em2.close");
  }
}
