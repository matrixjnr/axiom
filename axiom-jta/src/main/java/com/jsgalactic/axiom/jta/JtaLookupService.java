package com.jsgalactic.axiom.jta;

import com.jsgalactic.axiom.context.Context;
import com.jsgalactic.axiom.jta.spi.JtaTransactionLookup;
import java.util.Collections;
import java.util.Set;
import java.util.WeakHashMap;

/**
 * The service-loaded implementation of {@link JtaTransactionLookup}.
 *
 * <p>It consults the {@link Jta} instances built in this class loader, each of which answers only
 * for the request context it was given. Applications normally use {@link Jta#context(Context)}
 * directly; integrations that must not depend on this module can load this service by name and call
 * it with JDK types only. The service never begins, completes or mutates a transaction.
 */
public final class JtaLookupService implements JtaTransactionLookup {
  private static final Set<Jta> INSTANCES = Collections.newSetFromMap(new WeakHashMap<>());

  /** Creates the service instance used by {@code ServiceLoader}. */
  public JtaLookupService() {}

  static void register(Jta jta) {
    synchronized (INSTANCES) {
      INSTANCES.add(jta);
    }
  }

  @Override
  public boolean active(Object context) {
    if (!(context instanceof Context request)) {
      return false;
    }
    synchronized (INSTANCES) {
      for (Jta jta : INSTANCES) {
        if (jta.active(request)) {
          return true;
        }
      }
    }
    return false;
  }

  @Override
  public String status(Object context) {
    if (!(context instanceof Context request)) {
      return TransactionStatus.INACTIVE.name();
    }
    synchronized (INSTANCES) {
      for (Jta jta : INSTANCES) {
        TransactionStatus status = jta.statusOf(request);
        if (status != TransactionStatus.INACTIVE) {
          return status.name();
        }
      }
    }
    return TransactionStatus.INACTIVE.name();
  }

  @Override
  public boolean rollbackOnly(Object context) {
    if (!(context instanceof Context request)) {
      return false;
    }
    synchronized (INSTANCES) {
      for (Jta jta : INSTANCES) {
        if (jta.rollbackOnly(request)) {
          return true;
        }
      }
    }
    return false;
  }
}
