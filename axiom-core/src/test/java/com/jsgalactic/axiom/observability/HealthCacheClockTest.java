package com.jsgalactic.axiom.observability;

import static org.assertj.core.api.Assertions.assertThat;

import com.jsgalactic.axiom.application.Application;
import java.lang.reflect.Proxy;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

/** Expiry of kept check results, with a clock the test advances. */
class HealthCacheClockTest {
    private static Application running() {
        return (Application) Proxy.newProxyInstance(Application.class.getClassLoader(), new Class<?>[] {Application.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "state" -> Application.State.RUNNING;
                    case "onDrain" -> proxy;
                    default -> throw new UnsupportedOperationException(method.getName());
                });
    }

    @Test void aKeptResultIsReusedUntilItsIntervalPassesAndTheNextProbeRunsTheCheckAgain() {
        var clock = new AtomicLong(1_000);
        var runs = new AtomicInteger();
        var healthy = new java.util.concurrent.atomic.AtomicBoolean(true);
        var health = Health.builder(running()).cacheFor(Duration.ofNanos(100)).clock(clock::get)
                .readiness("db", () -> { runs.incrementAndGet(); return healthy.get(); }).build();
        assertThat(health.readiness().status()).isEqualTo(Health.Status.UP);
        clock.addAndGet(99);
        healthy.set(false);
        assertThat(health.readiness().status()).isEqualTo(Health.Status.UP);
        assertThat(runs).hasValue(1);
        clock.addAndGet(1); // The interval has passed.
        assertThat(health.readiness().status()).isEqualTo(Health.Status.DOWN);
        assertThat(runs).hasValue(2);
        // A down result is kept as well.
        healthy.set(true);
        clock.addAndGet(50);
        assertThat(health.readiness().status()).isEqualTo(Health.Status.DOWN);
        assertThat(runs).hasValue(2);
    }

    @Test void eachCheckKeepsItsOwnResult() {
        var clock = new AtomicLong();
        var first = new AtomicInteger();
        var second = new AtomicInteger();
        var health = Health.builder(running()).cacheFor(Duration.ofNanos(10)).clock(clock::get)
                .readiness("a", () -> { first.incrementAndGet(); return true; })
                .readiness("b", () -> { second.incrementAndGet(); return true; }).build();
        health.readiness();
        clock.addAndGet(10);
        health.readiness();
        health.readiness();
        assertThat(first).hasValue(2);
        assertThat(second).hasValue(2);
    }
}
