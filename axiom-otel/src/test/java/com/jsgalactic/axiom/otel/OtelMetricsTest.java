package com.jsgalactic.axiom.otel;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.sdk.metrics.SdkMeterProvider;
import io.opentelemetry.sdk.metrics.data.MetricData;
import io.opentelemetry.sdk.testing.exporter.InMemoryMetricReader;
import java.util.Collection;
import org.junit.jupiter.api.Test;

class OtelMetricsTest {
  @Test
  void mapsEachSpiInstrumentToAnOtelInstrument() {
    var reader = InMemoryMetricReader.create();
    var provider = SdkMeterProvider.builder().registerMetricReader(reader).build();
    var metrics = OtelMetrics.create(provider);

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

    var all = reader.collectAllMetrics();
    assertThat(all)
        .extracting(MetricData::getName)
        .containsExactlyInAnyOrder("app.jobs", "app.depth", "app.work");
    var jobs = metric(all, "app.jobs");
    assertThat(jobs.getUnit()).isEqualTo("1");
    assertThat(jobs.getLongSumData().getPoints()).hasSize(1);
    var jobsPoint = jobs.getLongSumData().getPoints().iterator().next();
    assertThat(jobsPoint.getValue()).isEqualTo(5);
    assertThat(jobsPoint.getAttributes().get(AttributeKey.stringKey("kind"))).isEqualTo("email");

    var depth = metric(all, "app.depth");
    assertThat(depth.getLongSumData().getPoints()).hasSize(1);
    assertThat(depth.getLongSumData().getPoints().iterator().next().getValue()).isEqualTo(2);

    var work = metric(all, "app.work");
    assertThat(work.getUnit()).isEqualTo("s");
    var point = work.getHistogramData().getPoints().iterator().next();
    assertThat(point.getCount()).isEqualTo(1);
    assertThat(point.getSum()).isEqualTo(0.0015);
    assertThat(point.getBoundaries()).containsExactlyElementsOf(OtelMetrics.DEFAULT_BUCKETS);
    assertThat(point.getCounts()).hasSize(OtelMetrics.DEFAULT_BUCKETS.size() + 1);
    assertThat(point.getCounts().get(1)).isEqualTo(1);
    assertThat(all)
        .allSatisfy(data ->
            assertThat(data.getInstrumentationScopeInfo().getName()).isEqualTo(Tracing.SCOPE_NAME));
    provider.shutdown();
  }

  @Test
  void keepsOneInstrumentPerSeriesAndRejectsInconsistentUse() {
    var metrics = OtelMetrics.create(OpenTelemetry.noop());
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

  private static MetricData metric(Collection<MetricData> all, String name) {
    return all.stream().filter(data -> data.getName().equals(name)).findFirst().orElseThrow();
  }
}
