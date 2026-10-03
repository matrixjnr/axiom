package com.jsgalactic.axiom.execution;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.jsgalactic.axiom.Axiom;
import com.jsgalactic.axiom.http.Request;
import com.jsgalactic.axiom.server.internal.execution.RequestDispatcher;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

/** Uses core's package-private clock seam without adding a public test clock API. */
class ExecutionDeadlineTest {
    @Test void completionChecksClockEvenWhenScheduledTimerHasNotFired() throws Exception {
        var clock = new AtomicLong();
        var budget = Duration.ofHours(1);
        var execution = ExecutionContext.create(budget, clock::get);
        var dispatcher = new RequestDispatcher(1);
        try {
            var task = dispatcher.submit(execution, () -> { clock.set(budget.toNanos()); return "too late"; });
            assertThatThrownBy(() -> task.result().toCompletableFuture().get(5, TimeUnit.SECONDS))
                    .hasCauseInstanceOf(RequestDispatcher.DeadlineExceededException.class);
        } finally {
            dispatcher.close();
            dispatcher.termination().toCompletableFuture().get(5, TimeUnit.SECONDS);
        }
    }

    @Test void requestDeadlineWinsOverQueueBudgetBeforePromotion() throws Exception {
        var entered = new java.util.concurrent.CountDownLatch(1);
        var release = new java.util.concurrent.CountDownLatch(1);
        var clock = new AtomicLong();
        var queuedContext = ExecutionContext.create(Duration.ofHours(1), clock::get);
        var dispatcher = new RequestDispatcher(new AdmissionPolicy(1, 1, Duration.ofDays(1)));
        try {
            dispatcher.submit(ExecutionContext.create(Duration.ofHours(1)), () -> {
                entered.countDown();
                release.await();
                return null;
            });
            assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
            var queued = dispatcher.submit(queuedContext, () -> { throw new AssertionError("expired work must not run"); });
            assertThat(dispatcher.snapshot().queued()).isEqualTo(1);
            clock.set(Duration.ofHours(1).toNanos());
            release.countDown();
            assertThatThrownBy(() -> queued.result().toCompletableFuture().get(5, TimeUnit.SECONDS))
                    .hasCauseInstanceOf(RequestDispatcher.DeadlineExceededException.class);
            assertThat(dispatcher.snapshot().queueTimeouts()).isZero();
        } finally {
            release.countDown();
            dispatcher.close();
            dispatcher.termination().toCompletableFuture().get(5, TimeUnit.SECONDS);
        }
    }
    @Test void expiredExplicitContextDoesNotInvokeSynchronousHandler() {
        var clock = new AtomicLong();
        var execution = ExecutionContext.create(Duration.ofSeconds(1), clock::get);
        var called = new AtomicBoolean();
        try (var app = Axiom.create()) {
            app.get("/", ctx -> { called.set(true); return "unexpected"; });
            app.start();
            clock.set(Duration.ofSeconds(1).toNanos());
            assertThatThrownBy(() -> app.handle(Request.get("/"), execution)).isInstanceOf(TimeoutException.class);
            assertThat(called).isFalse();
        }
    }
}
