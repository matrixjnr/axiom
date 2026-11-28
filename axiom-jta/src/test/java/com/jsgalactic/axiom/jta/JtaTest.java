package com.jsgalactic.axiom.jta;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.jsgalactic.axiom.Axiom;
import com.jsgalactic.axiom.error.ConflictException;
import com.jsgalactic.axiom.http.Response;
import com.jsgalactic.axiom.test.TestClient;
import jakarta.transaction.Status;
import jakarta.transaction.SystemException;
import jakarta.transaction.Transaction;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/** Deterministic coverage of the JTA boundary through the in-memory dispatcher. */
class JtaTest {
  private static String text(Response response) {
    return response.body() instanceof String value
        ? value
        : new String((byte[]) response.body(), StandardCharsets.UTF_8);
  }

  private static void assertProblem(Response response, int status, String code) {
    assertThat(response.status()).isEqualTo(status);
    assertThat(text(response)).contains("\"code\":\"" + code + "\"");
  }

  @Test
  void commitsSuccessfulResponseAndBindsRequestScopedContext() throws Exception {
    var tm = new FakeTransactionManager();
    var jta = Jta.builder(tm).timeout(Duration.ofMillis(1500)).build();
    var seen = new AtomicReference<TransactionContext>();
    var app = Axiom.create();
    app.get(
        "/ok",
        ctx -> {
          var transaction = Jta.context(ctx).orElseThrow();
          seen.set(transaction);
          assertThat(transaction.active()).isTrue();
          assertThat(transaction.status()).isEqualTo(TransactionStatus.ACTIVE);
          assertThat(transaction.rollbackOnly()).isFalse();
          assertThat(transaction.timeout()).contains(Duration.ofMillis(1500));
          assertThat(transaction.outcome()).isEqualTo(TransactionOutcome.PENDING);
          return ctx.text("done");
        },
        jta.transaction());

    try (var client = TestClient.start(app)) {
      var response = client.get("/ok");
      assertThat(response.status()).isEqualTo(200);
      assertThat(text(response)).isEqualTo("done");
    }

    assertThat(tm.events()).containsExactly("suspend", "timeout:2", "begin", "commit");
    assertThat(tm.commits()).isEqualTo(1);
    assertThat(tm.getStatus()).isEqualTo(Status.STATUS_NO_TRANSACTION);
    assertThat(seen.get().active()).isFalse();
    assertThat(seen.get().status()).isEqualTo(TransactionStatus.COMMITTED);
    assertThat(seen.get().outcome()).isEqualTo(TransactionOutcome.COMMITTED);
  }

  @Test
  void rollsBackProblemResponseWithoutCommitting() throws Exception {
    var tm = new FakeTransactionManager();
    var app = Axiom.create();
    app.get(
        "/conflict",
        ctx -> ctx.status(409).text("conflict"),
        Jta.builder(tm).build().transaction());

    try (var client = TestClient.start(app)) {
      assertThat(client.get("/conflict").status()).isEqualTo(409);
    }

    assertThat(tm.events()).containsExactly("suspend", "begin", "rollback");
    assertThat(tm.rollbacks()).isEqualTo(1);
    assertThat(tm.commits()).isZero();
  }

  @Test
  void rollsBackExceptionAndDecoratesMappedFailure() throws Exception {
    var tm = new FakeTransactionManager();
    var app = Axiom.create();
    app.get(
        "/boom",
        ctx -> {
          throw new ConflictException("order_conflict");
        },
        Jta.builder(tm).build().transaction());

    try (var client = TestClient.start(app)) {
      var response = client.get("/boom");
      assertProblem(response, 409, "order_conflict");
      assertThat(response.headers().get("X-Axiom-Transaction")).isEqualTo("rolled_back");
    }

    assertThat(tm.events()).containsExactly("suspend", "begin", "rollback");
  }

  @Test
  void setRollbackOnlyKeepsResponseButForcesRollback() throws Exception {
    var tm = new FakeTransactionManager();
    var seen = new AtomicReference<TransactionContext>();
    var app = Axiom.create();
    app.get(
        "/marked",
        ctx -> {
          var transaction = Jta.context(ctx).orElseThrow();
          transaction.setRollbackOnly();
          assertThat(transaction.rollbackOnly()).isTrue();
          assertThat(transaction.status()).isEqualTo(TransactionStatus.MARKED_ROLLBACK);
          seen.set(transaction);
          return ctx.text("ok");
        },
        Jta.builder(tm).build().transaction());

    try (var client = TestClient.start(app)) {
      assertThat(client.get("/marked").status()).isEqualTo(200);
    }

    assertThat(tm.events()).containsExactly("suspend", "begin", "rollback");
    assertThat(seen.get().outcome()).isEqualTo(TransactionOutcome.ROLLED_BACK);
    assertThatThrownBy(seen.get()::setRollbackOnly).isInstanceOf(IllegalStateException.class);
  }

  @Test
  void nestedScopesSuspendAndResumeTheOuterTransaction() throws Exception {
    var tm = new FakeTransactionManager();
    var jta = Jta.builder(tm).build();
    var app = Axiom.create();
    app.use(jta.transaction());
    app.get(
        "/nested",
        ctx -> {
          assertThat(Jta.context(ctx).orElseThrow().active()).isTrue();
          return ctx.text("inner");
        },
        jta.transaction());

    try (var client = TestClient.start(app)) {
      assertThat(client.get("/nested").status()).isEqualTo(200);
    }

    assertThat(tm.events())
        .containsExactly("suspend", "begin", "suspend", "begin", "commit", "resume", "commit");
    assertThat(tm.commits()).isEqualTo(2);
    assertThat(tm.getStatus()).isEqualTo(Status.STATUS_NO_TRANSACTION);
  }

  @Test
  void restoresTransactionAssociatedBeforeTheBoundary() throws Exception {
    var tm = new FakeTransactionManager();
    var associated = new AtomicReference<Transaction>();
    var app = Axiom.create();
    // An outer piece of application middleware associates a transaction on the request thread.
    app.use((ctx, next) -> {
      tm.begin();
      associated.set(tm.getTransaction());
      return next.run();
    });
    app.get("/inner", ctx -> ctx.text("ok"), Jta.builder(tm).build().transaction());

    try (var client = TestClient.start(app)) {
      assertThat(client.get("/inner").status()).isEqualTo(200);
    }

    assertThat(tm.resumed()).containsExactly(associated.get());
    assertThat(tm.events()).containsExactly("begin", "suspend", "begin", "commit", "resume");
    assertThat(tm.getStatus()).isEqualTo(Status.STATUS_NO_TRANSACTION);
  }

  @Test
  void rollbackRulesCanCommitAnExceptionTheErrorHandlerMapsToSuccess() throws Exception {
    var tm = new FakeTransactionManager();
    var app = Axiom.create();
    app.get(
        "/expected",
        ctx -> {
          throw new IllegalArgumentException("expected");
        },
        Jta.builder(tm).noRollbackOn(IllegalArgumentException.class).build().transaction());

    try (var client = TestClient.start(app)) {
      assertThatThrownBy(() -> client.get("/expected"))
          .isInstanceOf(IllegalArgumentException.class);
    }

    assertThat(tm.commits()).isEqualTo(1);
    assertThat(tm.rollbacks()).isZero();

    var second = new FakeTransactionManager();
    var guarded = Axiom.create();
    guarded.get(
        "/expected",
        ctx -> {
          throw new IllegalArgumentException("expected");
        },
        Jta.builder(second)
            .noRollbackOn(IllegalArgumentException.class)
            .rollbackWhen(failure -> true)
            .build()
            .transaction());
    try (var client = TestClient.start(guarded)) {
      assertThatThrownBy(() -> client.get("/expected"))
          .isInstanceOf(IllegalArgumentException.class);
    }
    assertThat(second.rollbacks()).isEqualTo(1);
    assertThat(second.commits()).isZero();
  }

  @Test
  void mapsBoundaryFailuresToFixedSafeProblems() throws Exception {
    var tm = new FakeTransactionManager();
    tm.beginFailure = new RuntimeException("jdbc:secret-password");
    var reached = new AtomicBoolean();
    var app = Axiom.create();
    app.get(
        "/begin",
        ctx -> {
          reached.set(true);
          return ctx.text("unreachable");
        },
        Jta.builder(tm).build().transaction());

    try (var client = TestClient.startMappingFailures(app)) {
      var response = client.get("/begin");
      assertProblem(response, 503, "transaction_unavailable");
      assertThat(text(response)).doesNotContain("secret-password", "RuntimeException");
    }
    assertThat(reached).isFalse();
    assertThat(tm.events()).containsExactly("suspend");

    var commitTm = new FakeTransactionManager();
    commitTm.commitFailure = new RuntimeException("provider stack trace");
    var commitApp = Axiom.create();
    commitApp.get(
        "/commit",
        ctx -> ctx.text("ok"),
        Jta.builder(commitTm).problemStatus(TransactionFailure.COMMIT, 500).build().transaction());
    try (var client = TestClient.startMappingFailures(commitApp)) {
      var response = client.get("/commit");
      assertProblem(response, 500, "transaction_failed");
      assertThat(text(response)).doesNotContain("provider stack trace", "RuntimeException");
    }
    assertThat(commitTm.events()).containsExactly("suspend", "begin", "rollback");
  }

  @Test
  void statusQueryFailureIsReportedAsUnknownWithoutFailingTheRequest() throws Exception {
    var tm = new FakeTransactionManager();
    tm.statusFailure = new SystemException("status unavailable");
    var seen = new AtomicReference<TransactionStatus>();
    var app = Axiom.create();
    app.get(
        "/unknown",
        ctx -> {
          seen.set(Jta.context(ctx).orElseThrow().status());
          return ctx.text("ok");
        },
        Jta.builder(tm).build().transaction());

    try (var client = TestClient.start(app)) {
      assertThat(client.get("/unknown").status()).isEqualTo(200);
    }

    assertThat(seen.get()).isEqualTo(TransactionStatus.UNKNOWN);
    assertThat(tm.commits()).isEqualTo(1);
  }

  @Test
  void rejectsInvalidConfigurationBeforeAnyTransactionIsBound() {
    var tm = new FakeTransactionManager();
    assertThatIllegalArgumentException()
        .isThrownBy(() -> Jta.builder(new Object()))
        .withMessageContaining("TransactionManager");
    assertThatIllegalArgumentException().isThrownBy(() -> Jta.builder(tm).timeout(Duration.ZERO));
    assertThatIllegalArgumentException()
        .isThrownBy(() -> Jta.builder(tm).timeout(Duration.ofDays(2)));
    assertThatIllegalArgumentException()
        .isThrownBy(() -> Jta.builder(tm).problemStatus(TransactionFailure.COMMIT, 404));
    assertThat(tm.events()).isEmpty();
  }

  @Test
  void concurrentRequestsKeepIndependentContextsAndLeaveNoAssociation() throws Exception {
    var tm = new FakeTransactionManager();
    var jta = Jta.builder(tm).build();
    var app = Axiom.create();
    var entered = new CountDownLatch(2);
    var release = new CountDownLatch(1);
    var seen = new ConcurrentLinkedQueue<TransactionContext>();
    app.get(
        "/hold",
        ctx -> {
          seen.add(Jta.context(ctx).orElseThrow());
          entered.countDown();
          assertThat(release.await(5, TimeUnit.SECONDS)).isTrue();
          assertThat(Jta.context(ctx).orElseThrow().active()).isTrue();
          return ctx.text("ok");
        },
        jta.transaction());
    app.get(
        "/plain",
        ctx -> {
          assertThat(Jta.context(ctx)).isEmpty();
          assertThat(tm.getStatus()).isEqualTo(Status.STATUS_NO_TRANSACTION);
          return ctx.text("plain");
        });

    ExecutorService workers = Executors.newFixedThreadPool(2);
    try (var firstClient = TestClient.start(app);
        var secondClient = TestClient.start(app)) {
      try {
        Future<Response> first = workers.submit(() -> firstClient.get("/hold"));
        Future<Response> second = workers.submit(() -> secondClient.get("/hold"));
        assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
        release.countDown();
        assertThat(first.get(5, TimeUnit.SECONDS).status()).isEqualTo(200);
        assertThat(second.get(5, TimeUnit.SECONDS).status()).isEqualTo(200);
      } finally {
        release.countDown();
        workers.shutdownNow();
        assertThat(workers.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
      }
      assertThat(firstClient.get("/plain").status()).isEqualTo(200);
    }

    assertThat(seen).hasSize(2);
    var iterator = seen.iterator();
    var one = iterator.next();
    var two = iterator.next();
    assertThat(one).isNotSameAs(two);
    assertThat(one.active()).isFalse();
    assertThat(two.active()).isFalse();
    assertThat(tm.commits()).isEqualTo(2);
    assertThat(tm.getStatus()).isEqualTo(Status.STATUS_NO_TRANSACTION);
  }
}
