package example.modules.persistence;

import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.EntityTransaction;
import jakarta.persistence.FlushModeType;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A factory and managers with no provider and no database. Every lifecycle call is recorded so a
 * test can see that a request's manager was committed and closed. Other JPA operations are not
 * needed by this example; the base classes supply them.
 */
public final class FakePersistence {
  private final List<String> events = new CopyOnWriteArrayList<>();
  private final AtomicInteger created = new AtomicInteger();
  private final EntityManagerFactory factory = new ExampleFactory();

  /**
   * Returns the factory the application owns and passes to the adapter.
   *
   * @return a thread-safe factory
   */
  public EntityManagerFactory factory() {
    return factory;
  }

  /**
   * Returns the recorded lifecycle calls in order.
   *
   * @return an immutable snapshot
   */
  public List<String> events() {
    return List.copyOf(events);
  }

  private final class ExampleFactory extends UnsupportedEntityManagerFactory {
    @Override
    public EntityManager createEntityManager() {
      var name = "em" + created.incrementAndGet();
      events.add(name + ".create");
      return new ExampleManager(name);
    }
  }

  private final class ExampleManager extends UnsupportedEntityManager {
    private final String name;
    private final EntityTransaction transaction;

    ExampleManager(String name) {
      this.name = name;
      transaction = new ExampleTransaction(name, events);
    }

    @Override
    public EntityTransaction getTransaction() {
      return transaction;
    }

    @Override
    public void setFlushMode(FlushModeType mode) {
      events.add(name + ".flush=" + mode);
    }

    @Override
    public void close() {
      events.add(name + ".close");
    }

    @Override
    public boolean isOpen() {
      return true;
    }
  }

  private static final class ExampleTransaction implements EntityTransaction {
    private final String name;
    private final List<String> events;
    private final AtomicBoolean active = new AtomicBoolean();

    ExampleTransaction(String name, List<String> events) {
      this.name = name;
      this.events = events;
    }

    @Override
    public void begin() {
      active.set(true);
      events.add(name + ".begin");
    }

    @Override
    public void commit() {
      requireActive();
      active.set(false);
      events.add(name + ".commit");
    }

    @Override
    public void rollback() {
      requireActive();
      active.set(false);
      events.add(name + ".rollback");
    }

    @Override
    public void setRollbackOnly() {
      events.add(name + ".rollbackOnly");
    }

    @Override
    public boolean getRollbackOnly() {
      return false;
    }

    @Override
    public boolean isActive() {
      return active.get();
    }

    @Override
    public void setTimeout(Integer seconds) {}

    @Override
    public Integer getTimeout() {
      return null;
    }

    private void requireActive() {
      if (!active.get()) {
        throw new IllegalStateException("No active transaction for " + name);
      }
    }
  }
}
