package com.jsgalactic.axiom.server.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

import com.jsgalactic.axiom.codec.spi.BodyCodec;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Registry rules: exact media types, no priority, conflicts fail, shared instances. */
class CodecsTest {
    /** Declares the given types; decoding returns the declaring codec's name. */
    private static class Named implements BodyCodec {
        private final String name;
        private final Set<String> types;

        Named(String name, String... types) { this.name = name; this.types = Set.of(types); }

        @Override public Set<String> mediaTypes() { return types; }
        @Override public <T> T decode(byte[] content, Class<T> type) { return type.cast(name); }
        @Override public byte[] encode(Object value) { return new byte[0]; }
        @Override public String toString() { return name; }
    }

    @Test void routesEachExactMediaTypeToItsCodecWhateverTheInstallationOrder() {
        var json = new Named("json", "application/json");
        var csv = new Named("csv", "text/csv", "application/csv");
        for (var order : List.of(List.<BodyCodec>of(json, csv), List.<BodyCodec>of(csv, json))) {
            var codecs = Codecs.of(order);
            assertThat(codecs.forMediaType("application/json")).isSameAs(json);
            assertThat(codecs.forMediaType("text/csv")).isSameAs(csv);
            assertThat(codecs.forMediaType("application/csv")).isSameAs(csv);
        }
    }

    @Test void doesNotMatchWildcardsSuffixesOrNeighboursOfADeclaredType() {
        var codecs = Codecs.of(List.of(new Named("json", "application/json")));
        for (var other : List.of("application/vnd.api+json", "application/problem+json", "application/*", "*/*",
                "application/jsonp", "text/json", "application/json; charset=utf-8", "APPLICATION/JSON")) {
            assertThat(codecs.forMediaType(other)).as(other).isNull();
        }
    }

    @Test void failsWhenTwoCodecsClaimTheSameTypeNamingBothAndTheType() {
        var first = new Named("first", "application/json");
        var second = new Named("second", "text/csv", "application/json");
        for (var order : List.of(List.<BodyCodec>of(first, second), List.<BodyCodec>of(second, first))) {
            assertThatIllegalStateException().isThrownBy(() -> Codecs.of(order))
                    .withMessageContaining("application/json")
                    .withMessageContaining(Named.class.getName())
                    .withMessageContaining("install only one");
        }
        // The same instance listed twice still claims its types twice.
        assertThatIllegalStateException().isThrownBy(() -> Codecs.of(List.of(first, first)))
                .withMessageContaining("application/json");
    }

    @ParameterizedTest
    @ValueSource(strings = {"application/*", "*/*", "*/json", "Application/Json", "application/json; charset=utf-8",
            "application", "application/", "/json", "application/json,text/csv", "application/vnd.api+json ", ""})
    void rejectsDeclarationsThatAreNotAnExactLowercaseMediaType(String declared) {
        assertThatIllegalStateException().isThrownBy(() -> Codecs.of(List.of(new Named("bad", declared))))
                .withMessageContaining("invalid media type");
    }

    @Test void acceptsStructuredSuffixTypesDeclaredExplicitly() {
        var codec = new Named("api", "application/vnd.api+json", "application/merge-patch+json");
        var codecs = Codecs.of(List.of(codec));
        assertThat(codecs.forMediaType("application/vnd.api+json")).isSameAs(codec);
        assertThat(codecs.forMediaType("application/merge-patch+json")).isSameAs(codec);
        assertThat(codecs.forMediaType("application/json")).isNull();
    }

    /** Every caller must reach the same instance at the same time: the registry never serializes calls. */
    @Test void handsTheSameInstanceToConcurrentCallers() throws Exception {
        int callers = 16;
        var barrier = new CyclicBarrier(callers);
        var overlapped = new java.util.concurrent.atomic.AtomicInteger();
        var codec = new Named("shared", "application/json") {
            @Override public <T> T decode(byte[] content, Class<T> type) {
                try {
                    barrier.await(10, TimeUnit.SECONDS); // Returns only if all callers are inside decode at once.
                    overlapped.incrementAndGet();
                } catch (Exception failure) { throw new IllegalStateException(failure); }
                return super.decode(content, type);
            }
        };
        var codecs = Codecs.of(List.of(codec));
        var results = new ArrayList<String>();
        var threads = new ArrayList<Thread>();
        for (int i = 0; i < callers; i++) {
            threads.add(Thread.ofVirtual().unstarted(() -> {
                var result = codecs.forMediaType("application/json").decode(new byte[0], String.class);
                synchronized (results) { results.add(result); }
            }));
        }
        threads.forEach(Thread::start);
        for (var thread : threads) { thread.join(15_000); }
        assertThat(overlapped).hasValue(callers);
        assertThat(results).hasSize(callers).containsOnly("shared");
    }

    @Test void meteredCodecsTimeAndCountPerDeclaredMediaTypeAndRethrowFailures() {
        var metrics = new MeteringProbe();
        var codec = new Named("csv", "text/csv", "application/csv") {
            @Override public <T> T decode(byte[] content, Class<T> type) {
                if (content.length == 0) { throw new IllegalArgumentException("empty"); }
                return super.decode(content, type);
            }
            @Override public byte[] encode(Object value) { return new byte[] {1}; }
        };
        var codecs = Codecs.of(List.of(codec), metrics);
        var csv = codecs.forMediaType("text/csv");
        assertThat(csv.decode(new byte[] {1}, String.class)).isEqualTo("csv");
        assertThat(csv.decode(java.nio.ByteBuffer.wrap(new byte[] {1}), String.class)).isEqualTo("csv");
        assertThat(csv.encode("x")).containsExactly(1);
        org.assertj.core.api.Assertions.assertThatIllegalArgumentException()
                .isThrownBy(() -> csv.decode(new byte[0], String.class));
        codecs.forMediaType("application/csv").encode("y");
        assertThat(csv.mediaTypes()).containsExactlyInAnyOrder("text/csv", "application/csv");
        assertThat(csv.supports("text/csv")).isTrue();

        assertThat(metrics.observations("axiom.codec.duration", "operation", "decode", "media_type", "text/csv")).hasSize(3);
        assertThat(metrics.observations("axiom.codec.duration", "operation", "encode", "media_type", "text/csv")).hasSize(1);
        assertThat(metrics.observations("axiom.codec.duration", "operation", "encode", "media_type", "application/csv")).hasSize(1);
        assertThat(metrics.count("axiom.codec.failures", "operation", "decode", "media_type", "text/csv")).isEqualTo(1);
        assertThat(metrics.count("axiom.codec.failures", "operation", "encode", "media_type", "text/csv")).isZero();
    }

    @Test void withoutMetricsTheCodecIsNotWrappedAndAFailingMetricsImplementationIsIgnored() {
        var plain = new Named("json", "application/json");
        assertThat(Codecs.of(List.of(plain), com.jsgalactic.axiom.observability.Metrics.NOOP)
                .forMediaType("application/json")).isSameAs(plain);
        var failing = new com.jsgalactic.axiom.observability.Metrics() {
            @Override public Counter counter(String name, String... tags) { throw new IllegalStateException(); }
            @Override public Gauge gauge(String name, String... tags) { throw new IllegalStateException(); }
            @Override public Timer timer(String name, String... tags) { throw new IllegalStateException(); }
        };
        assertThat(Codecs.of(List.of(plain), failing).forMediaType("application/json")).isSameAs(plain);
        var late = new com.jsgalactic.axiom.observability.Metrics() {
            @Override public Counter counter(String name, String... tags) { return new Counter() {
                @Override public void increment() { throw new IllegalStateException(); }
                @Override public void add(long amount) { throw new IllegalStateException(); }
            }; }
            @Override public Gauge gauge(String name, String... tags) { return delta -> { }; }
            @Override public Timer timer(String name, String... tags) { return nanos -> { throw new IllegalStateException(); }; }
        };
        var codec = Codecs.of(List.of(plain), late).forMediaType("application/json");
        assertThat(codec.decode(new byte[0], String.class)).isEqualTo("json");
        assertThat(codec.encode("x")).isEmpty();
    }
}
