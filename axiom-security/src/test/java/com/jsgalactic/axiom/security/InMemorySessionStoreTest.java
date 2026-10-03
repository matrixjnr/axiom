package com.jsgalactic.axiom.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import com.jsgalactic.axiom.context.SecurityIdentity;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;

/** Timeouts, bounds, atomicity and rotation of the in-memory store, with a manual clock. */
class InMemorySessionStoreTest {
    private static final SessionState ANONYMOUS = SessionState.empty();
    private static final SessionState SIGNED_IN = SessionState.empty()
            .withIdentity(Optional.of(new SecurityIdentity("ada", Set.of("user"), Set.of())));

    private final RateLimitTest.ManualClock clock = new RateLimitTest.ManualClock();
    private final RateLimitTest.RecordingMetrics metrics = new RateLimitTest.RecordingMetrics();

    private InMemorySessionStore.Builder store() {
        return InMemorySessionStore.builder().clock(clock).metrics(metrics)
                .idleTimeout(Duration.ofMinutes(10)).absoluteTimeout(Duration.ofHours(1));
    }

    @Test
    void idleTimeoutExpiresExactlyAtTheLimitAndAccessRestartsIt() {
        var store = store().build();
        assertThat(store.create("a", ANONYMOUS)).isTrue();
        clock.advance(Duration.ofMinutes(10).minusNanos(1));
        assertThat(store.find("a")).isPresent(); // Touch: the idle clock restarts.
        clock.advance(Duration.ofMinutes(10).minusNanos(1));
        assertThat(store.find("a")).isPresent();
        clock.advance(Duration.ofMinutes(10));
        assertThat(store.find("a")).isEmpty();
        assertThat(store.size()).isZero();
        assertThat(metrics.get("axiom.security.sessions.evictions", "reason", "expired")).isEqualTo(1);
    }

    @Test
    void absoluteTimeoutIsNotExtendedByAccessOrRotation() {
        var store = store().build();
        store.create("a", ANONYMOUS);
        for (int i = 0; i < 5; i++) {
            clock.advance(Duration.ofMinutes(9));
            assertThat(store.find("a")).isPresent();
        }
        assertThat(store.rename("a", "b")).isTrue();
        clock.advance(Duration.ofMinutes(9)); // 54 minutes since creation.
        assertThat(store.find("b")).isPresent();
        clock.advance(Duration.ofMinutes(6));
        assertThat(store.find("b")).isEmpty();
    }

    @Test
    void aClockMovingBackwardsExpiresNothing() {
        var store = store().build();
        store.create("a", ANONYMOUS);
        clock.advance(Duration.ofHours(-3));
        assertThat(store.find("a")).isPresent();
        clock.advance(Duration.ofMinutes(10));
        assertThat(store.find("a")).isEmpty();
    }

    @Test
    void renameInvalidatesTheOldIdentifierAtOnce() {
        var store = store().build();
        store.create("old", SIGNED_IN);
        assertThat(store.rename("old", "new")).isTrue();
        assertThat(store.find("old")).isEmpty();
        assertThat(store.find("new")).isPresent();
        assertThat(store.rename("old", "other")).isFalse();
        store.create("taken", ANONYMOUS);
        assertThat(store.rename("new", "taken")).isFalse();
        assertThat(store.find("new")).isPresent();
    }

    @Test
    void createRefusesALiveIdentifierButReusesAnExpiredOne() {
        var store = store().build();
        assertThat(store.create("a", ANONYMOUS)).isTrue();
        assertThat(store.create("a", SIGNED_IN)).isFalse();
        assertThat(store.find("a").orElseThrow().identity()).isEmpty();
        clock.advance(Duration.ofMinutes(11));
        assertThat(store.create("a", SIGNED_IN)).isTrue();
        assertThat(store.find("a").orElseThrow().identity()).isPresent();
    }

    @Test
    void updateReplacesStateKeepsCreationTimeAndMovesBetweenPools() {
        var store = store().build();
        store.create("a", ANONYMOUS);
        var created = store.find("a").orElseThrow().createdAt();
        clock.advance(Duration.ofMinutes(1));
        assertThat(store.update("a", state -> state.withAttributes(Map.of("k", "v")).withIdentity(SIGNED_IN.identity()))).isTrue();
        var state = store.find("a").orElseThrow();
        assertThat(state.attributes()).containsEntry("k", "v");
        assertThat(state.createdAt()).isEqualTo(created);
        assertThat(store.update("missing", s -> s)).isFalse();
        store.remove("a");
        store.remove("a");
        assertThat(store.size()).isZero();
        assertThat(metrics.get("axiom.security.sessions")).isZero();
    }

    @Test
    void maxSessionsEvictsAnonymousSessionsBeforeSignedInOnes() {
        var store = store().maxSessions(3).build();
        store.create("user1", SIGNED_IN);
        store.create("anon1", ANONYMOUS);
        store.create("anon2", ANONYMOUS);
        for (int i = 0; i < 1000; i++) { store.create("flood" + i, ANONYMOUS); }
        assertThat(store.size()).isEqualTo(3);
        assertThat(store.find("user1")).isPresent();
        assertThat(store.find("anon1")).isEmpty();
        assertThat(metrics.get("axiom.security.sessions.evictions", "reason", "capacity")).isEqualTo(1000);
        // With only signed-in sessions left to evict, the least recently used one goes.
        var small = store().maxSessions(2).build();
        small.create("u1", SIGNED_IN);
        small.create("u2", SIGNED_IN);
        small.find("u1");
        small.create("u3", SIGNED_IN);
        assertThat(small.find("u2")).isEmpty();
        assertThat(small.find("u1")).isPresent();
    }

    @Test
    void expiredSessionsAreReclaimedBeforeLiveOnesAreEvicted() {
        var store = store().maxSessions(2).build();
        store.create("old", SIGNED_IN);
        clock.advance(Duration.ofMinutes(5));
        store.create("recent", SIGNED_IN);
        clock.advance(Duration.ofMinutes(6)); // "old" is idle for 11 minutes, "recent" for 6.
        store.create("new", ANONYMOUS);
        assertThat(store.find("recent")).isPresent();
        assertThat(metrics.get("axiom.security.sessions.evictions", "reason", "capacity")).isZero();
        // An absolute timeout in the middle of the access order is found by the periodic sweep.
        var swept = store().maxSessions(2).idleTimeout(Duration.ofMinutes(50)).absoluteTimeout(Duration.ofMinutes(50)).build();
        swept.create("a", SIGNED_IN);
        clock.advance(Duration.ofMinutes(30));
        swept.create("b", SIGNED_IN);
        clock.advance(Duration.ofMinutes(21));
        swept.create("c", SIGNED_IN);
        assertThat(swept.find("b")).isPresent();
        assertThat(swept.find("c")).isPresent();
    }

    @Test
    void concurrentUpdatesAreAtomic() throws Exception {
        var store = store().build();
        store.create("a", ANONYMOUS);
        int threads = 8;
        int perThread = 200;
        var start = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(threads)) {
            var futures = new ArrayList<Future<?>>();
            for (int t = 0; t < threads; t++) {
                futures.add(pool.submit(() -> {
                    start.await();
                    for (int i = 0; i < perThread; i++) {
                        store.update("a", state -> state.withAttributes(Map.of("n", Integer.toString(Integer.parseInt(state.attributes().getOrDefault("n", "0")) + 1))));
                    }
                    return null;
                }));
            }
            start.countDown();
            for (var future : futures) { future.get(); }
        }
        assertThat(store.find("a").orElseThrow().attributes()).containsEntry("n", Integer.toString(threads * perThread));
    }

    @Test
    void concurrentRenamesLetExactlyOneWin() throws Exception {
        var store = store().build();
        store.create("a", SIGNED_IN);
        int threads = 8;
        var start = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(threads)) {
            var futures = new ArrayList<Future<Boolean>>();
            for (int t = 0; t < threads; t++) {
                var target = "target" + t;
                futures.add(pool.submit(() -> {
                    start.await();
                    return store.rename("a", target);
                }));
            }
            start.countDown();
            int winners = 0;
            for (var future : futures) { if (future.get()) { winners++; } }
            assertThat(winners).isEqualTo(1);
        }
        assertThat(store.size()).isEqualTo(1);
    }

    @Test
    void validatesConfigurationAndKeepsIdentifiersOutOfDescriptions() {
        assertThatIllegalArgumentException().isThrownBy(() -> InMemorySessionStore.builder().idleTimeout(Duration.ZERO));
        assertThatIllegalArgumentException().isThrownBy(() -> InMemorySessionStore.builder().absoluteTimeout(Duration.ofDays(31)));
        assertThatIllegalArgumentException().isThrownBy(() -> InMemorySessionStore.builder().maxSessions(0));
        assertThatIllegalArgumentException().isThrownBy(() -> InMemorySessionStore.builder()
                .idleTimeout(Duration.ofHours(2)).absoluteTimeout(Duration.ofHours(1)).build());
        var store = store().build();
        store.create("super-secret-identifier", SIGNED_IN);
        assertThat(store.toString()).doesNotContain("super-secret");
        assertThat(store.find("super-secret-identifier").orElseThrow().toString()).doesNotContain("ada");
        assertThatIllegalArgumentException().isThrownBy(() -> SessionState.empty().withAttributes(Map.of("bad name", "v")));
        assertThatIllegalArgumentException().isThrownBy(() -> SessionState.empty().withAttributes(Map.of("k", "x".repeat(4097))));
    }
}
