package com.jsgalactic.axiom.jta;

import jakarta.transaction.Status;
import jakarta.transaction.Transaction;
import jakarta.transaction.TransactionManager;

/**
 * The only place where JTA types are used. Every checked failure is converted to a {@link
 * ManagerFailure} with a plain {@link TransactionFailure} kind, so no JTA type can escape into an
 * Axiom signature or a client response.
 */
final class Manager {
  private final TransactionManager manager;

  Manager(Object candidate) {
    if (!(candidate instanceof TransactionManager value)) {
      throw new IllegalArgumentException(
          "transactionManager must be a jakarta.transaction.TransactionManager");
    }
    manager = value;
  }

  void timeout(int seconds) {
    try {
      manager.setTransactionTimeout(seconds);
    } catch (Exception failure) {
      throw new ManagerFailure(TransactionFailure.TIMEOUT, failure);
    }
  }

  void begin() {
    try {
      manager.begin();
    } catch (Exception failure) {
      throw new ManagerFailure(TransactionFailure.BEGIN, failure);
    }
  }

  Object suspend() {
    try {
      return manager.suspend();
    } catch (Exception failure) {
      throw new ManagerFailure(TransactionFailure.SUSPEND, failure);
    }
  }

  void resume(Object suspended) {
    try {
      manager.resume((Transaction) suspended);
    } catch (Exception failure) {
      throw new ManagerFailure(TransactionFailure.RESUME, failure);
    }
  }

  void commit() {
    try {
      manager.commit();
    } catch (Exception failure) {
      throw new ManagerFailure(TransactionFailure.COMMIT, failure);
    }
  }

  void rollback() {
    try {
      manager.rollback();
    } catch (Exception failure) {
      throw new ManagerFailure(TransactionFailure.ROLLBACK, failure);
    }
  }

  void markRollbackOnly() {
    try {
      manager.setRollbackOnly();
    } catch (Exception failure) {
      throw new ManagerFailure(TransactionFailure.MARK, failure);
    }
  }

  TransactionStatus status() {
    int status;
    try {
      status = manager.getStatus();
    } catch (Exception failure) {
      return TransactionStatus.UNKNOWN;
    }
    return switch (status) {
      case Status.STATUS_ACTIVE -> TransactionStatus.ACTIVE;
      case Status.STATUS_MARKED_ROLLBACK -> TransactionStatus.MARKED_ROLLBACK;
      case Status.STATUS_COMMITTED -> TransactionStatus.COMMITTED;
      case Status.STATUS_ROLLEDBACK -> TransactionStatus.ROLLED_BACK;
      case Status.STATUS_NO_TRANSACTION -> TransactionStatus.INACTIVE;
      default -> TransactionStatus.UNKNOWN;
    };
  }
}
