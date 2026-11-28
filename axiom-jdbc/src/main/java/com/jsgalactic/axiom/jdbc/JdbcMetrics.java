package com.jsgalactic.axiom.jdbc;

import com.jsgalactic.axiom.observability.Metrics;
import com.jsgalactic.axiom.routing.Route;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * The measurements of one gate. Instruments are resolved once per series and then reused, and a
 * failing {@link Metrics} implementation never fails a request.
 */
final class JdbcMetrics {
  static final String ADMISSION_REJECTED = "axiom.admission.rejected";
  static final String REASON = "db_capacity";
  static final String GATE_ACQUIRED = "axiom.jdbc.gate.acquired";
  static final String GATE_RELEASED = "axiom.jdbc.gate.released";
  static final String GATE_WAIT = "axiom.jdbc.gate.wait";
  static final String CONNECTIONS_ACQUIRED = "axiom.jdbc.connections.acquired";
  static final String CONNECTIONS_RELEASED = "axiom.jdbc.connections.released";
  static final String CONNECTIONS_WAITED = "axiom.jdbc.connections.waited";
  static final String CONNECTIONS_WAIT = "axiom.jdbc.connections.wait";

  /** Most routes with their own series; later routes share one {@code other} series. */
  static final int MAX_SERIES = 64;

  private static final System.Logger LOG = System.getLogger(JdbcMetrics.class.getName());

  private final Metrics metrics;
  private final AtomicBoolean reported = new AtomicBoolean();
  private final ConcurrentHashMap<Route, Series> series = new ConcurrentHashMap<>();
  private final Series overflow = new Series(this, "other", "other");

  JdbcMetrics(Metrics metrics) {
    this.metrics = metrics;
  }

  /** Returns the bounded series of a matched route, or the overflow series when none matched. */
  Series series(Route route) {
    if (route == null) {
      return overflow;
    }
    var found = series.get(route);
    if (found != null) {
      return found;
    }
    if (series.size() >= MAX_SERIES) {
      return overflow;
    }
    return series.computeIfAbsent(route, key -> new Series(this, key.method(), key.path()));
  }

  Metrics.Counter counter(String name, String... tags) {
    try {
      return metrics.counter(name, tags);
    } catch (RuntimeException failure) {
      dropped(failure);
      return Metrics.NOOP.counter(name, tags);
    }
  }

  Metrics.Timer timer(String name, String... tags) {
    try {
      return metrics.timer(name, tags);
    } catch (RuntimeException failure) {
      dropped(failure);
      return Metrics.NOOP.timer(name, tags);
    }
  }

  private void dropped(RuntimeException failure) {
    if (reported.compareAndSet(false, true)) {
      LOG.log(
          System.Logger.Level.WARNING,
          "The metrics implementation failed; this and later failing measurements are dropped",
          failure);
    }
  }

  /** Cached instruments of one bounded endpoint. */
  static final class Series {
    final String method;
    final String route;
    final Metrics.Counter acquired;
    final Metrics.Counter released;
    final Metrics.Counter rejected;
    final Metrics.Timer wait;

    Series(JdbcMetrics owner, String method, String route) {
      this.method = method;
      this.route = route;
      acquired = owner.counter(GATE_ACQUIRED, "method", method, "route", route);
      released = owner.counter(GATE_RELEASED, "method", method, "route", route);
      rejected =
          owner.counter(ADMISSION_REJECTED, "method", method, "route", route, "reason", REASON);
      wait = owner.timer(GATE_WAIT, "method", method, "route", route);
    }

    void rejected() {
      rejected.increment();
    }
  }
}
