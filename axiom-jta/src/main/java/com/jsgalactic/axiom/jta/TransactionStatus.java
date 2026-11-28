package com.jsgalactic.axiom.jta;

/**
 * A transaction status expressed without a JTA type, so integrations can read it without depending
 * on the transaction API. Values are the statuses an Axiom transaction boundary can report; a
 * provider-specific state that has no equivalent here is reported as {@link #UNKNOWN}.
 */
public enum TransactionStatus {
  /** A transaction is associated with the request and can still be completed. */
  ACTIVE,
  /** A transaction is associated but has been marked for rollback. */
  MARKED_ROLLBACK,
  /** The associated transaction completed with a commit. */
  COMMITTED,
  /** The associated transaction completed with a rollback. */
  ROLLED_BACK,
  /** No transaction is associated with the request. */
  INACTIVE,
  /** The provider reported a state this adapter does not model, or its status query failed. */
  UNKNOWN
}
