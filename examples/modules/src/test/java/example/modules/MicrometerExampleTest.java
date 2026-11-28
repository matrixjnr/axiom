package example.modules;

import static org.assertj.core.api.Assertions.assertThat;

import com.jsgalactic.axiom.test.TestClient;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class MicrometerExampleTest {
  @Test
  void publishesARequestCounterGaugeAndTimer() throws Exception {
    var registry = new SimpleMeterRegistry();
    var app = MicrometerExample.create(registry);
    var entered = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    app.get(
        "/blocked",
        ctx -> {
          entered.countDown();
          release.await();
          return ctx.text("done");
        });

    try (var client = TestClient.start(app)) {
      var held =
          java.util.concurrent.CompletableFuture.supplyAsync(
              () -> {
                try {
                  return client.get("/blocked");
                } catch (Exception failure) {
                  throw new IllegalStateException(failure);
                }
              });
      assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
      assertThat(registry.get("axiom.admission.active").gauge().value()).isEqualTo(1);
      release.countDown();
      assertThat(held.get(5, TimeUnit.SECONDS).status()).isEqualTo(200);

      assertThat(client.get("/work").status()).isEqualTo(200);
      assertThat(registry.get("axiom.http.requests").tag("route", "/work").counter().count())
          .isEqualTo(1);
      assertThat(registry.get("axiom.http.request.duration").tag("route", "/work").timer().count())
          .isEqualTo(1);
    } finally {
      release.countDown();
    }
  }
}
