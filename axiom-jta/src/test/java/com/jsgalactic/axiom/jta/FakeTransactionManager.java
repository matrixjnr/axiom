package com.jsgalactic.axiom.jta;

import jakarta.transaction.InvalidTransactionException;
import jakarta.transaction.NotSupportedException;
import jakarta.transaction.Status;
import jakarta.transaction.Synchronization;
import jakarta.transaction.SystemException;
import jakarta.transaction.Transaction;
import jakarta.transaction.TransactionManager;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import javax.transaction.xa.XAResource;

/** An in-memory, thread-affine transaction manager recording every boundary call. */
final class FakeTransactionManager implements TransactionManager {
  private final List<String> events = new CopyOnWriteArrayList<>();
  private final List<Transaction> resumed = new ArrayList<>();
  private final ThreadLocal<Transaction> associated = new ThreadLocal<>();
  private final AtomicInteger commits = new AtomicInteger();
  private final AtomicInteger rollbacks = new AtomicInteger();
  private final AtomicInteger nextId = new AtomicInteger(1);

  private volatile int timeoutSeconds;

  RuntimeException suspendFailure;
  RuntimeException beginFailure;
  RuntimeException commitFailure;
  RuntimeException rollbackFailure;
  RuntimeException resumeFailure;
  RuntimeException markFailure;
  SystemException timeoutFailure;
  SystemException statusFailure;

  @Override
  public void begin() throws NotSupportedException, SystemException {
    if (beginFailure != null) {
      throw beginFailure;
    }
    if (associated.get() != null) {
      throw new IllegalStateException("A transaction is already associated");
    }
    events.add("begin");
    associated.set(new FakeTransaction(nextId.getAndIncrement()));
  }

  @Override
  public void commit() {
    if (commitFailure != null) {
      throw commitFailure;
    }
    events.add("commit");
    requireActive();
    associated.remove();
    commits.incrementAndGet();
  }

  @Override
  public int getStatus() throws SystemException {
    if (statusFailure != null) {
      throw statusFailure;
    }
    Transaction transaction = associated.get();
    return transaction == null ? Status.STATUS_NO_TRANSACTION : transaction.getStatus();
  }

  @Override
  public Transaction getTransaction() {
    return associated.get();
  }

  @Override
  public void resume(Transaction transaction) throws InvalidTransactionException {
    if (resumeFailure != null) {
      throw resumeFailure;
    }
    if (transaction != null) {
      events.add("resume");
      synchronized (resumed) {
        resumed.add(transaction);
      }
    }
    if (transaction == null) {
      associated.remove();
    } else {
      associated.set(transaction);
    }
  }

  @Override
  public void rollback() {
    if (rollbackFailure != null) {
      throw rollbackFailure;
    }
    events.add("rollback");
    requireActive();
    associated.remove();
    rollbacks.incrementAndGet();
  }

  @Override
  public void setRollbackOnly() throws SystemException {
    if (markFailure != null) {
      throw markFailure;
    }
    requireActive();
    associated.get().setRollbackOnly();
  }

  @Override
  public void setTransactionTimeout(int seconds) throws SystemException {
    if (timeoutFailure != null) {
      throw timeoutFailure;
    }
    timeoutSeconds = seconds;
    events.add("timeout:" + seconds);
  }

  @Override
  public Transaction suspend() {
    if (suspendFailure != null) {
      throw suspendFailure;
    }
    events.add("suspend");
    Transaction previous = associated.get();
    associated.remove();
    return previous;
  }

  List<String> events() {
    return List.copyOf(events);
  }

  List<Transaction> resumed() {
    synchronized (resumed) {
      return List.copyOf(resumed);
    }
  }

  Transaction current() {
    return associated.get();
  }

  int timeoutSeconds() {
    return timeoutSeconds;
  }

  int commits() {
    return commits.get();
  }

  int rollbacks() {
    return rollbacks.get();
  }

  private void requireActive() {
    if (associated.get() == null) {
      throw new IllegalStateException("No transaction is associated");
    }
  }

  private static final class FakeTransaction implements Transaction {
    private final int id;
    private int status = Status.STATUS_ACTIVE;

    FakeTransaction(int id) {
      this.id = id;
    }

    @Override
    public void commit() {
      status = Status.STATUS_COMMITTED;
    }

    @Override
    public void rollback() {
      status = Status.STATUS_ROLLEDBACK;
    }

    @Override
    public void setRollbackOnly() {
      status = Status.STATUS_MARKED_ROLLBACK;
    }

    @Override
    public int getStatus() {
      return status;
    }

    @Override
    public boolean enlistResource(XAResource xaRes) {
      return false;
    }

    @Override
    public boolean delistResource(XAResource xaRes, int flag) {
      return false;
    }

    @Override
    public void registerSynchronization(Synchronization sync) {}

    @Override
    public String toString() {
      return "fake-transaction-" + id;
    }
  }
}
