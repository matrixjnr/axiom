package example.modules;

import static org.assertj.core.api.Assertions.assertThat;

import com.jsgalactic.axiom.http.Response;
import com.jsgalactic.axiom.test.TestClient;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class JdbcExampleTest {
  private static String text(Response response) {
    return response.body() instanceof String value
        ? value
        : new String((byte[]) response.body(), StandardCharsets.UTF_8);
  }

  @Test
  void servesWorkUntilTheOnlyPermitIsHeld() throws Exception {
    var entered = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    var source = new JdbcExample.ExampleDataSource();
    var app =
        JdbcExample.create(
            source,
            ctx -> {
              entered.countDown();
              release.await();
              return ctx.text("held");
            });

    try (var client = TestClient.start(app)) {
      var held =
          CompletableFuture.supplyAsync(
              () -> {
                try {
                  return client.get("/orders");
                } catch (Exception failure) {
                  throw new IllegalStateException(failure);
                }
              });
      assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
      var refused = client.get("/orders");
      assertThat(refused.status()).isEqualTo(503);
      assertThat(refused.headers()).containsEntry("Content-Type", "application/problem+json");
      assertThat(text(refused)).contains("\"code\":\"db_capacity\"");
      release.countDown();
      assertThat(held.get(5, TimeUnit.SECONDS).status()).isEqualTo(200);
    } finally {
      release.countDown();
    }
  }
}
