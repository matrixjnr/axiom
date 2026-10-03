package com.jsgalactic.axiom.metrics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class MetricsRegistryTest {
    @Test void aSeriesIsOneInstrumentWhoseValuesAccumulate() {
        var registry = MetricsRegistry.create();
        var first = registry.counter("app.jobs", "kind", "email");
        first.increment();
        registry.counter("app.jobs", "kind", "email").add(4);
        first.add(-3);
        registry.counter("app.jobs", "kind", "sms").increment();
        assertThat(registry.counterValue("app.jobs", "kind", "email")).isEqualTo(5);
        assertThat(registry.counterValue("app.jobs", "kind", "sms")).isEqualTo(1);
        assertThat(registry.counterValue("app.jobs", "kind", "push")).isZero();
        assertThat(registry.seriesCount()).isEqualTo(2);

        var gauge = registry.gauge("app.depth");
        gauge.add(3);
        gauge.add(-1);
        assertThat(registry.gaugeValue("app.depth")).isEqualTo(2);

        var timer = registry.timer("app.work");
        timer.record(1_500_000);
        timer.record(-1);
        assertThat(registry.timerSnapshot("app.work")).isEqualTo(new MetricsRegistry.TimerSnapshot(1, 1_500_000));
        assertThat(registry.timerSnapshot("app.absent").count()).isZero();
    }

    @Test void rejectsInvalidNamesTagsAndInconsistentUse() {
        var registry = MetricsRegistry.create();
        assertThatIllegalArgumentException().isThrownBy(() -> registry.counter("Bad-Name"));
        assertThatIllegalArgumentException().isThrownBy(() -> registry.counter(null));
        assertThatIllegalArgumentException().isThrownBy(() -> registry.counter("a.b", "odd"));
        assertThatIllegalArgumentException().isThrownBy(() -> registry.counter("a.b", "Key", "v"));
        assertThatIllegalArgumentException().isThrownBy(() -> registry.counter("a.b", "le", "v"));
        assertThatIllegalArgumentException().isThrownBy(() -> registry.counter("a.b", "k", null));
        assertThatIllegalArgumentException().isThrownBy(() -> registry.counter("a.b", "k", "1", "k", "2"));
        registry.counter("a.c", "k", "v");
        assertThatIllegalArgumentException().isThrownBy(() -> registry.gauge("a.c", "k", "v"))
                .withMessageContaining("counter");
        assertThatIllegalArgumentException().isThrownBy(() -> registry.gauge("a.c", "k", "other"))
                .withMessageContaining("counter");
        assertThatIllegalArgumentException().isThrownBy(() -> registry.counter("a.c", "other", "v"))
                .withMessageContaining("tag keys");
        assertThatIllegalArgumentException().isThrownBy(() -> registry.counter("a.c"))
                .withMessageContaining("tag keys");
        registry.counter("x.y");
        assertThatIllegalArgumentException().isThrownBy(() -> registry.counter("x_y"))
                .withMessageContaining("same name");
        assertThatIllegalArgumentException().isThrownBy(() -> MetricsRegistry.create(0));
        assertThat(registry.seriesCount()).isEqualTo(2);
    }

    @Test void seriesAreBoundedAndRefusedSeriesStillWorkWithoutBeingStored() {
        var registry = MetricsRegistry.create(10);
        for (int i = 0; i < 1000; i++) {
            registry.counter("app.hits", "user", "user-" + i).increment(); // the mistake the bound contains
        }
        assertThat(registry.seriesCount()).isEqualTo(10);
        assertThat(registry.droppedSeries()).isEqualTo(990);
        registry.counter("app.hits", "user", "user-0").increment();
        assertThat(registry.counterValue("app.hits", "user", "user-0")).isEqualTo(2);
        assertThat(registry.counterValue("app.hits", "user", "user-999")).isZero();
        registry.gauge("app.other").add(1); // a new name is refused too, without failing
        assertThat(registry.gaugeValue("app.other")).isZero();
        assertThat(registry.seriesCount()).isEqualTo(10);
        assertThat(PrometheusText.render(registry).lines().filter(line -> line.startsWith("app_hits_total{")))
                .hasSize(10);
    }

    @Test void longTagValuesAreTruncated() {
        var registry = MetricsRegistry.create();
        var value = "x".repeat(MetricsRegistry.MAX_VALUE_LENGTH + 50);
        registry.counter("app.hits", "route", value).increment();
        registry.counter("app.hits", "route", value + "different tail").increment();
        assertThat(registry.seriesCount()).isEqualTo(1);
        assertThat(registry.counterValue("app.hits", "route", value)).isEqualTo(2);
    }

    @Test void timerBucketsSplitAtTheirBounds() {
        var registry = MetricsRegistry.create();
        var timer = registry.timer("app.work");
        timer.record(1_000_000);      // exactly 1 ms: the first bucket is inclusive
        timer.record(1_000_001);
        timer.record(11_000_000_000L);
        var series = (MetricsRegistry.TimerSeries) registry.sortedSeries().getFirst();
        assertThat(series.buckets[0].sum()).isEqualTo(1);
        assertThat(series.buckets[1].sum()).isEqualTo(1);
        assertThat(series.buckets[series.buckets.length - 1].sum()).isEqualTo(1);
    }

    @Test void concurrentUpdatesAreNeitherLostNorDuplicated() throws Exception {
        var registry = MetricsRegistry.create();
        int threads = 16;
        int perThread = 20_000;
        var ready = new CountDownLatch(threads);
        var start = new CountDownLatch(1);
        var workers = new ArrayList<Thread>();
        var failure = new java.util.concurrent.atomic.AtomicReference<Throwable>();
        for (int t = 0; t < threads; t++) {
            workers.add(Thread.ofPlatform().start(() -> {
                try {
                    ready.countDown();
                    start.await();
                    for (int i = 0; i < perThread; i++) {
                        // Every thread looks the series up itself: concurrent creation yields one series.
                        registry.counter("app.hits", "route", "/a").increment();
                        registry.gauge("app.depth").add(1);
                        registry.gauge("app.depth").add(-1);
                        registry.timer("app.work", "route", "/a").record(i);
                    }
                } catch (Throwable thrown) { failure.set(thrown); }
            }));
        }
        assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
        start.countDown();
        for (var worker : workers) { worker.join(); }
        assertThat(failure.get()).isNull();
        long total = (long) threads * perThread;
        assertThat(registry.seriesCount()).isEqualTo(3);
        assertThat(registry.counterValue("app.hits", "route", "/a")).isEqualTo(total);
        assertThat(registry.gaugeValue("app.depth")).isZero();
        assertThat(registry.timerSnapshot("app.work", "route", "/a").count()).isEqualTo(total);
        assertThat(registry.droppedSeries()).isZero();
    }

    @Test void rejectsInvalidBucketsAndHelp() {
        var builder = MetricsRegistry.builder();
        assertThatIllegalArgumentException().isThrownBy(() -> builder.timerBuckets(List.of()));
        assertThatIllegalArgumentException().isThrownBy(() -> builder.timerBuckets(null));
        assertThatIllegalArgumentException().isThrownBy(
                () -> builder.timerBuckets(List.of(Duration.ofMillis(5), Duration.ofMillis(5))));
        assertThatIllegalArgumentException().isThrownBy(
                () -> builder.timerBuckets(List.of(Duration.ofMillis(5), Duration.ofMillis(1))));
        assertThatIllegalArgumentException().isThrownBy(() -> builder.timerBuckets(List.of(Duration.ZERO)));
        assertThatIllegalArgumentException().isThrownBy(() -> builder.timerBuckets(List.of(Duration.ofSeconds(-1))));
        assertThatIllegalArgumentException().isThrownBy(() -> builder.timerBuckets(List.of(Duration.ofSeconds(Long.MAX_VALUE))));
        var tooMany = new ArrayList<Duration>();
        for (int i = 1; i <= MetricsRegistry.MAX_BUCKETS + 1; i++) { tooMany.add(Duration.ofMillis(i)); }
        assertThatIllegalArgumentException().isThrownBy(() -> builder.timerBuckets(tooMany));
        assertThatIllegalArgumentException().isThrownBy(() -> builder.timerBuckets("Bad-Name", List.of(Duration.ofMillis(1))));
        assertThatIllegalArgumentException().isThrownBy(() -> builder.help("app.x", ""));
        assertThatIllegalArgumentException().isThrownBy(() -> builder.help("app.x", "two\nlines"));
        assertThatIllegalArgumentException().isThrownBy(() -> builder.help("app.x", "x".repeat(MetricsRegistry.MAX_HELP_LENGTH + 1)));
        assertThatIllegalArgumentException().isThrownBy(() -> builder.help("Bad", "text"));
        assertThatIllegalArgumentException().isThrownBy(() -> builder.maxSeries(0));
        assertThat(builder.timerBuckets(tooMany.subList(0, MetricsRegistry.MAX_BUCKETS)).build()).isNotNull();
    }

    @Test void theBucketsOfASeriesAreFixedWhenItIsCreatedAndTheDefaultsAreDocumented() {
        var registry = MetricsRegistry.create();
        assertThat(registry.timerBuckets("app.any")).isEqualTo(MetricsRegistry.DEFAULT_BUCKETS)
                .hasSize(12).startsWith(Duration.ofMillis(1)).endsWith(Duration.ofSeconds(10));
        assertThat(registry.help("app.any")).isEmpty();
        var timer = registry.timer("app.any");
        timer.record(Duration.ofMillis(10).toNanos());
        timer.record(Duration.ofMillis(11).toNanos());
        assertThat(registry.timerSnapshot("app.any").count()).isEqualTo(2);
    }
}
