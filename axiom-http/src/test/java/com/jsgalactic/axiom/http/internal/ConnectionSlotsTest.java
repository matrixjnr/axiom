package com.jsgalactic.axiom.http.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

class ConnectionSlotsTest {
    @Test void releaseIsIdempotentAndAReleasedSlotCannotLinger() {
        var slots = new ConnectionSlots(2, 1);
        var first = slots.acquire();
        var second = slots.acquire();
        assertThat(slots.acquire()).isNull();
        assertThat(first.linger()).isTrue();
        assertThat(first.linger()).as("already lingering").isFalse();
        assertThat(slots.open()).isEqualTo(1);
        assertThat(slots.lingering()).isEqualTo(1);
        assertThat(second.linger()).as("lingering pool full").isFalse();
        first.release();
        first.release();
        assertThat(slots.lingering()).isZero();
        assertThat(slots.open()).isEqualTo(1);
        second.release();
        assertThat(second.linger()).isFalse();
        assertThat(slots.open()).isZero();
        assertThat(slots.lingering()).isZero();
    }

    @Test void concurrentUseNeverExceedsEitherBoundAndReturnsEverySlot() throws Exception {
        var slots = new ConnectionSlots(8, 3);
        var start = new CountDownLatch(1);
        var exceeded = new AtomicBoolean();
        var open = new java.util.concurrent.atomic.AtomicInteger();
        var lingering = new java.util.concurrent.atomic.AtomicInteger();
        var threads = new ArrayList<Thread>();
        for (int t = 0; t < 8; t++) {
            threads.add(Thread.ofPlatform().start(() -> {
                try { start.await(); } catch (InterruptedException stop) { return; }
                for (int i = 0; i < 20_000; i++) {
                    var slot = slots.acquire();
                    if (slot == null) { continue; }
                    // The pool's counters may overshoot for an instant while refusing, so the test
                    // counts granted slots itself: up after a grant, down before a release.
                    if (open.incrementAndGet() > 8) { exceeded.set(true); }
                    open.decrementAndGet();
                    if (i % 2 == 0 && slot.linger()) {
                        if (lingering.incrementAndGet() > 3) { exceeded.set(true); }
                        lingering.decrementAndGet();
                    }
                    slot.release();
                    if (i % 3 == 0) { slot.release(); }
                }
            }));
        }
        start.countDown();
        for (var thread : threads) { thread.join(TimeUnit.MINUTES.toMillis(2)); }
        assertThat(exceeded).isFalse();
        assertThat(slots.open()).isZero();
        assertThat(slots.lingering()).isZero();
    }
}
