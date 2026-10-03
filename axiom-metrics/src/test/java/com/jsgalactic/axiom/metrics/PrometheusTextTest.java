package com.jsgalactic.axiom.metrics;

import static org.assertj.core.api.Assertions.assertThat;

import com.jsgalactic.axiom.Axiom;
import com.jsgalactic.axiom.test.TestClient;
import org.junit.jupiter.api.Test;

class PrometheusTextTest {
    @Test void anEmptyRegistryRendersNothing() {
        assertThat(PrometheusText.render(MetricsRegistry.create())).isEmpty();
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
                """);
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
        assertThat(text.lines()).hasSize(2);
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
