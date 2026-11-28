package com.jsgalactic.axiom.jdbc;

import com.jsgalactic.axiom.routing.Route;
import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Immutable bounds of a {@link Jdbc} gate.
 *
 * <p>Every field is validated when an instance is created: the metric name is a bounded tag value,
 * the connection caps and the bounded wait are positive and limited, the health probe is a single
 * short {@code SELECT} statement, and the per-route caps never exceed the aggregate cap. The route
 * limits are a fixed set of application-chosen routes, so the gate's metric series stay bounded.
 *
 * @param name bounded metric tag value for the wrapped DataSource, default {@code database}
 * @param maxConnections largest number of concurrent database permits, from 1 to 1024
 * @param maxWait longest time a request waits for a permit, from zero to 30 seconds
 * @param probeQuery single short read-only statement used by the health check
 * @param probeTimeout upper bound of one health probe statement, from one to 30 seconds
 * @param routeLimits optional per-route caps, each from 1 to {@code maxConnections}
 * @param closeConnectionsOnShutdown whether application close closes still-open wrapped connections
 */
public record JdbcOptions(
    String name,
    int maxConnections,
    Duration maxWait,
    String probeQuery,
    Duration probeTimeout,
    Map<Route, Integer> routeLimits,
    boolean closeConnectionsOnShutdown) {
  /** Longest permitted wait for a permit. */
  public static final Duration MAX_WAIT = Duration.ofSeconds(30);

  /** Longest permitted health statement timeout. */
  public static final Duration MAX_PROBE_TIMEOUT = Duration.ofSeconds(30);

  /** Most routes with their own cap. Later configured routes are refused. */
  public static final int MAX_ROUTE_LIMITS = 64;

  /** Longest permitted health statement. */
  public static final int MAX_PROBE_QUERY_LENGTH = 128;

  private static final Pattern NAME = Pattern.compile("[a-z0-9][a-z0-9_.-]{0,63}");

  /** Bounds used when none are supplied. */
  public static final JdbcOptions DEFAULT =
      new JdbcOptions(
          "database",
          16,
          Duration.ofSeconds(2),
          "SELECT 1",
          Duration.ofSeconds(1),
          Map.of(),
          false);

  /**
   * Validates the bounds and copies the route limits.
   *
   * @throws IllegalArgumentException if a bound is out of range or the probe is not one short
   *     select
   * @throws NullPointerException if a reference field or route key is null
   */
  public JdbcOptions {
    Objects.requireNonNull(name, "name");
    Objects.requireNonNull(maxWait, "maxWait");
    Objects.requireNonNull(probeQuery, "probeQuery");
    Objects.requireNonNull(probeTimeout, "probeTimeout");
    Objects.requireNonNull(routeLimits, "routeLimits");
    if (!NAME.matcher(name).matches()) {
      throw new IllegalArgumentException("name is 1 to 64 lowercase tag characters: " + name);
    }
    if (maxConnections < 1 || maxConnections > 1024) {
      throw new IllegalArgumentException("maxConnections must be from 1 to 1024");
    }
    if (maxWait.isNegative() || maxWait.compareTo(MAX_WAIT) > 0) {
      throw new IllegalArgumentException("wait must be from zero to " + MAX_WAIT);
    }
    if (probeQuery.isBlank()
        || probeQuery.length() > MAX_PROBE_QUERY_LENGTH
        || probeQuery.indexOf(';') >= 0
        || !probeQuery.regionMatches(true, 0, "SELECT ", 0, 7)) {
      throw new IllegalArgumentException("probeQuery must be one short SELECT statement");
    }
    if (probeTimeout.isZero()
        || probeTimeout.isNegative()
        || probeTimeout.compareTo(MAX_PROBE_TIMEOUT) > 0) {
      throw new IllegalArgumentException(
          "probeTimeout must be positive and at most " + MAX_PROBE_TIMEOUT);
    }
    if (routeLimits.size() > MAX_ROUTE_LIMITS) {
      throw new IllegalArgumentException("At most " + MAX_ROUTE_LIMITS + " route limits");
    }
    for (var entry : routeLimits.entrySet()) {
      Objects.requireNonNull(entry.getKey(), "route");
      Objects.requireNonNull(entry.getValue(), "route limit");
      int limit = entry.getValue();
      if (limit < 1 || limit > maxConnections) {
        throw new IllegalArgumentException("A route limit must be from 1 to maxConnections");
      }
    }
    routeLimits = Map.copyOf(routeLimits);
  }
}
