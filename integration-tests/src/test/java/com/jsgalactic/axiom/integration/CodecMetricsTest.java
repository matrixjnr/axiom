package com.jsgalactic.axiom.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.jsgalactic.axiom.Axiom;
import com.jsgalactic.axiom.http.Body;
import com.jsgalactic.axiom.http.Request;
import com.jsgalactic.axiom.observability.Metrics;
import com.jsgalactic.axiom.test.TestClient;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

/** The real JSON codec, timed and counted per declared media type, never per request Content-Type. */
class CodecMetricsTest {
    private static final class Recorder implements Metrics {
        final Map<String, AtomicLong> counters = new ConcurrentHashMap<>();
        final Map<String, AtomicLong> observations = new ConcurrentHashMap<>();
        private static String key(String name, String... tags) { return name + java.util.Arrays.toString(tags); }
        @Override public Counter counter(String name, String... tags) {
            var value = counters.computeIfAbsent(key(name, tags), k -> new AtomicLong());
            return new Counter() {
                @Override public void increment() { value.incrementAndGet(); }
                @Override public void add(long amount) { value.addAndGet(amount); }
            };
        }
        @Override public Gauge gauge(String name, String... tags) { return delta -> { }; }
        @Override public Timer timer(String name, String... tags) {
            var value = observations.computeIfAbsent(key(name, tags), k -> new AtomicLong());
            return nanos -> value.incrementAndGet();
        }
    }

    @Test void decodingAndEncodingAreTimedAndFailuresCountedByDeclaredMediaType() throws Exception {
        var metrics = new Recorder();
        var app = Axiom.create().metrics(metrics);
        app.post("/echo", ctx -> ctx.status(201).json(ctx.body(Map.class)));
        try (var client = TestClient.start(app)) {
            var good = new Request("POST", "/echo", Map.of("Content-Type", "application/json; charset=utf-8"),
                    Body.of("application/json", "{\"a\":1}".getBytes()));
            assertThat(client.execute(good).status()).isEqualTo(201);
            var bad = new Request("POST", "/echo", Map.of("Content-Type", "application/json"),
                    Body.of("application/json", "{not json".getBytes()));
            assertThat(client.execute(bad).status()).isEqualTo(400);
        }
        var decode = "[operation, decode, media_type, application/json]";
        var encode = "[operation, encode, media_type, application/json]";
        assertThat(metrics.observations.get("axiom.codec.duration" + decode)).hasValue(2);
        assertThat(metrics.observations.get("axiom.codec.duration" + encode)).hasValue(1);
        assertThat(metrics.counters.get("axiom.codec.failures" + decode)).hasValue(1);
        assertThat(metrics.counters.get("axiom.codec.failures" + encode)).hasValue(0);
        assertThat(metrics.observations.keySet()).allMatch(key -> !key.contains("charset"));
    }
}
