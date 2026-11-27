package com.jsgalactic.axiom.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.jsgalactic.axiom.Axiom;
import com.jsgalactic.axiom.execution.AdmissionPolicy;
import com.jsgalactic.axiom.execution.ExecutionBudget;
import com.jsgalactic.axiom.lifecycle.Server;
import com.jsgalactic.axiom.test.TestClient;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** The application's admission budget bounds every listener and test client together. */
@Tag("integration")
class SharedBudgetIntegrationTest {
  @Test
  void twoListenersShareOneActiveBound() throws Exception {
    var entered = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    try (var app = Axiom.create()) {
      app.admissionPolicy(AdmissionPolicy.reject(1));
      app.get(
          "/hold",
          ctx -> {
            entered.countDown();
            assertThat(release.await(10, TimeUnit.SECONDS)).isTrue();
            return "done";
          });
      var first = app.listen(0);
      var second = app.listen(0);
      var client = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();
      try {
        var held = get(client, first, "/hold");
        assertThat(entered.await(10, TimeUnit.SECONDS)).isTrue();
        assertThat(send(client, second, "/hold").statusCode()).isEqualTo(503);
        release.countDown();
        assertThat(joined(held).statusCode()).isEqualTo(200);
        assertThat(send(client, second, "/hold").statusCode()).isEqualTo(200);
      } finally {
        release.countDown();
      }
    }
  }

  @Test
  void routeOverridesAreSharedAcrossListenersAndDoNotConsumeUnrelatedCapacity() throws Exception {
    var entered = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    try (var app = Axiom.create()) {
      var slow =
          app.get(
              "/slow",
              ctx -> {
                entered.countDown();
                assertThat(release.await(10, TimeUnit.SECONDS)).isTrue();
                return "slow";
              });
      app.get("/free", ctx -> "free");
      app.executionBudget(
          new ExecutionBudget(AdmissionPolicy.reject(3), Map.of(slow, AdmissionPolicy.reject(1))));
      var first = app.listen(0);
      var second = app.listen(0);
      var client = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();
      try {
        var held = get(client, first, "/slow");
        assertThat(entered.await(10, TimeUnit.SECONDS)).isTrue();
        assertThat(send(client, second, "/slow").statusCode()).isEqualTo(503);
        assertThat(send(client, second, "/free").statusCode()).isEqualTo(200);
        release.countDown();
        assertThat(joined(held).statusCode()).isEqualTo(200);
        assertThat(send(client, second, "/slow").statusCode()).isEqualTo(200);
      } finally {
        release.countDown();
      }
    }
  }

  @Test
  void aTestClientDrawsFromTheSameBoundAsAListener() throws Exception {
    var entered = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    try (var app = Axiom.create()) {
      app.admissionPolicy(AdmissionPolicy.reject(1));
      app.get(
          "/hold",
          ctx -> {
            entered.countDown();
            assertThat(release.await(10, TimeUnit.SECONDS)).isTrue();
            return "done";
          });
      var listener = app.listen(0);
      var client = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();
      try (var testClient = TestClient.start(app)) {
        var held = get(client, listener, "/hold");
        assertThat(entered.await(10, TimeUnit.SECONDS)).isTrue();
        var rejected = testClient.get("/hold");
        assertThat(rejected.status()).isEqualTo(503);
        assertThat(rejected.headers().get("Content-Type")).contains("application/problem+json");
        release.countDown();
        assertThat(joined(held).statusCode()).isEqualTo(200);
        assertThat(testClient.get("/hold").status()).isEqualTo(200);
      } finally {
        release.countDown();
      }
    }
  }

  private static CompletableFuture<HttpResponse<String>> get(
      HttpClient client, Server server, String path) {
    var address = server.localAddress();
    var target = URI.create("http://127.0.0.1:" + address.getPort() + path);
    var request = HttpRequest.newBuilder(target).timeout(Duration.ofSeconds(10)).GET().build();
    return client.sendAsync(request, HttpResponse.BodyHandlers.ofString());
  }

  private static HttpResponse<String> send(HttpClient client, Server server, String path)
      throws Exception {
    return joined(get(client, server, path));
  }

  private static HttpResponse<String> joined(CompletableFuture<HttpResponse<String>> pending)
      throws Exception {
    return pending.get(10, TimeUnit.SECONDS);
  }
}
