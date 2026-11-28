package com.jsgalactic.axiom.jdbc;

import com.jsgalactic.axiom.application.Application;
import com.jsgalactic.axiom.context.Context;
import com.jsgalactic.axiom.context.Middleware;
import com.jsgalactic.axiom.error.ServiceUnavailableException;
import com.jsgalactic.axiom.http.Response;
import com.jsgalactic.axiom.observability.Health;
import com.jsgalactic.axiom.observability.HealthCheck;
import com.jsgalactic.axiom.observability.Metrics;
import com.jsgalactic.axiom.routing.Route;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.sql.DataSource;

/**
 * Bounds the database concurrency of an Axiom application around a source the application owns and
 * configures.
 *
 * <pre>{@code
 * var jdbc = Jdbc.builder(app, applicationOwnedDataSource)
 *         .maxConnections(16)
 *         .routeLimit(ordersRoute, 4)
 *         .readiness(Health.builder(app))
 *         .build()
 *         .register();
 * try (var connection = jdbc.dataSource().getConnection()) {
 *   // ordinary JDBC; the gate has already bounded this request
 * }
 * }</pre>
 *
 * <p>Registered as middleware with {@link #register()} or {@link #middleware()}, the gate acquires
 * one permit before the rest of the chain runs and releases it once the response is available. A
 * mapped error response passes through {@link #afterError}, which also releases a permit; both
 * paths are idempotent, so no permit is held after the request and none is released twice. When no
 * permit is available within the configured bounded wait, the handler is not run: the answer is a
 * 503 problem response with code {@code db_capacity}, counted on {@code axiom.admission.rejected}
 * with the fixed reason tag {@code db_capacity}. An aggregate cap and optional per-route caps are
 * enforced together; a per-route cap is never larger than the aggregate cap.
 *
 * <p><strong>Ownership.</strong> The {@link DataSource} passed to {@link #builder} belongs to the
 * application. The gate and its wrapper never close it, never change its settings and never close a
 * connection the application checked out except through that connection's own {@code close()}.
 * Building a gate registers a drain action that stops new permits and a close action that closes
 * still-open wrapped connections only when {@link Builder#closeConnectionsOnShutdown()} was called.
 * Without that opt-in, application close touches no connection and no source.
 *
 * <p>The wrapper returned by {@link #dataSource()} counts checkouts, releases and contention on the
 * existing {@link Metrics} SPI with one bounded {@code name} tag. The gate's own series carry a
 * method and route template from a bounded set. Health probes use the unwrapped source, so they do
 * not consume permits or appear in the connection counters. Instances are immutable and thread-safe
 * after construction; the source must be thread-safe too.
 */
public final class Jdbc implements Middleware, AutoCloseable {
  /** Problem code and bounded rejection reason used when a permit is unavailable. */
  public static final String REJECTION_CODE = "db_capacity";

  /** Name of the readiness check added by {@link Builder#readiness(Health.Builder)}. */
  public static final String HEALTH_NAME = "database";

  private final Application application;
  private final DataSource source;
  private final JdbcOptions options;
  private final JdbcMetrics metrics;
  private final JdbcDataSource dataSource;
  private final Semaphore global;
  private final Map<Route, Semaphore> routes;
  private final AtomicBoolean draining = new AtomicBoolean();
  private final ThreadLocal<Lease> current = new ThreadLocal<>();

  private Jdbc(Builder builder) {
    application = builder.application;
    source = builder.source;
    options =
        new JdbcOptions(
            builder.name,
            builder.maxConnections,
            builder.maxWait,
            builder.probeQuery,
            builder.probeTimeout,
            builder.routeLimits,
            builder.ownConnections);
    Metrics destination = builder.metrics == null ? application.metrics() : builder.metrics;
    metrics = new JdbcMetrics(destination);
    dataSource = new JdbcDataSource(source, options, destination);
    global = new Semaphore(options.maxConnections(), true);
    var configured = new HashMap<Route, Semaphore>();
    options
        .routeLimits()
        .forEach((route, limit) -> configured.put(route, new Semaphore(limit, true)));
    routes = Map.copyOf(configured);
    application.onDrain(() -> draining.set(true));
    application.onClose(this::close);
    if (builder.health != null) {
      builder.health.readiness(HEALTH_NAME, healthCheck());
    }
  }

  /**
   * Starts a builder for one application-owned source. The application supplies its own driver,
   * connection settings and lifecycle; nothing is opened or configured here.
   *
   * @param application application whose lifecycle owns the gate
   * @param source application-owned source, never closed by the gate
   * @return a new builder
   * @throws NullPointerException if an argument is null
   */
  public static Builder builder(Application application, DataSource source) {
    return new Builder(
        Objects.requireNonNull(application, "application"),
        Objects.requireNonNull(source, "source"));
  }

  /**
   * Returns the validated bounds of this gate.
   *
   * @return immutable options
   */
  public JdbcOptions options() {
    return options;
  }

  /**
   * Returns the counted wrapper applications use for ordinary JDBC work. The delegate source is not
   * closed when this wrapper or the application closes.
   *
   * @return thread-safe source that counts checkouts and releases
   */
  public DataSource dataSource() {
    return dataSource;
  }

  /**
   * Returns the middleware that acquires and releases permits. It is this gate itself, so the
   * instance can be registered globally or on one group.
   *
   * @return thread-safe middleware
   */
  public Middleware middleware() {
    return this;
  }

  /**
   * Registers this gate as global application middleware. Routes registered later are covered as
   * well. An alternative is to pass {@link #middleware()} to one group or route.
   *
   * @return this gate
   * @throws IllegalStateException if the application is closed
   */
  public Jdbc register() {
    application.use(this);
    return this;
  }

  /**
   * Returns a readiness check that runs the bounded query from {@link JdbcOptions#probeQuery()} on
   * the unwrapped source. It is UP only when the statement returns at least one row. A driver
   * failure, an invalid statement, an interrupted probe or a timeout reports DOWN without the SQL,
   * the URL, credentials or the exception message leaving the check.
   *
   * @return check suitable for any named Axiom readiness hook
   */
  public HealthCheck healthCheck() {
    return () -> {
      try (var connection = source.getConnection();
          var statement = connection.createStatement()) {
        statement.setQueryTimeout(Math.max(1, (int) options.probeTimeout().getSeconds()));
        try (ResultSet result = statement.executeQuery(options.probeQuery())) {
          return result.next();
        }
      } catch (SQLException | RuntimeException failure) {
        return false;
      }
    };
  }

  /**
   * Acquires a permit before the rest of the chain and releases it once a response is available.
   * The permit is held while the handler runs, including any database work it does through {@link
   * #dataSource()}.
   *
   * @param context request context, used only for its matched route identity
   * @param next rest of the chain
   * @return the handler's response
   * @throws Exception if a permit cannot be acquired or the rest of the chain fails
   */
  @Override
  public Response handle(Context context, Next next) throws Exception {
    Lease lease = acquire(context);
    current.set(lease);
    try {
      return next.run();
    } finally {
      release(lease);
      current.remove();
    }
  }

  /**
   * Releases a permit for a response produced from an exception. {@link #handle} already released
   * it on every path, including failures that bypass error mapping, so this call is a no-op for
   * those requests and leaves the response unchanged.
   *
   * @param context failed request's context
   * @param response mapped error response
   * @return the unchanged response
   */
  @Override
  public Response afterError(Context context, Response response) {
    release(current.get());
    current.remove();
    return response;
  }

  /**
   * Stops new permits and closes the connections this gate tracks, if the application opted into
   * owning them. The application-provided {@code DataSource} is never closed. Idempotent.
   */
  @Override
  public void close() {
    draining.set(true);
    dataSource.close();
  }

  private Lease acquire(Context context) throws InterruptedException {
    long start = System.nanoTime();
    long deadline = start + options.maxWait().toNanos();
    var route = context.matchedRoute().orElse(null);
    var series = metrics.series(route);
    if (draining.get()) {
      throw full(series);
    }
    if (!global.tryAcquire(remaining(deadline), TimeUnit.NANOSECONDS)) {
      throw full(series);
    }
    Semaphore routeGate = route == null ? null : routes.get(route);
    if (routeGate != null && !routeGate.tryAcquire(remaining(deadline), TimeUnit.NANOSECONDS)) {
      global.release();
      throw full(series);
    }
    series.acquired.increment();
    return new Lease(global, routeGate, series, start);
  }

  private static long remaining(long deadline) {
    return Math.max(0L, deadline - System.nanoTime());
  }

  private ServiceUnavailableException full(JdbcMetrics.Series series) {
    series.rejected();
    return new ServiceUnavailableException(Duration.ofSeconds(1), REJECTION_CODE);
  }

  private static void release(Lease lease) {
    if (lease == null || !lease.once.compareAndSet(false, true)) {
      return;
    }
    if (lease.routeGate != null) {
      lease.routeGate.release();
    }
    lease.global.release();
    lease.series.released.increment();
    lease.series.wait.record(Math.max(0L, System.nanoTime() - lease.start));
  }

  /** One held permit and the series that recorded it. */
  private static final class Lease {
    final Semaphore global;
    final Semaphore routeGate;
    final JdbcMetrics.Series series;
    final long start;
    final AtomicBoolean once = new AtomicBoolean();

    Lease(Semaphore global, Semaphore routeGate, JdbcMetrics.Series series, long start) {
      this.global = global;
      this.routeGate = routeGate;
      this.series = series;
      this.start = start;
    }
  }

  /** Collects bounds and hooks before {@link #build()}. Not thread-safe. */
  public static final class Builder {
    private final Application application;
    private final DataSource source;
    private final Map<Route, Integer> routeLimits = new HashMap<>();
    private String name = JdbcOptions.DEFAULT.name();
    private int maxConnections = JdbcOptions.DEFAULT.maxConnections();
    private Duration maxWait = JdbcOptions.DEFAULT.maxWait();
    private String probeQuery = JdbcOptions.DEFAULT.probeQuery();
    private Duration probeTimeout = JdbcOptions.DEFAULT.probeTimeout();
    private boolean ownConnections = JdbcOptions.DEFAULT.closeConnectionsOnShutdown();
    private Metrics metrics;
    private Health.Builder health;

    private Builder(Application application, DataSource source) {
      this.application = application;
      this.source = source;
    }

    /**
     * Sets the bounded tag value of the wrapper's connection series. Defaults to {@code database}.
     *
     * @param name 1 to 64 lowercase letters, digits, {@code _}, {@code .} or {@code -}
     * @return this builder
     */
    public Builder name(String name) {
      this.name = Objects.requireNonNull(name, "name");
      return this;
    }

    /**
     * Sets the largest number of concurrent permits. Defaults to 16; valid values are 1 to 1024.
     *
     * @param maxConnections aggregate cap
     * @return this builder
     */
    public Builder maxConnections(int maxConnections) {
      this.maxConnections = maxConnections;
      return this;
    }

    /**
     * Sets how long a request waits for a permit before answering 503. Defaults to two seconds;
     * valid values are zero to 30 seconds, where zero refuses immediately when full.
     *
     * @param wait bounded wait
     * @return this builder
     */
    public Builder wait(Duration wait) {
      maxWait = Objects.requireNonNull(wait, "wait");
      return this;
    }

    /**
     * Sets the read-only statement used by {@link Jdbc#healthCheck()}. It must be one short {@code
     * SELECT} with no semicolon; the default is {@code SELECT 1}.
     *
     * @param probeQuery statement run on each health probe
     * @return this builder
     */
    public Builder probeQuery(String probeQuery) {
      this.probeQuery = Objects.requireNonNull(probeQuery, "probeQuery");
      return this;
    }

    /**
     * Sets the statement timeout of one health probe. Defaults to one second; valid values are one
     * to 30 seconds. The health builder's own probe timeout bounds the whole check as well.
     *
     * @param probeTimeout statement timeout
     * @return this builder
     */
    public Builder probeTimeout(Duration probeTimeout) {
      this.probeTimeout = Objects.requireNonNull(probeTimeout, "probeTimeout");
      return this;
    }

    /**
     * Caps one route independently of the aggregate. Both caps must have room; a route cap above
     * the aggregate is rejected at build time. A later call for the same route replaces the cap.
     *
     * @param route registered route identity
     * @param maxConnections route cap from 1 to the aggregate cap
     * @return this builder
     */
    public Builder routeLimit(Route route, int maxConnections) {
      routeLimits.put(Objects.requireNonNull(route, "route"), maxConnections);
      return this;
    }

    /**
     * Opts the application into owning the wrapped connections it leaves open: closing the gate or
     * the application then closes those connections. The delegate {@code DataSource} is never
     * closed either way, and the default is to touch nothing on close.
     *
     * @return this builder
     */
    public Builder closeConnectionsOnShutdown() {
      ownConnections = true;
      return this;
    }

    /**
     * Replaces the metrics destination. By default the gate and wrapper record through {@link
     * Application#metrics()}.
     *
     * @param metrics thread-safe metrics implementation
     * @return this builder
     */
    public Builder metrics(Metrics metrics) {
      this.metrics = Objects.requireNonNull(metrics, "metrics");
      return this;
    }

    /**
     * Adds a readiness check named {@code database} to a health builder that has not been built
     * yet. The check uses {@link Jdbc#healthCheck()} and is bounded by its own statement timeout.
     *
     * @param builder health builder
     * @return this builder
     */
    public Builder readiness(Health.Builder builder) {
      health = Objects.requireNonNull(builder, "builder");
      return this;
    }

    /**
     * Builds the gate and registers its lifecycle actions with the application. No middleware is
     * registered until {@link Jdbc#register()} or an explicit {@code app.use(jdbc.middleware())}.
     *
     * @return the configured gate
     * @throws IllegalArgumentException if a bound is invalid
     * @throws IllegalStateException if the application is closed
     */
    public Jdbc build() {
      return new Jdbc(this);
    }

    /**
     * Builds the gate and registers its middleware globally.
     *
     * @return the configured gate
     * @throws IllegalArgumentException if a bound is invalid
     * @throws IllegalStateException if the application is closed
     */
    public Jdbc register() {
      return build().register();
    }
  }
}
