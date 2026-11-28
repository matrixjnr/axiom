package com.jsgalactic.axiom.jdbc;

import java.io.PrintWriter;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.sql.Statement;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Logger;
import javax.sql.DataSource;

/** A source with no driver, no socket and no sleeps; every blocking call uses a latch. */
final class FakeDataSource implements DataSource {
  final AtomicInteger opened = new AtomicInteger();
  final AtomicInteger closed = new AtomicInteger();
  final AtomicBoolean failConnection = new AtomicBoolean();
  final AtomicBoolean failQuery = new AtomicBoolean();
  final AtomicBoolean up = new AtomicBoolean(true);
  final AtomicInteger queryTimeout = new AtomicInteger();
  final CountDownLatch queryEntered = new CountDownLatch(1);
  final CountDownLatch queryRelease = new CountDownLatch(1);
  volatile boolean blockQuery;

  @Override
  public Connection getConnection() throws SQLException {
    if (failConnection.get()) {
      throw new SQLException("jdbc:secret://db user=admin password=hunter2");
    }
    opened.incrementAndGet();
    return connection();
  }

  @Override
  public Connection getConnection(String user, String password) throws SQLException {
    return getConnection();
  }

  @Override
  public int getLoginTimeout() {
    return 3;
  }

  @Override
  public void setLoginTimeout(int seconds) {}

  @Override
  public PrintWriter getLogWriter() {
    return null;
  }

  @Override
  public void setLogWriter(PrintWriter out) {}

  @Override
  public Logger getParentLogger() throws SQLFeatureNotSupportedException {
    throw new SQLFeatureNotSupportedException();
  }

  @Override
  public <T> T unwrap(Class<T> type) throws SQLException {
    if (!type.isInstance(this)) {
      throw new SQLException("no such interface");
    }
    return type.cast(this);
  }

  @Override
  public boolean isWrapperFor(Class<?> type) {
    return type.isInstance(this);
  }

  private Connection connection() {
    return (Connection)
        Proxy.newProxyInstance(
            Connection.class.getClassLoader(),
            new Class<?>[] {Connection.class},
            new ConnectionHandler(this));
  }

  private static Object result(Method method) {
    Class<?> type = method.getReturnType();
    if (type == boolean.class) {
      return false;
    }
    if (type == int.class) {
      return 0;
    }
    if (type == long.class) {
      return 0L;
    }
    return null;
  }

  private static final class ConnectionHandler implements InvocationHandler {
    private final FakeDataSource owner;

    ConnectionHandler(FakeDataSource owner) {
      this.owner = owner;
    }

    @Override
    public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
      return switch (method.getName()) {
        case "close" -> {
          owner.closed.incrementAndGet();
          yield null;
        }
        case "isClosed" -> false;
        case "createStatement" ->
            Proxy.newProxyInstance(
                Statement.class.getClassLoader(),
                new Class<?>[] {Statement.class},
                new StatementHandler(owner));
        case "unwrap" -> args[0];
        case "isWrapperFor" -> true;
        default -> result(method);
      };
    }
  }

  private static final class StatementHandler implements InvocationHandler {
    private final FakeDataSource owner;

    StatementHandler(FakeDataSource owner) {
      this.owner = owner;
    }

    @Override
    public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
      return switch (method.getName()) {
        case "setQueryTimeout" -> {
          owner.queryTimeout.set((Integer) args[0]);
          yield null;
        }
        case "executeQuery" -> {
          if (owner.failQuery.get()) {
            throw new SQLException("SELECT * FROM secret_table failed with password=hunter2");
          }
          if (owner.blockQuery) {
            owner.queryEntered.countDown();
            if (!owner.queryRelease.await(5, TimeUnit.SECONDS)) {
              throw new SQLException("probe did not resume");
            }
          }
          yield Proxy.newProxyInstance(
              ResultSet.class.getClassLoader(),
              new Class<?>[] {ResultSet.class},
              new ResultSetHandler(owner));
        }
        case "close" -> null;
        default -> result(method);
      };
    }
  }

  private static final class ResultSetHandler implements InvocationHandler {
    private final FakeDataSource owner;

    ResultSetHandler(FakeDataSource owner) {
      this.owner = owner;
    }

    @Override
    public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
      return switch (method.getName()) {
        case "next" -> owner.up.get();
        case "close" -> null;
        default -> result(method);
      };
    }
  }
}
