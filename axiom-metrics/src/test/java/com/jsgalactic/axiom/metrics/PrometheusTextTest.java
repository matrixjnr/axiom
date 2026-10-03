package com.jsgalactic.axiom.metrics;

import static org.assertj.core.api.Assertions.assertThat;

import com.jsgalactic.axiom.Axiom;
import com.jsgalactic.axiom.test.TestClient;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;

class PrometheusTextTest {
    private static final String SELF = """
            # HELP axiom_metrics_series Distinct series the registry stores.
            # TYPE axiom_metrics_series gauge
            axiom_metrics_series %d
            # HELP axiom_metrics_dropped_series_total Series refused because the registry was full.
            # TYPE axiom_metrics_dropped_series_total counter
            axiom_metrics_dropped_series_total %d
            """;

    @Test void anEmptyRegistryRendersOnlyItsOwnSize() {
        assertThat(PrometheusText.render(MetricsRegistry.create())).isEqualTo(SELF.formatted(0, 0));
    }

    @Test void exposesTheSeriesCountAndTheDroppedSeriesAsMetrics() {
        var registry = MetricsRegistry.create(2);
        registry.counter("app.a").increment();
        registry.counter("app.b").increment();
        registry.counter("app.c").increment();
        registry.counter("app.d").increment();
        assertThat(PrometheusText.render(registry))
                .endsWith(SELF.formatted(2, 2));
    }

    @Test void rendersCountersGaugesAndHistogramsInAStableOrder() {
        var registry = MetricsRegistry.create();
        registry.gauge("app.depth").add(7);
        registry.counter("app.jobs", "kind", "sms").add(2);
        registry.counter("app.jobs", "kind", "email").increment();
        var timer = registry.timer("app.work", "route", "/a");
        timer.record(2_000_000);
        timer.record(30_000_000);
        timer.record(20_000_000_000L);

        assertThat(PrometheusText.render(registry)).isEqualTo("""
                # TYPE app_depth gauge
                app_depth 7
                # TYPE app_jobs_total counter
                app_jobs_total{kind="email"} 1
                app_jobs_total{kind="sms"} 2
                # TYPE app_work_seconds histogram
                app_work_seconds_bucket{route="/a",le="0.001"} 0
                app_work_seconds_bucket{route="/a",le="0.005"} 1
                app_work_seconds_bucket{route="/a",le="0.01"} 1
                app_work_seconds_bucket{route="/a",le="0.025"} 1
                app_work_seconds_bucket{route="/a",le="0.05"} 2
                app_work_seconds_bucket{route="/a",le="0.1"} 2
                app_work_seconds_bucket{route="/a",le="0.25"} 2
                app_work_seconds_bucket{route="/a",le="0.5"} 2
                app_work_seconds_bucket{route="/a",le="1"} 2
                app_work_seconds_bucket{route="/a",le="2.5"} 2
                app_work_seconds_bucket{route="/a",le="5"} 2
                app_work_seconds_bucket{route="/a",le="10"} 2
                app_work_seconds_bucket{route="/a",le="+Inf"} 3
                app_work_seconds_sum{route="/a"} 20.032000000
                app_work_seconds_count{route="/a"} 3
                """ + SELF.formatted(4, 0));
    }

    @Test void usesConfiguredBucketsForOneTimerAndTheDefaultForTheOthers() {
        var registry = MetricsRegistry.builder()
                .timerBuckets("app.fast", List.of(Duration.ofNanos(500), Duration.ofMillis(2), Duration.ofSeconds(30)))
                .build();
        registry.timer("app.fast").record(1_000);
        registry.timer("app.fast").record(5_000_000_000L);
        registry.timer("app.slow").record(1_000);
        var text = PrometheusText.render(registry);
        assertThat(text).contains("app_fast_seconds_bucket{le=\"0.0000005\"} 0\n")
                .contains("app_fast_seconds_bucket{le=\"0.002\"} 1\n")
                .contains("app_fast_seconds_bucket{le=\"30\"} 2\n")
                .contains("app_fast_seconds_bucket{le=\"+Inf\"} 2\n")
                .doesNotContain("app_fast_seconds_bucket{le=\"0.001\"}")
                .contains("app_slow_seconds_bucket{le=\"0.001\"} 1\n")
                .contains("app_slow_seconds_bucket{le=\"10\"} 1\n");
    }

    @Test void aRegistryWideBucketSetAppliesToTimersWithoutTheirOwn() {
        var registry = MetricsRegistry.builder().timerBuckets(List.of(Duration.ofMillis(3), Duration.ofSeconds(1)))
                .timerBuckets("app.own", List.of(Duration.ofMillis(7))).build();
        registry.timer("app.any").record(1);
        registry.timer("app.own").record(1);
        var text = PrometheusText.render(registry);
        assertThat(text).contains("app_any_seconds_bucket{le=\"0.003\"} 1\n").contains("app_any_seconds_bucket{le=\"1\"} 1\n")
                .contains("app_own_seconds_bucket{le=\"0.007\"} 1\n").doesNotContain("app_own_seconds_bucket{le=\"1\"}");
        assertThat(registry.timerBuckets("app.own")).containsExactly(Duration.ofMillis(7));
        assertThat(registry.timerBuckets("app.other")).containsExactly(Duration.ofMillis(3), Duration.ofSeconds(1));
    }

    @Test void writesHelpLinesBeforeTheTypeOfEachFamilyOnce() {
        var registry = MetricsRegistry.builder().help("app.jobs", "Jobs run, by kind.")
                .help("app.depth", "Back\\slash only.").build();
        registry.counter("app.jobs", "kind", "sms").increment();
        registry.counter("app.jobs", "kind", "email").increment();
        registry.gauge("app.depth").add(1);
        registry.gauge("app.plain").add(1);
        var text = PrometheusText.render(registry);
        assertThat(text).contains("""
                # HELP app_depth Back\\\\slash only.
                # TYPE app_depth gauge
                app_depth 1
                # HELP app_jobs_total Jobs run, by kind.
                # TYPE app_jobs_total counter
                app_jobs_total{kind="email"} 1
                app_jobs_total{kind="sms"} 1
                # TYPE app_plain gauge
                """);
        assertThat(text.split("# HELP app_jobs_total", -1)).hasSize(2);
    }

    @Test void theRuntimesOwnMetricsCarryHelpWithoutConfiguration() {
        var registry = MetricsRegistry.create();
        registry.counter("axiom.http.requests", "method", "GET", "route", "/a", "status_class", "2xx").increment();
        assertThat(PrometheusText.render(registry)).contains(
                "# HELP axiom_http_requests_total Finished requests by route template and status class.\n"
                        + "# TYPE axiom_http_requests_total counter\n");
        var custom = MetricsRegistry.builder().help("axiom.http.requests", "Mine.").build();
        custom.counter("axiom.http.requests", "method", "GET", "route", "/a", "status_class", "2xx").increment();
        assertThat(PrometheusText.render(custom)).contains("# HELP axiom_http_requests_total Mine.\n");
    }

    @Test void untaggedTimersCarryOnlyTheBucketBound() {
        var registry = MetricsRegistry.create();
        registry.timer("app.work").record(1);
        assertThat(PrometheusText.render(registry)).contains("app_work_seconds_bucket{le=\"0.001\"} 1")
                .contains("app_work_seconds_sum 0.000000001").contains("app_work_seconds_count 1");
    }

    @Test void escapesTagValuesSoInputCannotInjectSeriesOrLines() {
        var registry = MetricsRegistry.create();
        registry.counter("app.hits", "route", "a\"b\\c\nd 1\n# TYPE evil counter").increment();
        var text = PrometheusText.render(registry);
        assertThat(text).contains("app_hits_total{route=\"a\\\"b\\\\c\\nd 1\\n# TYPE evil counter\"} 1\n");
        assertThat(text.lines()).noneMatch(line -> line.startsWith("# TYPE evil") || line.startsWith("d 1"));
    }

    @Test void theHandlerServesTheCurrentRegistryAsPrometheusText() throws Exception {
        var registry = MetricsRegistry.create();
        var app = Axiom.create().metrics(registry);
        app.get("/users/:id", ctx -> "user");
        app.get("/metrics", PrometheusText.handler(registry));
        try (var client = TestClient.start(app)) {
            client.get("/users/alice?token=secret");
            client.get("/users/bob");
            client.get("/nothing/42");
            var scrape = client.get("/metrics");
            assertThat(scrape.status()).isEqualTo(200);
            assertThat(scrape.headers()).containsEntry("Content-Type", PrometheusText.CONTENT_TYPE)
                    .containsEntry("Cache-Control", "no-store");
            var text = (String) scrape.body();
            assertThat(text)
                    .contains("axiom_http_requests_total{method=\"GET\",route=\"/users/:id\",status_class=\"2xx\"} 2\n")
                    .contains("axiom_http_requests_total{method=\"none\",route=\"unmatched\",status_class=\"4xx\"} 1\n")
                    .contains("axiom_http_request_duration_seconds_count{method=\"GET\",route=\"/users/:id\"} 2\n")
                    .contains("axiom_admission_active 1\n")
                    .doesNotContain("alice").doesNotContain("secret").doesNotContain("/nothing");
        }
    }
}
