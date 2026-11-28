package com.jsgalactic.axiom.jdbc;

import com.jsgalactic.axiom.observability.Metrics;
import java.io.PrintWriter;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.LongSupplier;
import java.util.logging.Logger;
import javax.sql.DataSource;

/**
 * A {@link DataSource} that delegates every call to an application-owned source and records
 * connection checkouts through the Axiom {@link Metrics} SPI.
 *
 * <pre>{@code
 * DataSource counted = new JdbcDataSource(applicationOwnedDataSource, options, app.metrics());
 * }</pre>
 *
 * <p>The delegate is never closed by this wrapper, on any path. Each returned {@link Connection} is
 * a delegate that forwards to the real connection; the first {@code close()} on it counts one
 * release and closes the real connection. A later {@code close()} is a no-op. The {@code name} from
 * {@link JdbcOptions} is the only tag value on the connection series, so the series stay bounded
 * however many connections the application opens. No SQL, URL, user, password or request value is
 * ever recorded.
 *
 * <p>The {@code acquired} counter increases on every successful checkout. The {@code released}
 * counter increases once per returned connection that is closed. The {@code waited} counter
 * increases on a checkout that overlapped an outstanding checkout of this wrapper: it reports
 * contention seen here, not the delegate's internal queue depth. The {@code wait} timer records the
 * time spent inside the delegate's {@code getConnection}, so an application can see whether a wait
 * was a real one. Health probes use the unwrapped source and are not counted.
 *
 * <p>Instances are thread-safe. {@link #close()} never closes the delegate {@code DataSource}; it
 * closes only wrapped connections that are still open, and only when {@link
 * JdbcOptions#closeConnectionsOnShutdown()} is true.
 */
public final class JdbcDataSource implements DataSource, AutoCloseable {
  private static final System.Logger LOG = System.getLogger(JdbcDataSource.class.getName());

  private final javax.sql.DataSource delegate;
  private final JdbcOptions options;
  private final JdbcMetrics metrics;
  private final LongSupplier clock;
  private final LongAdder active = new LongAdder();
  private final Set<Connection> tracked = ConcurrentHashMap.newKeySet();
  private final Metrics.Counter acquired;
  private final Metrics.Counter released;
  private final Metrics.Counter waited;
  private final Metrics.Timer wait;

  /**
   * Wraps a source with checked-out connection counters.
   *
   * @param delegate application-owned source, never closed by this wrapper
   * @param options bounds and the bounded metric name
   * @param metrics where the counters and timer are recorded
   * @throws NullPointerException if an argument is null
   */
  public JdbcDataSource(DataSource delegate, JdbcOptions options, Metrics metrics) {
    this(delegate, options, metrics, System::nanoTime);
  }

  JdbcDataSource(DataSource delegate, JdbcOptions options, Metrics metrics, LongSupplier clock) {
    this.delegate = Objects.requireNonNull(delegate, "delegate");
    this.options = Objects.requireNonNull(options, "options");
    this.metrics = new JdbcMetrics(Objects.requireNonNull(metrics, "metrics"));
    this.clock = Objects.requireNonNull(clock, "clock");
    acquired = this.metrics.counter(JdbcMetrics.CONNECTIONS_ACQUIRED, "name", options.name());
    released = this.metrics.counter(JdbcMetrics.CONNECTIONS_RELEASED, "name", options.name());
    waited = this.metrics.counter(JdbcMetrics.CONNECTIONS_WAITED, "name", options.name());
    wait = this.metrics.timer(JdbcMetrics.CONNECTIONS_WAIT, "name", options.name());
  }

  /**
   * Checks out a connection from the delegate and returns a wrapper whose first {@code close()}
   * counts one release. Credentials are passed through and never recorded.
   *
   * @return a wrapped connection owned by the caller
   * @throws SQLException if the delegate cannot supply a connection
   */
  @Override
  public Connection getConnection() throws SQLException {
    return checkout(delegate::getConnection);
  }

  /**
   * Checks out a connection with the supplied credentials, recording the same series as {@link
   * #getConnection()}. The credentials are never recorded.
   *
   * @param user database user
   * @param password database password
   * @return a wrapped connection owned by the caller
   * @throws SQLException if the delegate cannot supply a connection
   */
  @Override
  public Connection getConnection(String user, String password) throws SQLException {
    return checkout(() -> delegate.getConnection(user, password));
  }

  /**
   * Returns the delegate's login timeout.
   *
   * @return the delegate's login timeout in seconds
   * @throws SQLException if the delegate fails
   */
  @Override
  public int getLoginTimeout() throws SQLException {
    return delegate.getLoginTimeout();
  }

  /**
   * Sets the delegate's login timeout.
   *
   * @param seconds timeout in seconds
   * @throws SQLException if the delegate fails
   */
  @Override
  public void setLoginTimeout(int seconds) throws SQLException {
    delegate.setLoginTimeout(seconds);
  }

  /**
   * Returns the delegate's log writer.
   *
   * @return the delegate's log writer, possibly null
   * @throws SQLException if the delegate fails
   */
  @Override
  public PrintWriter getLogWriter() throws SQLException {
    return delegate.getLogWriter();
  }

  /**
   * Sets the delegate's log writer.
   *
   * @param out log writer, possibly null
   * @throws SQLException if the delegate fails
   */
  @Override
  public void setLogWriter(PrintWriter out) throws SQLException {
    delegate.setLogWriter(out);
  }

  /**
   * Returns the delegate's parent logger.
   *
   * @return the delegate's parent logger
   * @throws SQLFeatureNotSupportedException if the delegate does not support it
   */
  @Override
  public Logger getParentLogger() throws SQLFeatureNotSupportedException {
    return delegate.getParentLogger();
  }

  /**
   * Unwraps this wrapper or asks the delegate. No SQL object is exposed by this wrapper itself.
   *
   * @param type requested interface
   * @param <T> requested interface type
   * @return an object of that type
   * @throws SQLException if the delegate cannot unwrap
   */
  @Override
  public <T> T unwrap(Class<T> type) throws SQLException {
    Objects.requireNonNull(type, "type");
    if (type.isInstance(this)) {
      return type.cast(this);
    }
    return delegate.unwrap(type);
  }

  /**
   * Reports whether this wrapper or its delegate implements a type.
   *
   * @param type requested interface
   * @return true if it can be unwrapped
   * @throws SQLException if the delegate fails
   */
  @Override
  public boolean isWrapperFor(Class<?> type) throws SQLException {
    Objects.requireNonNull(type, "type");
    return type.isInstance(this) || delegate.isWrapperFor(type);
  }

  /**
   * Closes the wrapped connections this wrapper still tracks, if {@link
   * JdbcOptions#closeConnectionsOnShutdown()} is true. It never closes the delegate {@code
   * DataSource}. Failures from the underlying connections are swallowed and reported by class name
   * only, so a driver message cannot leak a URL or credential to logs. Idempotent.
   */
  @Override
  public void close() {
    if (!options.closeConnectionsOnShutdown()) {
      return;
    }
    for (var connection : tracked) {
      try {
        connection.close();
      } catch (SQLException failure) {
        LOG.log(
            System.Logger.Level.WARNING,
            "A tracked JDBC connection could not be closed: " + failure.getClass().getSimpleName());
      }
    }
  }

  private Connection checkout(Connector connector) throws SQLException {
    boolean contended = active.sum() > 0;
    long begun = clock.getAsLong();
    Connection connection = connector.get();
    long elapsed = Math.max(0, clock.getAsLong() - begun);
    active.increment();
    acquired.increment();
    if (contended) {
      waited.increment();
    }
    wait.record(elapsed);
    Connection wrapped =
        (Connection)
            Proxy.newProxyInstance(
                Connection.class.getClassLoader(),
                new Class<?>[] {Connection.class},
                new LeasedConnection(connection, this));
    if (options.closeConnectionsOnShutdown()) {
      tracked.add(wrapped);
    }
    return wrapped;
  }

  private void released(Connection proxy) {
    active.decrement();
    tracked.remove(proxy);
    released.increment();
  }

  @FunctionalInterface
  private interface Connector {
    Connection get() throws SQLException;
  }

  /** Forwards every connection call and counts the first close exactly once. */
  private static final class LeasedConnection implements InvocationHandler {
    private final Connection delegate;
    private final JdbcDataSource owner;
    private final AtomicBoolean closed = new AtomicBoolean();

    LeasedConnection(Connection delegate, JdbcDataSource owner) {
      this.delegate = delegate;
      this.owner = owner;
    }

    @Override
    public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
      return switch (method.getName()) {
        case "close" -> close(proxy);
        case "isClosed" -> closed.get() || delegate.isClosed();
        case "equals" -> proxy == args[0];
        case "hashCode" -> System.identityHashCode(proxy);
        case "toString" -> "Axiom JDBC connection (" + owner.options.name() + ")";
        case "unwrap" -> unwrap(proxy, (Class<?>) args[0]);
        case "isWrapperFor" ->
            ((Class<?>) args[0]).isInstance(proxy) || delegate.isWrapperFor((Class<?>) args[0]);
        default -> forward(method, args);
      };
    }

    private Object close(Object proxy) throws SQLException {
      if (closed.compareAndSet(false, true)) {
        try {
          delegate.close();
        } finally {
          owner.released((Connection) proxy);
        }
      }
      return null;
    }

    private Object unwrap(Object proxy, Class<?> type) throws SQLException {
      return type.isInstance(proxy) ? proxy : delegate.unwrap(type);
    }

    private Object forward(Method method, Object[] args) throws Throwable {
      try {
        return method.invoke(delegate, args);
      } catch (InvocationTargetException failure) {
        throw failure.getCause();
      }
    }
  }
}
