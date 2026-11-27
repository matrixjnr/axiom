package com.jsgalactic.axiom.micrometer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.Arrays;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class MicrometerMetricsTest {
  @Test
  void mapsEachSpiInstrumentToARegistryMeter() {
    var registry = new SimpleMeterRegistry();
    var metrics = MicrometerMetrics.create(registry);

    var count = metrics.counter("app.jobs", "kind", "email");
    count.increment();
    metrics.counter("app.jobs", "kind", "email").add(4);
    count.add(-1);
    var level = metrics.gauge("app.depth");
    level.add(3);
    metrics.gauge("app.depth").add(-1);
    var timer = metrics.timer("app.work");
    timer.record(1_500_000);
    timer.record(-1);

    assertThat(registry.get("app.jobs").tag("kind", "email").counter().count()).isEqualTo(5);
    assertThat(registry.get("app.depth").gauge().value()).isEqualTo(2);
    var recorded = registry.get("app.work").timer();
    assertThat(recorded.count()).isEqualTo(1);
    assertThat(recorded.totalTime(TimeUnit.NANOSECONDS)).isEqualTo(1_500_000);
    assertThat(
            Arrays.stream(recorded.takeSnapshot().histogramCounts())
                .map(bucket -> bucket.bucket(TimeUnit.SECONDS))
                .anyMatch(bucket -> Math.abs(bucket - 0.005) < 1e-9))
        .isTrue();
  }

  @Test
  void keepsOneMeterPerSeriesAndRejectsInconsistentUse() {
    var metrics = MicrometerMetrics.create(new SimpleMeterRegistry());
    assertThat(metrics.counter("app.hits", "route", "/a"))
        .isSameAs(metrics.counter("app.hits", "route", "/a"));
    assertThat(metrics.counter("app.hits", "route", "/b"))
        .isNotSameAs(metrics.counter("app.hits", "route", "/a"));
    assertThatIllegalArgumentException().isThrownBy(() -> metrics.counter("Bad-Name"));
    assertThatIllegalArgumentException().isThrownBy(() -> metrics.counter("app.x", "odd"));
    assertThatIllegalArgumentException().isThrownBy(() -> metrics.counter("app.x", "Key", "v"));
    assertThatIllegalArgumentException()
        .isThrownBy(() -> metrics.counter("app.x", "k", "v", "k", "v"));
    assertThatIllegalArgumentException().isThrownBy(() -> metrics.gauge("app.hits"));
    assertThatIllegalArgumentException()
        .isThrownBy(() -> metrics.counter("app.hits", "other", "v"))
        .withMessageContaining("tag keys");
  }
}
