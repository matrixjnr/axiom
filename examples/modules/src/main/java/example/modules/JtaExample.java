package example.modules;

import com.jsgalactic.axiom.Axiom;
import com.jsgalactic.axiom.application.Application;
import com.jsgalactic.axiom.jta.Jta;
import jakarta.transaction.InvalidTransactionException;
import jakarta.transaction.NotSupportedException;
import jakarta.transaction.Status;
import jakarta.transaction.Synchronization;
import jakarta.transaction.Transaction;
import jakarta.transaction.TransactionManager;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import javax.transaction.xa.XAResource;

/**
 * Wraps handlers in a transaction supplied by the application. The fake manager below keeps only a
 * few counters: a real application passes its own provider and keeps the same routes.
 */
public final class JtaExample {
  private JtaExample() {}

  /**
   * Builds an application whose requests run inside the manager's transactions.
   *
   * @param manager an application-owned JTA transaction manager
   * @return a configured application, not yet started
   */
  public static Application create(TransactionManager manager) {
    var jta = Jta.builder(manager).timeout(Duration.ofSeconds(5)).build();
    var app = Axiom.create();
    app.use(jta.transaction());
    app.post(
        "/orders",
        ctx ->
            ctx.text(
                Jta.context(ctx).map(transaction -> transaction.status().name()).orElse("none")));
    app.get("/conflict", ctx -> ctx.status(409).text("conflict"));
    return app;
  }

  /**
   * Serves the transactional routes on http://127.0.0.1:8080/ until the process stops.
   *
   * @param args unused
   * @throws Exception if the listener cannot start
   */
  public static void main(String[] args) throws Exception {
    var app = create(new ExampleTransactionManager()).closeOnJvmShutdown();
    var server = app.listen(8080);
    System.out.println("JTA boundaries on http://127.0.0.1:8080/orders");
    server.termination().toCompletableFuture().join();
  }

  /** A thread-affine manager that records completions without a provider or a database. */
  static final class ExampleTransactionManager implements TransactionManager {
    private final AtomicInteger commits = new AtomicInteger();
    private final AtomicInteger rollbacks = new AtomicInteger();
    private final List<String> events = new CopyOnWriteArrayList<>();
    private final ThreadLocal<ExampleTransaction> associated = new ThreadLocal<>();

    int commits() {
      return commits.get();
    }

    int rollbacks() {
      return rollbacks.get();
    }

    List<String> events() {
      return List.copyOf(events);
    }

    @Override
    public void begin() throws NotSupportedException {
      if (associated.get() != null) {
        throw new NotSupportedException("Already associated");
      }
      events.add("begin");
      associated.set(new ExampleTransaction(Status.STATUS_ACTIVE));
    }

    @Override
    public void commit() {
      events.add("commit");
      associated.get().commit();
      commits.incrementAndGet();
      associated.remove();
    }

    @Override
    public void rollback() {
      events.add("rollback");
      associated.get().rollback();
      rollbacks.incrementAndGet();
      associated.remove();
    }

    @Override
    public void setRollbackOnly() {
      associated.get().setRollbackOnly();
    }

    @Override
    public int getStatus() {
      var transaction = associated.get();
      return transaction == null ? Status.STATUS_NO_TRANSACTION : transaction.getStatus();
    }

    @Override
    public Transaction getTransaction() {
      return associated.get();
    }

    @Override
    public Transaction suspend() {
      var previous = associated.get();
      associated.remove();
      return previous;
    }

    @Override
    public void resume(Transaction transaction) throws InvalidTransactionException {
      if (transaction == null) {
        associated.remove();
      } else if (transaction instanceof ExampleTransaction example) {
        associated.set(example);
      } else {
        throw new InvalidTransactionException("Foreign transaction");
      }
    }

    @Override
    public void setTransactionTimeout(int seconds) {
      events.add("timeout:" + seconds);
    }
  }

  /** One in-memory transaction; the manager changes its status when it completes. */
  private record ExampleTransaction(int initialStatus) implements Transaction {
    @Override
    public void commit() {}

    @Override
    public void rollback() {}

    @Override
    public void setRollbackOnly() {}

    @Override
    public int getStatus() {
      return initialStatus;
    }

    @Override
    public boolean enlistResource(XAResource resource) {
      return false;
    }

    @Override
    public boolean delistResource(XAResource resource, int flags) {
      return false;
    }

    @Override
    public void registerSynchronization(Synchronization synchronization) {}
  }
}
