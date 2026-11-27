package com.jsgalactic.axiom.micrometer;

import com.jsgalactic.axiom.observability.Metrics;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import io.micrometer.core.instrument.Timer;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/** Creates Micrometer instruments that implement the Axiom SPI instrument interfaces. */
final class MicrometerInstruments {
  private MicrometerInstruments() {}

  static Metrics.Counter counter(MeterRegistry registry, String name, Tags tags) {
    return new Count(registry.counter(name, tags));
  }

  static Metrics.Gauge gauge(MeterRegistry registry, String name, Tags tags) {
    var value = registry.gauge(name, tags, new AtomicLong());
    // A filtered gauge is not published, but recording still has a well-defined value.
    return new Level(value == null ? new AtomicLong() : value);
  }

  static Metrics.Timer timer(MeterRegistry registry, String name, Tags tags) {
    var timer =
        Timer.builder(name)
            .tags(tags)
            .serviceLevelObjectives(MicrometerMetrics.DEFAULT_BUCKETS.toArray(Duration[]::new))
            .publishPercentileHistogram()
            .register(registry);
    return new Timing(timer);
  }

  private record Count(Counter counter) implements Metrics.Counter {
    @Override
    public void increment() {
      counter.increment();
    }

    @Override
    public void add(long amount) {
      if (amount > 0) {
        counter.increment(amount);
      }
    }
  }

  private record Level(AtomicLong value) implements Metrics.Gauge {
    @Override
    public void add(long delta) {
      value.addAndGet(delta);
    }
  }

  private record Timing(Timer timer) implements Metrics.Timer {
    @Override
    public void record(long nanos) {
      if (nanos > 0) {
        timer.record(nanos, TimeUnit.NANOSECONDS);
      }
    }
  }
}
