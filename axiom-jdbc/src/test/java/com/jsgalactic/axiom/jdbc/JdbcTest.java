package com.jsgalactic.axiom.jdbc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import com.jsgalactic.axiom.Axiom;
import com.jsgalactic.axiom.error.BadRequestException;
import com.jsgalactic.axiom.http.Response;
import com.jsgalactic.axiom.observability.Health;
import com.jsgalactic.axiom.routing.Route;
import com.jsgalactic.axiom.test.TestClient;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/** Deterministic tests: fake sources, latches and bounded waits, never a sleep. */
@Timeout(30)
class JdbcTest {
  private static String text(Response response) {
    Object body = response.body();
    return body instanceof byte[] bytes
        ? new String(bytes, StandardCharsets.UTF_8)
        : String.valueOf(body);
  }

  private static CompletableFuture<Response> get(TestClient client, String path) {
    var result = new CompletableFuture<Response>();
    Thread.ofVirtual()
        .start(() -> {
          try {
            result.complete(client.get(path));
          } catch (Throwable failure) {
            result.completeExceptionally(failure);
          }
        });
    return result;
  }

  private static boolean await(CountDownLatch latch) {
    try {
      return latch.await(5, TimeUnit.SECONDS);
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      throw new AssertionError(interrupted);
    }
  }

  private static Response done(CompletableFuture<Response> request) {
    try {
      return request.get(5, TimeUnit.SECONDS);
    } catch (Exception failure) {
      throw new AssertionError(failure);
    }
  }

  @Test
  void permitsAreReleasedAfterResponsesAndCountedOnBoundedSeries() throws Exception {
    var app = Axiom.create();
    var source = new FakeDataSource();
    var metrics = new RecordingMetrics();
    app.metrics(metrics);
    app.get("/ok", ctx -> "ok");
    Jdbc.builder(app, source).maxConnections(1).wait(Duration.ZERO).build().register();

    try (var client = TestClient.start(app)) {
      assertThat(client.get("/ok").status()).isEqualTo(200);
      assertThat(client.get("/ok").status()).isEqualTo(200);
    }

    assertThat(metrics.count(JdbcMetrics.GATE_ACQUIRED, "method", "GET", "route", "/ok"))
        .isEqualTo(2);
    assertThat(metrics.count(JdbcMetrics.GATE_RELEASED, "method", "GET", "route", "/ok"))
        .isEqualTo(2);
    assertThat(
            metrics.count(
                JdbcMetrics.ADMISSION_REJECTED,
                "method",
                "GET",
                "route",
                "/ok",
                "reason",
                JdbcMetrics.REASON))
        .isZero();
  }

  @Test
  void fullGateAnswers503BeforeTheHandlerRunsThenAcceptsAgain() throws Exception {
    var app = Axiom.create();
    var entered = new CountDownLatch(1);
    var resume = new CountDownLatch(1);
    var calls = new AtomicInteger();
    app.get(
        "/work",
        ctx -> {
          calls.incrementAndGet();
          entered.countDown();
          if (!await(resume)) {
            throw new IllegalStateException("handler was not resumed");
          }
          return "done";
        });
    var metrics = new RecordingMetrics();
    app.metrics(metrics);
    Jdbc.builder(app, new FakeDataSource())
        .maxConnections(1)
        .wait(Duration.ZERO)
        .build()
        .register();

    try (var client = TestClient.start(app)) {
      var first = get(client, "/work");
      assertThat(await(entered)).isTrue();
      var rejected = client.get("/work");
      assertThat(rejected.status()).isEqualTo(503);
      assertThat(text(rejected)).contains("\"code\":\"db_capacity\"");
      assertThat(calls.get()).isEqualTo(1);
      resume.countDown();
      assertThat(done(first).status()).isEqualTo(200);
      assertThat(client.get("/work").status()).isEqualTo(200);
      assertThat(calls.get()).isEqualTo(2);
    }

    assertThat(
            metrics.count(
                JdbcMetrics.ADMISSION_REJECTED,
                "method",
                "GET",
                "route",
                "/work",
                "reason",
                JdbcMetrics.REASON))
        .isEqualTo(1);
  }

  @Test
  void perRouteCapRefusesOnlyTheCappedRoute() throws Exception {
    var app = Axiom.create();
    var entered = new CountDownLatch(1);
    var resume = new CountDownLatch(1);
    Route orders =
        app.get(
            "/orders",
            ctx -> {
              entered.countDown();
              if (!await(resume)) {
                throw new IllegalStateException("handler was not resumed");
              }
              return "order";
            });
    app.get("/other", ctx -> "other");
    Jdbc.builder(app, new FakeDataSource())
        .maxConnections(4)
        .routeLimit(orders, 1)
        .wait(Duration.ZERO)
        .build()
        .register();

    try (var client = TestClient.start(app)) {
      var first = get(client, "/orders");
      assertThat(await(entered)).isTrue();
      assertThat(client.get("/orders").status()).isEqualTo(503);
      assertThat(client.get("/other").status()).isEqualTo(200);
      resume.countDown();
      assertThat(done(first).status()).isEqualTo(200);
      assertThat(client.get("/orders").status()).isEqualTo(200);
    }
  }

  @Test
  void waitingRequestReceivesThePermitAfterRelease() throws Exception {
    var app = Axiom.create();
    var entered = new CountDownLatch(1);
    var resume = new CountDownLatch(1);
    app.get(
        "/work",
        ctx -> {
          entered.countDown();
          if (!await(resume)) {
            throw new IllegalStateException("handler was not resumed");
          }
          return "done";
        });
    Jdbc.builder(app, new FakeDataSource()).maxConnections(1).build().register();

    try (var client = TestClient.start(app)) {
      var first = get(client, "/work");
      assertThat(await(entered)).isTrue();
      var second = get(client, "/work");
      assertThat(second).isNotDone();
      resume.countDown();
      assertThat(done(first).status()).isEqualTo(200);
      assertThat(done(second).status()).isEqualTo(200);
    }
  }

  @Test
  void mappedAndUnmappedFailuresReleaseTheirPermits() throws Exception {
    var app = Axiom.create();
    app.get(
        "/bad",
        ctx -> {
          throw new BadRequestException("bad_request");
        });
    app.get(
        "/boom",
        ctx -> {
          throw new IllegalStateException("boom");
        });
    Jdbc.builder(app, new FakeDataSource())
        .maxConnections(1)
        .wait(Duration.ZERO)
        .build()
        .register();

    try (var client = TestClient.startMappingFailures(app)) {
      assertThat(client.get("/bad").status()).isEqualTo(400);
      assertThat(client.get("/bad").status()).isEqualTo(400);
      assertThat(client.get("/boom").status()).isEqualTo(500);
      assertThat(client.get("/boom").status()).isEqualTo(500);
    }
  }

  @Test
  void wrapperCountsCheckoutsReleasesAndContentionWithOneBoundedTag() throws Exception {
    var source = new FakeDataSource();
    var metrics = new RecordingMetrics();
    var options =
        new JdbcOptions(
            "orders", 4, Duration.ZERO, "SELECT 1", Duration.ofSeconds(1), Map.of(), true);
    var clock = new AtomicLong();
    var wrapper = new JdbcDataSource(source, options, metrics, clock::getAndIncrement);

    Connection first = wrapper.getConnection("user", "secret");
    Connection second = wrapper.getConnection();
    assertThat(metrics.count(JdbcMetrics.CONNECTIONS_ACQUIRED, "name", "orders")).isEqualTo(2);
    assertThat(metrics.count(JdbcMetrics.CONNECTIONS_WAITED, "name", "orders")).isEqualTo(1);
    assertThat(metrics.count(JdbcMetrics.CONNECTIONS_RELEASED, "name", "orders")).isZero();

    first.close();
    first.close();
    second.close();
    assertThat(metrics.count(JdbcMetrics.CONNECTIONS_RELEASED, "name", "orders")).isEqualTo(2);
    assertThat(source.closed.get()).isEqualTo(2);
    assertThat(metrics.count(JdbcMetrics.CONNECTIONS_WAIT, "name", "orders")).isPositive();
    assertThat(wrapper.isWrapperFor(DataSource.class)).isTrue();
    assertThat(wrapper.unwrap(DataSource.class)).isSameAs(wrapper);
    assertThat(source.getLoginTimeout()).isEqualTo(3);
  }

  @Test
  void healthCheckReportsUpAndDownWithoutExposingSqlOrCredentials() {
    var app = Axiom.create();
    var source = new FakeDataSource();
    var jdbc = Jdbc.builder(app, source).build();
    var health = Health.builder(app).readiness("database", jdbc.healthCheck()).build();
    app.start();
    try {
      assertThat(health.readiness().status()).isEqualTo(Health.Status.UP);
      assertThat(health.readiness().checks()).containsEntry("database", Health.Status.UP);
      assertThat(source.queryTimeout.get()).isEqualTo(1);

      source.up.set(false);
      assertThat(health.readiness().status()).isEqualTo(Health.Status.DOWN);

      source.failQuery.set(true);
      var failed = health.readiness();
      assertThat(failed.status()).isEqualTo(Health.Status.DOWN);
      assertThat(failed.toJson()).doesNotContain("SELECT", "secret_table", "password", "hunter2");
    } finally {
      app.close();
    }
  }

  @Test
  void connectionCleanupIsOptInAndNeverClosesTheApplicationSource() throws Exception {
    var app = Axiom.create();
    var source = new FakeDataSource();
    var jdbc = Jdbc.builder(app, source).build();
    app.start();
    Connection leftOpen = jdbc.dataSource().getConnection();
    app.close();
    assertThat(source.closed.get()).isZero();
    assertThat(leftOpen.isClosed()).isFalse();

    var ownedApp = Axiom.create();
    var ownedSource = new FakeDataSource();
    var owned = Jdbc.builder(ownedApp, ownedSource).closeConnectionsOnShutdown().build();
    ownedApp.start();
    Connection tracked = owned.dataSource().getConnection();
    ownedApp.close();
    assertThat(ownedSource.closed.get()).isEqualTo(1);
    assertThat(tracked.isClosed()).isTrue();
    owned.close();
    assertThat(ownedSource.closed.get()).isEqualTo(1);
  }

  @Test
  void boundsAreValidatedWhenBuilt() {
    var app = Axiom.create();
    var source = new FakeDataSource();
    assertThatIllegalArgumentException()
        .isThrownBy(() -> Jdbc.builder(app, source).name("Bad Name").build());
    assertThatIllegalArgumentException()
        .isThrownBy(() -> Jdbc.builder(app, source).maxConnections(0).build());
    assertThatIllegalArgumentException()
        .isThrownBy(() -> Jdbc.builder(app, source).wait(Duration.ofMinutes(1)).build());
    assertThatIllegalArgumentException()
        .isThrownBy(() -> Jdbc.builder(app, source).probeQuery("SELECT 1; DROP TABLE t").build());
    assertThatIllegalArgumentException()
        .isThrownBy(() ->
            Jdbc.builder(app, source)
                .maxConnections(2)
                .routeLimit(new Route("GET", "/x"), 3)
                .build());
    app.close();
  }
}
