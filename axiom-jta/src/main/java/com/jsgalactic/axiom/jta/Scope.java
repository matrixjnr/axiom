package com.jsgalactic.axiom.jta;

import java.time.Duration;

/** One nested transaction boundary; only the innermost scope is visible to handlers. */
final class Scope {
  final Manager manager;
  final Duration timeout;
  final int timeoutSeconds;

  Object suspended;
  boolean began;
  boolean active;
  boolean rollbackOnly;
  TransactionStatus status = TransactionStatus.INACTIVE;
  TransactionOutcome outcome = TransactionOutcome.PENDING;

  Scope(Manager manager, Duration timeout, int timeoutSeconds) {
    this.manager = manager;
    this.timeout = timeout;
    this.timeoutSeconds = timeoutSeconds;
  }

  boolean rollsBack() {
    return rollbackOnly || status == TransactionStatus.MARKED_ROLLBACK;
  }
}
