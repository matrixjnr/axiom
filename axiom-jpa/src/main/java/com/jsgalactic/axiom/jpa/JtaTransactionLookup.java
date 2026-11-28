package com.jsgalactic.axiom.jpa;

import com.jsgalactic.axiom.context.Context;

/**
 * Reports whether a request already carries an active JTA transaction.
 *
 * <p>An application that has a JTA module supplies a callback that reads that module's own
 * request-scoped state, for example {@code Jta::active}. No JTA type appears in this interface and
 * {@code axiom-jpa} has no dependency on the module that supplies the transaction. Without a
 * callback this adapter always manages a resource-local transaction itself.
 *
 * <p>The callback runs on the request thread while the EntityManager is being opened. It must be
 * thread-safe and must not retain the context. A callback that throws fails the request.
 */
@FunctionalInterface
public interface JtaTransactionLookup {
  /**
   * Reports whether the request has a JTA transaction an EntityManager can join.
   *
   * @param context the request being served
   * @return true to call {@code EntityManager.joinTransaction()} instead of starting a local
   *     transaction
   */
  boolean active(Context context);
}
