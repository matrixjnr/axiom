package com.jsgalactic.axiom.jta;

/** The completion outcome of the innermost transaction scope of a request. */
public enum TransactionOutcome {
  /** The scope is still running. */
  PENDING,
  /** The scope's transaction was committed. */
  COMMITTED,
  /** The scope's transaction was rolled back, including when rollback-only was set. */
  ROLLED_BACK,
  /** Completion failed; the underlying failure is attached to the request's exception for logs. */
  FAILED
}
