package com.jsgalactic.axiom.test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import com.jsgalactic.axiom.Axiom;
import com.jsgalactic.axiom.observability.Health;
import com.jsgalactic.axiom.observability.Health.Status;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/** The startup probe, per-check timeouts and result caching of {@link Health}. */
class HealthLifecycleTest {
    @Test void theStartupProbeIsDownUntilEveryCheckPassesThenStaysUpWithoutRunningThemAgain() throws Exception {
        var app = Axiom.create();
        var warm = new AtomicBoolean();
        var runs = new AtomicInteger();
        var health = Health.builder(app).startup("warm-up", () -> { runs.incrementAndGet(); return warm.get(); }).build();
        health.register(app);
        // Not running yet: DOWN without running anything.
        assertThat(health.startup().status()).isEqualTo(Status.DOWN);
        assertThat(runs).hasValue(0);
        try (var client = TestClient.start(app)) {
            var down = client.get("/health/startup");
            assertThat(down.status()).isEqualTo(503);
            assertThat(down.body()).isEqualTo("{\"status\":\"DOWN\",\"checks\":{\"warm-up\":\"DOWN\"}}");
            assertThat(runs).hasValue(1);
            warm.set(true);
            assertThat(client.get("/health/startup").status()).isEqualTo(200);
            assertThat(runs).hasValue(2);
            // Started for good: later probes do not run the check, even if it would fail now.
            warm.set(false);
            var up = client.get("/health/startup");
            assertThat(up.status()).isEqualTo(200);
            assertThat(up.body()).isEqualTo("{\"status\":\"UP\"}");
            assertThat(runs).hasValue(2);
            // Liveness and readiness do not depend on the startup checks.
            assertThat(client.get("/health/live").status()).isEqualTo(200);
            assertThat(client.get("/health/ready").status()).isEqualTo(200);
        }
    }

    @Test void withoutStartupChecksTheProbeIsUpOnceTheApplicationRuns() {
        var app = Axiom.create();
        var health = Health.builder(app).build();
        assertThat(health.startup().status()).isEqualTo(Status.DOWN);
        app.start();
        assertThat(health.startup().status()).isEqualTo(Status.UP);
        app.close();
        assertThat(health.startup().status()).as("latched").isEqualTo(Status.UP);

        var unstarted = Axiom.create();
        var late = Health.builder(unstarted).build();
        unstarted.close();
        assertThat(late.startup().status()).isEqualTo(Status.DOWN);
    }

    @Test void aCheckCanHaveItsOwnTimeoutShorterThanTheProbes() throws Exception {
        var app = Axiom.create().start();
        var started = new CountDownLatch(1);
        var interrupted = new CountDownLatch(1);
        var health = Health.builder(app).timeout(Duration.ofSeconds(30))
                .readiness("quick", () -> true)
                .readiness("impatient", () -> {
                    started.countDown();
                    try { new CountDownLatch(1).await(); } catch (InterruptedException expected) { interrupted.countDown(); }
                    return true;
                }, Duration.ofMillis(50)).build();
        var report = health.readiness();
        assertThat(report.checks()).containsEntry("quick", Status.UP).containsEntry("impatient", Status.DOWN);
        assertThat(started.await(30, TimeUnit.SECONDS)).isTrue();
        assertThat(interrupted.await(30, TimeUnit.SECONDS)).isTrue();
        app.close();
    }

    @Test void ownTimeoutsApplyToEveryKindOfCheckAndAreValidated() {
        var app = Axiom.create().start();
        var health = Health.builder(app)
                .liveness("l", () -> true, Duration.ofSeconds(1))
                .readiness("r", () -> true, Duration.ofSeconds(1))
                .startup("s", () -> true, Duration.ofSeconds(1)).build();
        assertThat(health.liveness().status()).isEqualTo(Status.UP);
        assertThat(health.readiness().status()).isEqualTo(Status.UP);
        assertThat(health.startup().status()).isEqualTo(Status.UP);
        var builder = Health.builder(app);
        assertThatIllegalArgumentException().isThrownBy(() -> builder.readiness("a", () -> true, Duration.ZERO));
        assertThatIllegalArgumentException().isThrownBy(() -> builder.liveness("a", () -> true, Duration.ofMinutes(1)));
        assertThatIllegalArgumentException().isThrownBy(() -> builder.startup("a", () -> true, Duration.ofSeconds(-1)));
        assertThatIllegalArgumentException().isThrownBy(() -> builder.startup("bad name", () -> true));
        builder.startup("s", () -> true);
        assertThatIllegalArgumentException().isThrownBy(() -> builder.startup("s", () -> true));
        assertThatIllegalArgumentException().isThrownBy(() -> builder.cacheFor(Duration.ofSeconds(-1)));
        assertThatIllegalArgumentException().isThrownBy(() -> builder.cacheFor(Duration.ofMinutes(2)));
        app.close();
    }

    @Test void cachedResultsMeanAnExpensiveCheckRunsOncePerIntervalWhateverTheProbes() throws Exception {
        var app = Axiom.create();
        var healthy = new AtomicBoolean(true);
        var runs = new AtomicInteger();
        var health = Health.builder(app).cacheFor(Duration.ofMinutes(1))
                .readiness("db", () -> { runs.incrementAndGet(); return healthy.get(); }).build();
        health.register(app);
        try (var client = TestClient.start(app)) {
            for (int i = 0; i < 5; i++) { assertThat(client.get("/health/ready").status()).isEqualTo(200); }
            assertThat(runs).hasValue(1);
            healthy.set(false);
            // Still the kept answer, which is the point of keeping it.
            assertThat(client.get("/health/ready").status()).isEqualTo(200);
            assertThat(runs).hasValue(1);
            // Draining ignores the cache: it is decided without running or reading checks.
            health.beginDrain();
            assertThat(client.get("/health/ready").status()).isEqualTo(503);
            assertThat(runs).hasValue(1);
        }
    }

    @Test void withoutCachingEveryProbeRunsTheCheck() {
        var app = Axiom.create().start();
        var runs = new AtomicInteger();
        var health = Health.builder(app).readiness("db", () -> { runs.incrementAndGet(); return true; }).build();
        health.readiness();
        health.readiness();
        assertThat(runs).hasValue(2);
        app.close();
    }

    @Test void aStoppedApplicationBypassesTheCache() {
        var app = Axiom.create().start();
        var health = Health.builder(app).cacheFor(Duration.ofMinutes(1)).readiness("db", () -> true).build();
        assertThat(health.readiness().status()).isEqualTo(Status.UP);
        app.close();
        assertThat(health.readiness().status()).isEqualTo(Status.DOWN);
    }

    @Test void buildingHealthForAClosedApplicationStillWorks() {
        var app = Axiom.create();
        app.close();
        var health = Health.builder(app).build();
        assertThat(health.readiness().status()).isEqualTo(Status.DOWN);
    }
}
