package com.jsgalactic.axiom.micrometer;

import com.jsgalactic.axiom.observability.Metrics;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Pattern;

/**
 * Maps the dependency-free {@link Metrics} SPI to an application-owned Micrometer {@link
 * MeterRegistry}. Counters become Micrometer counters, gauges become gauges backed by an {@link
 * AtomicLong}, and timers become Micrometer timers whose service-level objectives are {@link
 * #DEFAULT_BUCKETS}, the bounds of the in-memory registry in {@code axiom-metrics}.
 *
 * <p>Micrometer publishes through whatever registry and exporters the application installs, so this
 * adapter adds no renderer, Prometheus endpoint, StatsD client or JMX bridge. One instrument is
 * registered per name and tag set and reused by later lookups; a name always has one kind and one
 * set of tag keys, and using it otherwise throws {@link IllegalArgumentException}. Instances are
 * thread-safe and can be shared across requests.
 *
 * <p>Tag values stay the caller's responsibility: Axiom's runtime tags only with route templates,
 * methods and other fixed sets. The adapter does not truncate values or bound cardinality; use a
 * registry filter when untrusted tags are possible.
 */
public final class MicrometerMetrics implements Metrics {
  /**
   * Timer service-level objectives: 1 ms, 5 ms, 10 ms, 25 ms, 50 ms, 100 ms, 250 ms, 500 ms, 1 s,
   * 2.5 s, 5 s and 10 s. They match the buckets of {@code MetricsRegistry.DEFAULT_BUCKETS}.
   */
  public static final List<Duration> DEFAULT_BUCKETS =
      List.of(
          Duration.ofMillis(1),
          Duration.ofMillis(5),
          Duration.ofMillis(10),
          Duration.ofMillis(25),
          Duration.ofMillis(50),
          Duration.ofMillis(100),
          Duration.ofMillis(250),
          Duration.ofMillis(500),
          Duration.ofSeconds(1),
          Duration.ofMillis(2500),
          Duration.ofSeconds(5),
          Duration.ofSeconds(10));

  private static final Pattern NAME = Pattern.compile("[a-z][a-z0-9_]*(\\.[a-z][a-z0-9_]*)*");
  private static final Pattern KEY = Pattern.compile("[a-z][a-z0-9_]*");

  private enum Kind {
    COUNTER,
    GAUGE,
    TIMER
  }

  private record Family(Kind kind, List<String> keys) {}

  private record Key(String name, Tags tags) {}

  private final MeterRegistry registry;
  private final Map<String, Family> families = new HashMap<>();
  private final ConcurrentHashMap<Key, Counter> counters = new ConcurrentHashMap<>();
  private final ConcurrentHashMap<Key, Gauge> gauges = new ConcurrentHashMap<>();
  private final ConcurrentHashMap<Key, Timer> timers = new ConcurrentHashMap<>();

  private MicrometerMetrics(MeterRegistry registry) {
    this.registry = registry;
  }

  /**
   * Creates an adapter that registers instruments in the supplied registry. The registry's
   * lifecycle, naming conventions, filters and exporters remain the application's responsibility.
   *
   * @param registry meter registry that owns the instruments
   * @return an empty adapter
   */
  public static MicrometerMetrics create(MeterRegistry registry) {
    Objects.requireNonNull(registry, "registry");
    return new MicrometerMetrics(registry);
  }

  @Override
  public Counter counter(String name, String... tags) {
    var key = key(Kind.COUNTER, name, tags);
    return counters.computeIfAbsent(
        key, k -> MicrometerInstruments.counter(registry, k.name(), k.tags()));
  }

  @Override
  public Gauge gauge(String name, String... tags) {
    var key = key(Kind.GAUGE, name, tags);
    return gauges.computeIfAbsent(
        key, k -> MicrometerInstruments.gauge(registry, k.name(), k.tags()));
  }

  @Override
  public Timer timer(String name, String... tags) {
    var key = key(Kind.TIMER, name, tags);
    return timers.computeIfAbsent(
        key, k -> MicrometerInstruments.timer(registry, k.name(), k.tags()));
  }

  private Key key(Kind kind, String name, String[] tags) {
    Objects.requireNonNull(name, "name");
    if (!NAME.matcher(name).matches()) {
      throw new IllegalArgumentException("Invalid metric name: " + name);
    }
    if ((tags.length & 1) != 0) {
      throw new IllegalArgumentException("Tags must be alternating keys and values");
    }
    var keys = new ArrayList<String>();
    for (int i = 0; i < tags.length; i += 2) {
      var tagKey = Objects.requireNonNull(tags[i], "tag key");
      if (!KEY.matcher(tagKey).matches()) {
        throw new IllegalArgumentException("Invalid tag key: " + tagKey);
      }
      Objects.requireNonNull(tags[i + 1], "tag value");
      if (keys.contains(tagKey)) {
        throw new IllegalArgumentException("Duplicate tag key: " + tagKey);
      }
      keys.add(tagKey);
    }
    var family = new Family(kind, List.copyOf(keys));
    synchronized (families) {
      var previous = families.putIfAbsent(name, family);
      if (previous != null && !previous.equals(family)) {
        throw new IllegalArgumentException(
            "Metric name '" + name + "' is already used with a different kind or tag keys");
      }
    }
    return new Key(name, Tags.of(tags));
  }
}
