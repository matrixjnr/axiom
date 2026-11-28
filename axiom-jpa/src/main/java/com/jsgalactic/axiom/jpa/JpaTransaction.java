package com.jsgalactic.axiom.jpa;

import com.jsgalactic.axiom.context.Context;
import com.jsgalactic.axiom.context.Middleware;
import com.jsgalactic.axiom.http.Response;
import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.EntityTransaction;
import jakarta.persistence.FlushModeType;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** The middleware behind {@link Jpa#transaction()} and {@link Jpa#readOnlyTransaction()}. */
final class JpaTransaction implements Middleware {
  private static final String PROBLEM_TYPE = "application/problem+json";

  private final EntityManagerFactory factory;
  private final EntityManagers managers;
  private final JtaTransactionLookup jta;
  private final boolean readOnly;
  private final Set<Class<?>> rollbackRules;
  private final Set<Class<?>> commitRules;

  JpaTransaction(
      EntityManagerFactory factory,
      EntityManagers managers,
      JtaTransactionLookup jta,
      boolean readOnly,
      Set<Class<?>> rollbackRules,
      Set<Class<?>> commitRules) {
    this.factory = factory;
    this.managers = managers;
    this.jta = jta;
    this.readOnly = readOnly;
    this.rollbackRules = rollbackRules;
    this.commitRules = commitRules;
  }

  @Override
  public Response handle(Context context, Next next) throws Exception {
    var entityManager = factory.createEntityManager();
    var binding = managers.bind(context, entityManager);
    try {
      if (readOnly) {
        entityManager.setFlushMode(FlushModeType.COMMIT);
      }
      if (jta != null && jta.active(context)) {
        entityManager.joinTransaction();
        binding.joined = true;
      } else {
        EntityTransaction transaction = entityManager.getTransaction();
        transaction.begin();
        binding.transaction = transaction;
      }
      var response = next.run();
      if (binding.joined || readOnly || problem(response)) {
        rollback(binding, null);
      } else {
        commit(binding, null);
      }
      return response;
    } catch (Exception failure) {
      failed(binding, failure);
      throw failure;
    } catch (Error failure) {
      failed(binding, failure);
      throw failure;
    } finally {
      try {
        binding.entityManager.close();
      } finally {
        managers.finish(binding);
      }
    }
  }

  @Override
  public Response afterError(Context context, Response response) {
    var binding = managers.current(context);
    if (binding != null && !binding.finished) {
      // A failure that bypassed handle's own cleanup still ends with a rolled-back, closed manager.
      try {
        rollback(binding, null);
      } finally {
        try {
          binding.entityManager.close();
        } finally {
          managers.finish(binding);
        }
      }
    }
    return response;
  }

  private void failed(Binding binding, Throwable failure) {
    if (!binding.joined && !readOnly && !shouldRollback(failure)) {
      commit(binding, failure);
    } else {
      rollback(binding, failure);
    }
  }

  private boolean shouldRollback(Throwable failure) {
    for (Class<?> type = failure.getClass();
        type != null && type != Object.class;
        type = type.getSuperclass()) {
      if (rollbackRules.contains(type)) {
        return true;
      }
      if (commitRules.contains(type)) {
        return false;
      }
    }
    return true;
  }

  private static void commit(Binding binding, Throwable primary) {
    if (binding.transaction == null) {
      return;
    }
    try {
      binding.transaction.commit();
    } catch (RuntimeException failure) {
      if (primary == null) {
        throw failure;
      }
      primary.addSuppressed(failure);
    }
  }

  private static void rollback(Binding binding, Throwable primary) {
    if (binding.joined || binding.transaction == null) {
      return;
    }
    try {
      if (binding.transaction.isActive()) {
        binding.transaction.rollback();
      }
    } catch (RuntimeException failure) {
      if (primary == null) {
        throw failure;
      }
      primary.addSuppressed(failure);
    }
  }

  private static boolean problem(Response response) {
    if (response == null || response.status() >= 400) {
      return true;
    }
    for (Map.Entry<String, String> header : response.headers().entrySet()) {
      if (header.getKey().equalsIgnoreCase("Content-Type")
          && header.getValue().toLowerCase(Locale.ROOT).startsWith(PROBLEM_TYPE)) {
        return true;
      }
    }
    return false;
  }
}
