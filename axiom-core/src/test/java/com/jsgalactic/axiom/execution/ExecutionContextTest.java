package com.jsgalactic.axiom.execution;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

class ExecutionContextTest {
    @Test void measuresElapsedTimeAndClampsExpiry() {
        var clock = new AtomicLong(123);
        var context = ExecutionContext.create(Duration.ofNanos(10), clock::get);
        assertThat(context.remainingTime()).isEqualTo(Duration.ofNanos(10));
        clock.addAndGet(4);
        assertThat(context.remainingTime()).isEqualTo(Duration.ofNanos(6));
        assertThat(context.isExpired()).isFalse();
        clock.addAndGet(6);
        assertThat(context.isExpired()).isTrue();
        clock.addAndGet(100);
        assertThat(context.remainingTime()).isEqualTo(Duration.ZERO);
    }

    @Test void elapsedSubtractionHandlesNanoTimeWraparound() {
        var clock = new AtomicLong(Long.MAX_VALUE - 5);
        var context = ExecutionContext.create(Duration.ofNanos(10), clock::get);
        clock.addAndGet(7);
        assertThat(context.remainingTime()).isEqualTo(Duration.ofNanos(3));
        clock.addAndGet(3);
        assertThat(context.isExpired()).isTrue();
    }

    @Test void validatesBudgetsAndCreatesIndependentIds() {
        for (var invalid : new Duration[] {Duration.ZERO, Duration.ofNanos(-1), Duration.ofDays(2),
                Duration.ofSeconds(Long.MAX_VALUE)}) {
            assertThatThrownBy(() -> ExecutionContext.create(invalid)).isInstanceOf(IllegalArgumentException.class);
        }
        assertThatThrownBy(() -> ExecutionContext.create(null)).isInstanceOf(NullPointerException.class);
        var first = ExecutionContext.create(Duration.ofDays(1));
        var second = ExecutionContext.create(Duration.ofDays(1));
        assertThat(first.requestId()).isNotEqualTo(second.requestId());
    }

    @Test void requestIdsShareARandomProcessPrefixAndIncreaseASequence() {
        var first = ExecutionContext.create(Duration.ofSeconds(1)).requestId();
        var second = ExecutionContext.create(Duration.ofSeconds(1)).requestId();
        assertThat(first).matches("[A-Za-z0-9_-]{16}-[0-9a-f]+");
        assertThat(second).matches("[A-Za-z0-9_-]{16}-[0-9a-f]+");
        var prefix = first.substring(0, 17);
        assertThat(second).startsWith(prefix);
        assertThat(Long.parseLong(second.substring(17), 16)).isGreaterThan(Long.parseLong(first.substring(17), 16));
    }
}
