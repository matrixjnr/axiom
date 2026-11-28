package com.jsgalactic.axiom.jta;

/** A transaction-boundary operation that failed, used to choose a client-visible problem status. */
public enum TransactionFailure {
  /** Suspending a transaction already associated with the thread failed. */
  SUSPEND,
  /** Setting the transaction timeout or beginning the transaction failed. */
  BEGIN,
  /** Committing the transaction failed. */
  COMMIT,
  /** Rolling the transaction back failed. */
  ROLLBACK,
  /** Resuming the previously associated transaction failed. */
  RESUME,
  /** Marking the active transaction rollback-only failed. */
  MARK,
  /** No transaction was active where the boundary needed one. */
  INACTIVE,
  /** The configured timeout could not be applied by the provider. */
  TIMEOUT
}
