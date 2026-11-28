package example.modules;

import com.jsgalactic.axiom.Axiom;
import com.jsgalactic.axiom.application.Application;
import com.jsgalactic.axiom.context.Handler;
import com.jsgalactic.axiom.jdbc.Jdbc;
import java.io.PrintWriter;
import java.sql.SQLException;
import java.time.Duration;
import java.util.logging.Logger;
import javax.sql.DataSource;

/**
 * Bounds database work to one request at a time with an application-owned {@link DataSource}. The
 * source here has no driver: a real application passes its own pool and uses ordinary JDBC in the
 * handler. The gate is what keeps too many requests from reaching that pool at once.
 */
public final class JdbcExample {
  private JdbcExample() {}

  /**
   * Builds an application with a one-permit gate around its routes.
   *
   * @param source the application's source, never closed by the gate
   * @param handler the work to protect, for example a handler that checks out a connection
   * @return a configured application, not yet started
   */
  public static Application create(DataSource source, Handler handler) {
    var app = Axiom.create();
    app.get("/orders", handler);
    Jdbc.builder(app, source).maxConnections(1).wait(Duration.ofMillis(200)).build().register();
    return app;
  }

  /**
   * Serves the gated route on http://127.0.0.1:8080/ until the process stops.
   *
   * @param args unused
   * @throws Exception if the listener cannot start
   */
  public static void main(String[] args) throws Exception {
    var app = create(new ExampleDataSource(), ctx -> ctx.text("order placed")).closeOnJvmShutdown();
    var server = app.listen(8080);
    System.out.println("Gated JDBC work on http://127.0.0.1:8080/orders");
    server.termination().toCompletableFuture().join();
  }

  /** A source with no driver and no socket, used only to show application ownership. */
  static final class ExampleDataSource implements DataSource {
    @Override
    public java.sql.Connection getConnection() throws SQLException {
      throw new SQLException("This example has no database; a real application supplies a pool");
    }

    @Override
    public java.sql.Connection getConnection(String user, String password) throws SQLException {
      return getConnection();
    }

    @Override
    public PrintWriter getLogWriter() {
      return null;
    }

    @Override
    public void setLogWriter(PrintWriter out) {}

    @Override
    public void setLoginTimeout(int seconds) {}

    @Override
    public int getLoginTimeout() {
      return 0;
    }

    @Override
    public Logger getParentLogger() {
      return Logger.getLogger(ExampleDataSource.class.getName());
    }

    @Override
    public <T> T unwrap(Class<T> type) throws SQLException {
      if (!type.isInstance(this)) {
        throw new SQLException("Not a " + type.getName());
      }
      return type.cast(this);
    }

    @Override
    public boolean isWrapperFor(Class<?> type) {
      return type.isInstance(this);
    }
  }
}
