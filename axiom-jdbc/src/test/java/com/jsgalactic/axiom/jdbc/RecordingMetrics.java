package com.jsgalactic.axiom.jdbc;

import com.jsgalactic.axiom.observability.Metrics;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/** Tiny Metrics SPI implementation that records series under deterministic names. */
final class RecordingMetrics implements Metrics {
  private final Map<String, AtomicLong> values = new ConcurrentHashMap<>();

  @Override
  public Counter counter(String name, String... tags) {
    AtomicLong value = values.computeIfAbsent(key(name, tags), ignored -> new AtomicLong());
    return new Counter() {
      @Override
      public void increment() {
        value.incrementAndGet();
      }

      @Override
      public void add(long amount) {
        if (amount > 0) {
          value.addAndGet(amount);
        }
      }
    };
  }

  @Override
  public Gauge gauge(String name, String... tags) {
    AtomicLong value = values.computeIfAbsent(key(name, tags), ignored -> new AtomicLong());
    return value::addAndGet;
  }

  @Override
  public Timer timer(String name, String... tags) {
    AtomicLong value = values.computeIfAbsent(key(name, tags), ignored -> new AtomicLong());
    return nanos -> {
      if (nanos > 0) {
        value.addAndGet(nanos);
      }
    };
  }

  long count(String name, String... tags) {
    AtomicLong value = values.get(key(name, tags));
    return value == null ? 0L : value.get();
  }

  private static String key(String name, String[] tags) {
    var key = new StringBuilder(name);
    for (int i = 0; i < tags.length; i += 2) {
      key.append('{').append(tags[i]).append('=').append(tags[i + 1]).append('}');
    }
    return key.toString();
  }
}
