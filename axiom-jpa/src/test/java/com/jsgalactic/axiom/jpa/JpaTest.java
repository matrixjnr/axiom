package com.jsgalactic.axiom.jpa;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.jsgalactic.axiom.Axiom;
import com.jsgalactic.axiom.context.Context;
import com.jsgalactic.axiom.context.Middleware;
import com.jsgalactic.axiom.http.Request;
import com.jsgalactic.axiom.http.Response;
import jakarta.persistence.EntityManager;
import jakarta.persistence.spi.PersistenceProvider;
import java.util.ArrayList;
import java.util.Collections;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/** Deterministic coverage of JPA transaction boundaries through Axiom's real dispatcher. */
class JpaTest {
  private static final long WAIT_SECONDS = 5;

  private static Jpa jpa(FakePersistence persistence) {
    return Jpa.builder().entityManagerFactory(persistence.factory()).build();
  }

  @Test
  void buildingDoesNotOpenAnEntityManager() {
    var persistence = FakePersistence.create();
    var jpa = jpa(persistence);
    assertThat(persistence.events()).isEmpty();
    assertThat(jpa.entityManagers()).isNotNull();
  }

  @Test
  void successfulResponseCommitsAndClosesTheRequestManager() throws Exception {
    var persistence = FakePersistence.create();
    var jpa = jpa(persistence);
    var managers = jpa.entityManagers();
    var seen = new AtomicReference<EntityManager>();
    var contextSeen = new AtomicReference<Context>();
    try (var app = Axiom.create()) {
      app.use(jpa.transaction());
      app.get(
          "/ok",
          ctx -> {
            contextSeen.set(ctx);
            seen.set(jpa.entityManager(ctx));
            return ctx.text("ok");
          });
      app.start();
      assertThat(app.handle(Request.get("/ok")).status()).isEqualTo(200);
    }
    assertThat(seen.get()).isSameAs(persistence.last());
    assertThat(managers.has(contextSeen.get())).isFalse();
    assertThat(persistence.events())
        .containsExactly("factory.create", "em1.begin", "em1.commit", "em1.close");
  }

  @Test
  void exceptionRollsBackAndClosesWithoutSwallowingTheFailure() throws Exception {
    var persistence = FakePersistence.create();
    var jpa = jpa(persistence);
    try (var app = Axiom.create()) {
      app.use(jpa.transaction());
      app.get(
          "/fail",
          ctx -> {
            throw new IllegalStateException("boom");
          });
      app.start();
      assertThatThrownBy(() -> app.handle(Request.get("/fail")))
          .isInstanceOf(IllegalStateException.class)
          .hasMessage("boom");
    }
    assertThat(persistence.events())
        .containsExactly("factory.create", "em1.begin", "em1.rollback", "em1.close");
  }

  @Test
  void errorResponseAndProblemBodyRollBack() throws Exception {
    var persistence = FakePersistence.create();
    try (var app = Axiom.create()) {
      app.use(jpa(persistence).transaction());
      app.get("/bad", ctx -> ctx.status(422).text("bad"));
      app.get("/problem", ctx -> ctx.encode("application/problem+json", "{}"));
      app.start();
      assertThat(app.handle(Request.get("/bad")).status()).isEqualTo(422);
      assertThat(app.handle(Request.get("/problem")).status()).isEqualTo(200);
    }
    assertThat(persistence.events())
        .containsExactly(
            "factory.create",
            "em1.begin",
            "em1.rollback",
            "em1.close",
            "factory.create",
            "em2.begin",
            "em2.rollback",
            "em2.close");
  }

  @Test
  void afterErrorDecorationCleansUpExactlyOnce() throws Exception {
    var persistence = FakePersistence.create();
    var calls = Collections.synchronizedList(new ArrayList<String>());
    Middleware decorator =
        new Middleware() {
          @Override
          public Response handle(Context context, Next next) throws Exception {
            return next.run();
          }

          @Override
          public Response afterError(Context context, Response response) {
            calls.add("afterError:" + response.status());
            return response.withHeader("X-Decorated", "yes");
          }
        };
    try (var app = Axiom.create()) {
      app.use(decorator);
      app.use(jpa(persistence).transaction());
      app.get(
          "/mapped",
          ctx -> {
            throw new IllegalStateException("mapped");
          });
      app.error(IllegalStateException.class, (ctx, failure) -> ctx.status(409).text("mapped"));
      app.start();
      var response = app.handle(Request.get("/mapped"));
      assertThat(response.status()).isEqualTo(409);
      assertThat(response.headers()).containsEntry("X-Decorated", "yes");
    }
    assertThat(calls).containsExactly("afterError:409");
    assertThat(persistence.events())
        .containsExactly("factory.create", "em1.begin", "em1.rollback", "em1.close");
  }

  @Test
  void afterErrorClosesABindingThatHasNotFinished() throws Exception {
    var persistence = FakePersistence.create();
    var jpa = jpa(persistence);
    var middleware = jpa.transaction();
    var context = contextWithoutJpa();
    var manager = persistence.factory().createEntityManager();
    var binding = jpa.entityManagers().bind(context, manager);
    binding.transaction = manager.getTransaction();
    binding.transaction.begin();
    assertThat(jpa.entityManagers().has(context)).isTrue();

    var response = Response.of(500, null);
    assertThat(middleware.afterError(context, response)).isSameAs(response);

    assertThat(jpa.entityManagers().has(context)).isFalse();
    assertThat(persistence.events())
        .containsExactly("factory.create", "em1.begin", "em1.rollback", "em1.close");
    assertThat(middleware.afterError(context, response)).isSameAs(response);
    assertThat(persistence.events()).hasSize(4);
  }

  @Test
  void readOnlyDefersFlushingAndAlwaysRollsBack() throws Exception {
    var persistence = FakePersistence.create();
    var jpa = jpa(persistence);
    try (var app = Axiom.create()) {
      app.use(jpa.readOnlyTransaction());
      app.get("/read", ctx -> ctx.text("ok"));
      app.start();
      assertThat(app.handle(Request.get("/read")).status()).isEqualTo(200);
    }
    assertThat(persistence.events())
        .containsExactly(
            "factory.create", "em1.flush=COMMIT", "em1.begin", "em1.rollback", "em1.close");

    var configured = FakePersistence.create();
    try (var app = Axiom.create()) {
      app.use(
          Jpa.builder()
              .entityManagerFactory(configured.factory())
              .readOnly()
              .build()
              .transaction());
      app.get("/read", ctx -> ctx.text("ok"));
      app.start();
      assertThat(app.handle(Request.get("/read")).status()).isEqualTo(200);
    }
    assertThat(configured.events())
        .containsExactly(
            "factory.create", "em1.flush=COMMIT", "em1.begin", "em1.rollback", "em1.close");
  }

  @Test
  void rollbackRulesChooseTheNearestMatchingExceptionClass() throws Exception {
    var persistence = FakePersistence.create();
    var jpa =
        Jpa.builder()
            .entityManagerFactory(persistence.factory())
            .commitOn(BusinessRejection.class)
            .rollbackOn(SpecificRejection.class)
            .build();
    try (var app = Axiom.create()) {
      app.use(jpa.transaction());
      app.get("/business", ctx -> throwFailure(new BusinessRejection()));
      app.get("/specific", ctx -> throwFailure(new SpecificRejection()));
      app.get("/default", ctx -> throwFailure(new IllegalArgumentException()));
      app.start();
      assertThatThrownBy(() -> app.handle(Request.get("/business")))
          .isInstanceOf(BusinessRejection.class);
      assertThatThrownBy(() -> app.handle(Request.get("/specific")))
          .isInstanceOf(SpecificRejection.class);
      assertThatThrownBy(() -> app.handle(Request.get("/default")))
          .isInstanceOf(IllegalArgumentException.class);
    }
    assertThat(persistence.events())
        .containsExactly(
            "factory.create",
            "em1.begin",
            "em1.commit",
            "em1.close",
            "factory.create",
            "em2.begin",
            "em2.rollback",
            "em2.close",
            "factory.create",
            "em3.begin",
            "em3.rollback",
            "em3.close");
  }

  @Test
  void jtaCallbackJoinsInsteadOfBeginningALocalTransaction() throws Exception {
    var persistence = FakePersistence.create();
    var jpa =
        Jpa.builder().entityManagerFactory(persistence.factory()).jta(context -> true).build();
    try (var app = Axiom.create()) {
      app.use(jpa.transaction());
      app.get(
          "/joined",
          ctx -> {
            throw new IllegalStateException("boom");
          });
      app.start();
      assertThatThrownBy(() -> app.handle(Request.get("/joined")))
          .isInstanceOf(IllegalStateException.class);
    }
    assertThat(persistence.events()).containsExactly("factory.create", "em1.join", "em1.close");
  }

  @Test
  void nestedJpaMiddlewareKeepsBothAccessorsAndRestoresBindings() throws Exception {
    var outerPersistence = FakePersistence.create();
    var innerPersistence = FakePersistence.create();
    var outer = jpa(outerPersistence);
    var inner = jpa(innerPersistence);
    var distinct = new AtomicReference<Boolean>();
    try (var app = Axiom.create()) {
      app.use(outer.transaction());
      app.use(inner.transaction());
      app.get(
          "/nested",
          ctx -> {
            distinct.set(outer.entityManager(ctx) != inner.entityManager(ctx));
            return ctx.text("ok");
          });
      app.start();
      assertThat(app.handle(Request.get("/nested")).status()).isEqualTo(200);
    }
    assertThat(distinct.get()).isTrue();
    assertThat(outerPersistence.events())
        .containsExactly("factory.create", "em1.begin", "em1.commit", "em1.close");
    assertThat(innerPersistence.events())
        .containsExactly("factory.create", "em1.begin", "em1.commit", "em1.close");
  }

  @Test
  void twoRequestsUseSeparateManagersAndOneTransactionNeverSpansBoth() throws Exception {
    var persistence = FakePersistence.create();
    var jpa = jpa(persistence);
    var managers = jpa.entityManagers();
    var entered = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    var firstManager = new AtomicReference<EntityManager>();
    var secondManager = new AtomicReference<EntityManager>();
    ExecutorService worker = Executors.newSingleThreadExecutor();
    try (var app = Axiom.create()) {
      app.use(jpa.transaction());
      app.get(
          "/hold",
          ctx -> {
            firstManager.set(managers.get(ctx));
            entered.countDown();
            assertThat(release.await(WAIT_SECONDS, TimeUnit.SECONDS)).isTrue();
            return ctx.text("first");
          });
      app.get(
          "/quick",
          ctx -> {
            secondManager.set(managers.get(ctx));
            return ctx.text("second");
          });
      app.start();
      Future<Response> first = worker.submit(() -> app.handle(Request.get("/hold")));
      assertThat(entered.await(WAIT_SECONDS, TimeUnit.SECONDS)).isTrue();
      assertThat(app.handle(Request.get("/quick")).status()).isEqualTo(200);
      assertThat(firstManager.get()).isNotSameAs(secondManager.get());
      assertThat(persistence.events())
          .contains("em1.begin", "em2.begin", "em2.commit", "em2.close")
          .doesNotContain("em1.commit");
      release.countDown();
      assertThat(first.get(WAIT_SECONDS, TimeUnit.SECONDS).status()).isEqualTo(200);
      assertThat(persistence.events()).contains("em1.commit", "em1.close");
    } finally {
      worker.shutdownNow();
    }
  }

  @Test
  void accessorRejectsRequestsWithoutTheMiddleware() throws Exception {
    var jpa = jpa(FakePersistence.create());
    var context = contextWithoutJpa();
    assertThatIllegalStateException().isThrownBy(() -> jpa.entityManagers().get(context));
    assertThat(jpa.entityManagers().has(context)).isFalse();
  }

  @Test
  void builderRejectsMissingAndWrongFactories() {
    assertThatIllegalStateException().isThrownBy(() -> Jpa.builder().build());
    assertThatIllegalArgumentException()
        .isThrownBy(() -> Jpa.builder().entityManagerFactory("not a factory").build());
  }

  @Test
  void testScopeResolvesARealJpaProvider() throws Exception {
    var provider = Class.forName("org.hibernate.jpa.HibernatePersistenceProvider");
    assertThat(PersistenceProvider.class.isAssignableFrom(provider)).isTrue();
  }

  private static Context contextWithoutJpa() throws Exception {
    var captured = new AtomicReference<Context>();
    try (var app = Axiom.create()) {
      app.get(
          "/ctx",
          ctx -> {
            captured.set(ctx);
            return null;
          });
      app.start();
      app.handle(Request.get("/ctx"));
    }
    return captured.get();
  }

  private static Object throwFailure(Exception failure) throws Exception {
    throw failure;
  }

  private static class BusinessRejection extends Exception {
    private static final long serialVersionUID = 1L;
  }

  private static final class SpecificRejection extends BusinessRejection {
    private static final long serialVersionUID = 1L;
  }
}
