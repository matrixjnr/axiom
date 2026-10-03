package com.jsgalactic.axiom.lifecycle;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.function.BiConsumer;
import org.junit.jupiter.api.Test;

class ListenerOptionsTest {
    @Test void defaultsKeepTheDocumentedValues() {
        var defaults = ListenerOptions.defaults();
        assertThat(defaults.shutdownGrace()).isEqualTo(Duration.ofSeconds(5));
        assertThat(defaults.idleTimeout()).isEqualTo(Duration.ofSeconds(30));
        assertThat(defaults.headTimeout()).isEqualTo(Duration.ofSeconds(10));
        assertThat(defaults.responseTimeout()).isEqualTo(Duration.ofSeconds(30));
        assertThat(defaults.lingerTimeout()).isEqualTo(Duration.ofSeconds(2));
        assertThat(defaults.lingerQuietTimeout()).isEqualTo(Duration.ofMillis(500));
        assertThat(defaults.shutdownLingerTimeout()).isEqualTo(Duration.ofMillis(500));
        assertThat(defaults.maxDiscardedInput()).isEqualTo(16 * 1024 * 1024);
        assertThat(defaults.maxConnections()).isEqualTo(128);
        assertThat(defaults.maxLingeringConnections()).isEqualTo(32);
        assertThat(defaults.maxPipelinedRequests()).isEqualTo(8);
        assertThat(defaults.maxInFlightBodyBytes()).isEqualTo(64L * 1024 * 1024);
        assertThat(defaults.maxRequestLine()).isEqualTo(4096);
        assertThat(defaults.maxHeaderBytes()).isEqualTo(8192);
        assertThat(defaults.ioThreads()).isEqualTo(Math.max(2, Runtime.getRuntime().availableProcessors()));
        assertThat(ListenerOptions.builder().build().toString()).isEqualTo(defaults.toString());
    }

    @Test void builderSetsEverySettingAndToBuilderCopiesThem() {
        var options = ListenerOptions.builder().shutdownGrace(Duration.ofSeconds(1))
                .idleTimeout(Duration.ofSeconds(2)).headTimeout(Duration.ofSeconds(3))
                .responseTimeout(Duration.ofSeconds(4)).lingerTimeout(Duration.ofSeconds(5))
                .lingerQuietTimeout(Duration.ofSeconds(6)).shutdownLingerTimeout(Duration.ofSeconds(7))
                .maxDiscardedInput(8).maxConnections(9).maxLingeringConnections(10).maxPipelinedRequests(11)
                .maxInFlightBodyBytes(12).maxRequestLine(512).maxHeaderBytes(1024).ioThreads(3).build();
        assertThat(options.shutdownGrace()).isEqualTo(Duration.ofSeconds(1));
        assertThat(options.idleTimeout()).isEqualTo(Duration.ofSeconds(2));
        assertThat(options.headTimeout()).isEqualTo(Duration.ofSeconds(3));
        assertThat(options.responseTimeout()).isEqualTo(Duration.ofSeconds(4));
        assertThat(options.lingerTimeout()).isEqualTo(Duration.ofSeconds(5));
        assertThat(options.lingerQuietTimeout()).isEqualTo(Duration.ofSeconds(6));
        assertThat(options.shutdownLingerTimeout()).isEqualTo(Duration.ofSeconds(7));
        assertThat(options.maxDiscardedInput()).isEqualTo(8);
        assertThat(options.maxConnections()).isEqualTo(9);
        assertThat(options.maxLingeringConnections()).isEqualTo(10);
        assertThat(options.maxPipelinedRequests()).isEqualTo(11);
        assertThat(options.maxInFlightBodyBytes()).isEqualTo(12);
        assertThat(options.maxRequestLine()).isEqualTo(512);
        assertThat(options.maxHeaderBytes()).isEqualTo(1024);
        assertThat(options.ioThreads()).isEqualTo(3);
        assertThat(options.toBuilder().build().toString()).isEqualTo(options.toString());
        assertThat(options.toBuilder().maxConnections(1).build().maxConnections()).isEqualTo(1);
        assertThat(options.maxConnections()).isEqualTo(9);
    }

    @Test void acceptsTheBoundariesOfEveryRange() {
        var day = Duration.ofDays(1);
        var ms = Duration.ofMillis(1);
        var low = ListenerOptions.builder().shutdownGrace(Duration.ZERO).idleTimeout(ms).headTimeout(ms)
                .responseTimeout(ms).lingerTimeout(ms).lingerQuietTimeout(ms).shutdownLingerTimeout(ms)
                .maxDiscardedInput(0).maxConnections(1).maxLingeringConnections(0).maxPipelinedRequests(1)
                .maxRequestLine(256).maxHeaderBytes(256).ioThreads(1).build();
        assertThat(low.shutdownGrace()).isZero();
        var high = ListenerOptions.builder().shutdownGrace(day).idleTimeout(day).headTimeout(day)
                .responseTimeout(day).lingerTimeout(day).lingerQuietTimeout(day).shutdownLingerTimeout(day)
                .maxDiscardedInput(1 << 30).maxConnections(1_000_000).maxLingeringConnections(1_000_000)
                .maxPipelinedRequests(1024).maxInFlightBodyBytes(1L << 40).maxRequestLine(65_536).maxHeaderBytes(1024 * 1024).ioThreads(1024)
                .build();
        assertThat(high.maxHeaderBytes()).isEqualTo(1024 * 1024);
    }

    @Test void rejectsEveryValueOutsideItsRangeNamingTheSetting() {
        var tooLong = Duration.ofDays(1).plusMillis(1);
        var tooShort = Duration.ofNanos(999_999);
        check("shutdownGrace", (b, v) -> b.shutdownGrace(v), Duration.ofMillis(-1), tooLong);
        check("idleTimeout", (b, v) -> b.idleTimeout(v), Duration.ZERO, tooShort, tooLong);
        check("headTimeout", (b, v) -> b.headTimeout(v), Duration.ZERO, tooShort, tooLong);
        check("responseTimeout", (b, v) -> b.responseTimeout(v), Duration.ZERO, tooShort, tooLong);
        check("lingerTimeout", (b, v) -> b.lingerTimeout(v), Duration.ZERO, tooShort, tooLong);
        check("lingerQuietTimeout", (b, v) -> b.lingerQuietTimeout(v), Duration.ZERO, tooShort, tooLong);
        check("shutdownLingerTimeout", (b, v) -> b.shutdownLingerTimeout(v), Duration.ZERO, tooShort, tooLong);
        checkInt("maxDiscardedInput", (b, v) -> b.maxDiscardedInput(v), -1, (1 << 30) + 1);
        checkInt("maxConnections", (b, v) -> b.maxConnections(v), 0, -1, 1_000_001);
        checkInt("maxLingeringConnections", (b, v) -> b.maxLingeringConnections(v), -1, 1_000_001);
        checkInt("maxPipelinedRequests", (b, v) -> b.maxPipelinedRequests(v), 0, 1025);
        for (long value : new long[] {0, -1, (1L << 40) + 1}) {
            assertThatThrownBy(() -> ListenerOptions.builder().maxInFlightBodyBytes(value))
                    .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("maxInFlightBodyBytes");
        }
        checkInt("maxRequestLine", (b, v) -> b.maxRequestLine(v), 255, 65_537);
        checkInt("maxHeaderBytes", (b, v) -> b.maxHeaderBytes(v), 255, 1024 * 1024 + 1);
        checkInt("ioThreads", (b, v) -> b.ioThreads(v), 0, 1025);
    }

    @Test void rejectsNullDurationsAndKeepsEarlierValuesAfterARejection() {
        var builder = ListenerOptions.builder().maxConnections(7);
        assertThatThrownBy(() -> builder.idleTimeout(null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> builder.shutdownGrace(null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> builder.maxConnections(0)).isInstanceOf(IllegalArgumentException.class);
        assertThat(builder.build().maxConnections()).isEqualTo(7);
    }

    private static void check(String name, BiConsumer<ListenerOptions.Builder, Duration> setter, Duration... invalid) {
        for (var value : invalid) {
            assertThatThrownBy(() -> setter.accept(ListenerOptions.builder(), value))
                    .isInstanceOf(IllegalArgumentException.class).hasMessageContaining(name);
        }
    }

    private static void checkInt(String name, BiConsumer<ListenerOptions.Builder, Integer> setter, int... invalid) {
        for (int value : invalid) {
            assertThatThrownBy(() -> setter.accept(ListenerOptions.builder(), value))
                    .isInstanceOf(IllegalArgumentException.class).hasMessageContaining(name);
        }
    }
}
