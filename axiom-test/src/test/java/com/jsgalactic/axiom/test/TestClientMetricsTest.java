package com.jsgalactic.axiom.test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import com.jsgalactic.axiom.Axiom;
import com.jsgalactic.axiom.execution.AdmissionPolicy;
import com.jsgalactic.axiom.http.Request;
import com.jsgalactic.axiom.observability.Metrics;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

class TestClientMetricsTest {
    @Test
    void recordsRequestsByRouteTemplateAndNeverByRawPathOrInput() throws Exception {
        var metrics = new Recorder();
        var app = Axiom.create().metrics(metrics);
        app.get("/users/:id", ctx -> "user " + ctx.path("id"));
        app.get("/boom", ctx -> ctx.status(500).text("failed"));
        try (var client = TestClient.start(app)) {
            client.get("/users/alice");
            client.get("/users/bob?token=secret");
            client.get("/boom");
            client.get("/nothing/here/42");
            client.execute(new Request("PROPFIND", "/users/carol"));
        }
        assertThat(metrics.counters).containsOnlyKeys(
                "axiom.http.requests{method=GET,route=/users/:id,status_class=2xx}",
                "axiom.http.requests{method=GET,route=/boom,status_class=5xx}",
                "axiom.http.requests{method=none,route=unmatched,status_class=4xx}");
        assertThat(metrics.counters.get("axiom.http.requests{method=none,route=unmatched,status_class=4xx}").get())
                .isEqualTo(2); // 404 for the unknown path, 405 for PROPFIND on a routed path
        assertThat(metrics.counters.get("axiom.http.requests{method=GET,route=/users/:id,status_class=2xx}").get())
                .isEqualTo(2);
        assertThat(metrics.timers).containsOnlyKeys(
                "axiom.http.request.duration{method=GET,route=/users/:id}",
                "axiom.http.request.duration{method=GET,route=/boom}",
                "axiom.http.request.duration{method=none,route=unmatched}");
    }

    @Test
    void recordsAdmissionRejectionsAndQueueDepth() throws Exception {
        var metrics = new Recorder();
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var app = Axiom.create().metrics(metrics).admissionPolicy(AdmissionPolicy.reject(1));
        app.get("/slow", ctx -> { entered.countDown(); release.await(); return "done"; });
        try (var client = TestClient.start(app)) {
            var first = client.submit(Request.get("/slow"));
            assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(client.get("/slow").status()).isEqualTo(503);
            assertThat(metrics.gauges.get("axiom.admission.active{}").get()).isEqualTo(1);
            release.countDown();
            assertThat(first.get(5, TimeUnit.SECONDS).status()).isEqualTo(200);
        } finally { release.countDown(); }
        assertThat(metrics.counters.get("axiom.admission.rejected{method=GET,route=/slow,reason=capacity}").get())
                .isEqualTo(1);
        assertThat(metrics.counters.get("axiom.http.requests{method=GET,route=/slow,status_class=5xx}").get())
                .isEqualTo(1);
        assertThat(metrics.counters.get("axiom.http.requests{method=GET,route=/slow,status_class=2xx}").get())
                .isEqualTo(1);
    }

    @Test
    void metricsAreFrozenAtStartupAndDefaultToNoop() {
        var app = Axiom.create();
        assertThat(app.metrics()).isSameAs(Metrics.NOOP);
        assertThatNullPointerException().isThrownBy(() -> app.metrics(null));
        app.start();
        assertThatIllegalStateException().isThrownBy(() -> app.metrics(new Recorder()));
        app.close();
    }

    private static final class Recorder implements Metrics {
        final Map<String, AtomicLong> counters = new ConcurrentHashMap<>();
        final Map<String, AtomicLong> gauges = new ConcurrentHashMap<>();
        final Map<String, AtomicLong> timers = new ConcurrentHashMap<>();

        private static String key(String name, String... tags) {
            var key = new StringBuilder(name).append('{');
            for (int i = 0; i < tags.length; i += 2) {
                key.append(i > 0 ? "," : "").append(tags[i]).append('=').append(tags[i + 1]);
            }
            return key.append('}').toString();
        }

        @Override public Counter counter(String name, String... tags) {
            var value = counters.computeIfAbsent(key(name, tags), ignored -> new AtomicLong());
            return new Counter() {
                @Override public void increment() { value.incrementAndGet(); }
                @Override public void add(long amount) { value.addAndGet(amount); }
            };
        }

        @Override public Gauge gauge(String name, String... tags) {
            var value = gauges.computeIfAbsent(key(name, tags), ignored -> new AtomicLong());
            return value::addAndGet;
        }

        @Override public Timer timer(String name, String... tags) {
            var value = timers.computeIfAbsent(key(name, tags), ignored -> new AtomicLong());
            return nanos -> value.incrementAndGet();
        }
    }
}
