package com.jsgalactic.axiom.jta.spi;

/**
 * A read-only view of the JTA transaction bound to a request, with only JDK types in its
 * signatures.
 *
 * <p>This is the integration point for modules such as a JPA adapter that need to know whether the
 * current request has an active JTA transaction. Such a module does not need a compile dependency
 * on {@code axiom-jta}: it can load this interface by name from {@code ServiceLoader} and call its
 * three methods reflectively, because the arguments and results are {@code Object}, {@code String}
 * and {@code boolean}. The implementation shipped by {@code axiom-jta} is registered as a service
 * and consults every {@code Jta} instance built in this class loader, keyed by the request's
 * context object. It never starts, completes or mutates a transaction.
 */
public interface JtaTransactionLookup {
  /**
   * Reports whether a transaction is currently associated with the request represented by {@code
   * context}.
   *
   * @param context the request's Axiom context, or any object to answer {@code false}
   * @return true while a transaction is active for that request
   */
  boolean active(Object context);

  /**
   * Returns the request's transaction status as a plain name: {@code ACTIVE}, {@code
   * MARKED_ROLLBACK}, {@code COMMITTED}, {@code ROLLED_BACK}, {@code INACTIVE} or {@code UNKNOWN}.
   *
   * @param context the request's Axiom context
   * @return one of the six status names
   */
  String status(Object context);

  /**
   * Reports whether the request's active transaction has been marked rollback-only.
   *
   * @param context the request's Axiom context
   * @return true when rollback-only was set by the handler or the provider
   */
  boolean rollbackOnly(Object context);
}
