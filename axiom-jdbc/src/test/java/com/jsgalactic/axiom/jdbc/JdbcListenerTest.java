package com.jsgalactic.axiom.jdbc;

import static org.assertj.core.api.Assertions.assertThat;

import com.jsgalactic.axiom.Axiom;
import com.jsgalactic.axiom.lifecycle.Server;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** The gate's refusal over a live HTTP/1.1 listener, with no timing-dependent assertions. */
@Tag("integration")
class JdbcListenerTest {
  private static HttpResponse<String> send(HttpClient client, Server server) throws Exception {
    var address = server.localAddress();
    var uri =
        URI.create(
            "http://" + address.getAddress().getHostAddress() + ":" + address.getPort() + "/work");
    return client.send(
        HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(10)).GET().build(),
        HttpResponse.BodyHandlers.ofString());
  }

  @Test
  void fullGateAnswers503WithoutCallingTheHandler() throws Exception {
    var app = Axiom.create();
    var entered = new CountDownLatch(1);
    var resume = new CountDownLatch(1);
    var calls = new AtomicInteger();
    app.get(
        "/work",
        ctx -> {
          calls.incrementAndGet();
          entered.countDown();
          if (!resume.await(5, TimeUnit.SECONDS)) {
            throw new IllegalStateException("handler was not resumed");
          }
          return "done";
        });
    Jdbc.builder(app, new FakeDataSource())
        .maxConnections(1)
        .wait(Duration.ZERO)
        .build()
        .register();

    try (var client = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build()) {
      var server = app.listen(0);
      try {
        var first = new CompletableFuture<Integer>();
        Thread.ofVirtual().start(() -> first.complete(sendQuietly(client, server)));
        assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
        var rejected = send(client, server);
        assertThat(rejected.statusCode()).isEqualTo(503);
        assertThat(rejected.headers().firstValue("Content-Type"))
            .contains("application/problem+json");
        assertThat(rejected.body()).contains("\"code\":\"db_capacity\"");
        assertThat(calls.get()).isEqualTo(1);

        resume.countDown();
        assertThat(first.get(10, TimeUnit.SECONDS)).isEqualTo(200);
        assertThat(send(client, server).statusCode()).isEqualTo(200);
        assertThat(calls.get()).isEqualTo(2);
      } finally {
        server.close();
        server.termination().toCompletableFuture().get(10, TimeUnit.SECONDS);
      }
    } finally {
      app.close();
    }
  }

  private static int sendQuietly(HttpClient client, Server server) {
    try {
      return send(client, server).statusCode();
    } catch (Exception failure) {
      throw new IllegalStateException(failure);
    }
  }
}
