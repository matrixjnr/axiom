package com.jsgalactic.axiom.test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import com.jsgalactic.axiom.Axiom;
import com.jsgalactic.axiom.context.Middleware;
import com.jsgalactic.axiom.error.UnauthorizedException;
import com.jsgalactic.axiom.observability.Health;
import com.jsgalactic.axiom.observability.Health.Status;
import java.time.Duration;
import java.util.ArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class HealthTest {
    @Test void withoutChecksBothProbesAreUp() {
        var app = Axiom.create().start();
        var health = Health.builder(app).build();
        assertThat(health.liveness().toJson()).isEqualTo("{\"status\":\"UP\"}");
        assertThat(health.readiness().status()).isEqualTo(Status.UP);
        app.close();
    }

    @Test void reportsEachCheckAndOnlyUpWhenAllAre() {
        var app = Axiom.create().start();
        var health = Health.builder(app)
                .liveness("loop", () -> true)
                .readiness("database", () -> true)
                .readiness("cache", () -> false)
                .build();
        assertThat(health.liveness().status()).isEqualTo(Status.UP);
        var ready = health.readiness();
        assertThat(ready.status()).isEqualTo(Status.DOWN);
        assertThat(ready.checks()).containsExactly(
                java.util.Map.entry("database", Status.UP), java.util.Map.entry("cache", Status.DOWN));
        assertThat(ready.toJson()).isEqualTo("{\"status\":\"DOWN\",\"checks\":{\"database\":\"UP\",\"cache\":\"DOWN\"}}");
        app.close();
    }

    @Test void aThrowingCheckIsDownAndItsMessageIsNeverReported() throws Exception {
        var app = Axiom.create();
        var health = Health.builder(app).readiness("database", () -> {
            throw new IllegalStateException("password=hunter2 at jdbc://internal-host");
        }).build();
        health.register(app);
        try (var client = TestClient.start(app)) {
            var response = client.get("/health/ready");
            assertThat(response.status()).isEqualTo(503);
            assertThat(response.body()).isEqualTo("{\"status\":\"DOWN\",\"checks\":{\"database\":\"DOWN\"}}");
        }
    }

    @Test void aCheckThatOutlastsTheTimeoutIsDownAndInterrupted() throws Exception {
        var app = Axiom.create().start();
        var started = new CountDownLatch(1);
        var interrupted = new CountDownLatch(1);
        var health = Health.builder(app).timeout(Duration.ofMillis(50)).readiness("slow", () -> {
            started.countDown();
            try { new CountDownLatch(1).await(); }
            catch (InterruptedException expected) { interrupted.countDown(); }
            return true;
        }).build();
        assertThat(health.readiness().checks()).containsEntry("slow", Status.DOWN);
        assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(interrupted.await(5, TimeUnit.SECONDS)).isTrue();
        app.close();
    }

    @Test void aCheckStillRunningIsNotStartedAgainAndRecoversWhenItReturns() throws Exception {
        var app = Axiom.create().start();
        var starts = new AtomicInteger();
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var health = Health.builder(app).timeout(Duration.ofMillis(20)).readiness("stuck", () -> {
            if (starts.incrementAndGet() == 1) {
                entered.countDown();
                boolean released = false;
                while (!released) { // ignores interruption, like a blocked native call
                    try { released = release.await(1, TimeUnit.DAYS); } catch (InterruptedException ignored) { /* keep waiting */ }
                }
            }
            return true;
        }).build();
        assertThat(health.readiness().status()).isEqualTo(Status.DOWN);
        assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(health.readiness().status()).isEqualTo(Status.DOWN);
        assertThat(starts).hasValue(1);
        release.countDown();
        // The released run clears its flag on its own thread; poll without sleeping until a probe starts anew.
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        var status = health.readiness().status();
        while (status != Status.UP && System.nanoTime() < deadline) {
            Thread.onSpinWait();
            status = health.readiness().status();
        }
        assertThat(status).isEqualTo(Status.UP);
        assertThat(starts).hasValue(2);
        app.close();
    }

    @Test void concurrentProbesNeverRunOneCheckConcurrently() throws Exception {
        var app = Axiom.create().start();
        var running = new AtomicInteger();
        var overlap = new AtomicInteger();
        var health = Health.builder(app).timeout(Duration.ofSeconds(5)).readiness("busy", () -> {
            if (running.incrementAndGet() > 1) { overlap.incrementAndGet(); }
            try { Thread.yield(); } finally { running.decrementAndGet(); }
            return true;
        }).build();
        int probers = 8;
        var start = new CountDownLatch(1);
        var threads = new ArrayList<Thread>();
        for (int i = 0; i < probers; i++) {
            threads.add(Thread.ofPlatform().start(() -> {
                try { start.await(); } catch (InterruptedException e) { return; }
                for (int j = 0; j < 200; j++) { health.readiness(); }
            }));
        }
        start.countDown();
        for (var thread : threads) { thread.join(); }
        assertThat(overlap).hasValue(0);
        app.close();
    }

    @Test void readinessIsDownWhileDrainingWithoutRunningChecksButLivenessStaysUp() throws Exception {
        var app = Axiom.create();
        var runs = new AtomicInteger();
        var health = Health.builder(app).readiness("database", () -> { runs.incrementAndGet(); return true; }).build();
        health.register(app);
        try (var client = TestClient.start(app)) {
            assertThat(client.get("/health/ready").status()).isEqualTo(200);
            health.beginDrain();
            health.beginDrain();
            assertThat(health.isDraining()).isTrue();
            var ready = client.get("/health/ready");
            assertThat(ready.status()).isEqualTo(503);
            assertThat(ready.body()).isEqualTo("{\"status\":\"DOWN\"}");
            assertThat(runs).hasValue(1);
            assertThat(client.get("/health/live").status()).isEqualTo(200);
        }
    }

    @Test void readinessIsDownOnceTheApplicationIsClosed() {
        var app = Axiom.create().start();
        var health = Health.builder(app).build();
        app.close();
        assertThat(health.readiness().status()).isEqualTo(Status.DOWN);
    }

    @Test void routesAnswerSmallJsonAndHonorTheMiddlewareGuardingThem() throws Exception {
        var app = Axiom.create();
        Middleware guard = (ctx, next) -> {
            if (ctx.request().header("X-Ops").isEmpty()) { throw new UnauthorizedException("ops_only"); }
            return next.run();
        };
        Health.builder(app).build().register(app, guard);
        try (var client = TestClient.start(app)) {
            assertThat(client.get("/health/live").status()).isEqualTo(401);
            var response = client.execute(new com.jsgalactic.axiom.http.Request("GET", "/health/ready")
                    .withHeaders(java.util.Map.of("X-Ops", "1")));
            assertThat(response.status()).isEqualTo(200);
            assertThat(response.headers()).containsEntry("Content-Type", "application/json")
                    .containsEntry("Cache-Control", "no-store");
            assertThat(response.body()).isEqualTo("{\"status\":\"UP\"}");
        }
    }

    @Test void registrationUnderAGroupUsesItsPrefix() throws Exception {
        var app = Axiom.create();
        var health = Health.builder(app).build();
        app.group("/internal", group -> health.register(group));
        try (var client = TestClient.start(app)) {
            assertThat(client.get("/internal/health/live").status()).isEqualTo(200);
            assertThat(client.get("/health/live").status()).isEqualTo(404);
        }
    }

    @Test void validatesNamesTimeoutsAndLimits() {
        var app = Axiom.create();
        var builder = Health.builder(app);
        assertThatIllegalArgumentException().isThrownBy(() -> builder.readiness("bad name", () -> true));
        assertThatIllegalArgumentException().isThrownBy(() -> builder.readiness("\"quote", () -> true));
        assertThatIllegalArgumentException().isThrownBy(() -> builder.readiness(null, () -> true));
        assertThatIllegalArgumentException().isThrownBy(() -> builder.readiness("x".repeat(65), () -> true));
        builder.readiness("db", () -> true).liveness("db", () -> true); // names are per probe
        assertThatIllegalArgumentException().isThrownBy(() -> builder.readiness("db", () -> true));
        assertThatIllegalArgumentException().isThrownBy(() -> builder.timeout(Duration.ZERO));
        assertThatIllegalArgumentException().isThrownBy(() -> builder.timeout(Duration.ofMinutes(1)));
        for (int i = 0; i < Health.MAX_CHECKS - 1; i++) { builder.readiness("c" + i, () -> true); }
        assertThatIllegalArgumentException().isThrownBy(() -> builder.readiness("one-too-many", () -> true));
        app.close();
    }
}
