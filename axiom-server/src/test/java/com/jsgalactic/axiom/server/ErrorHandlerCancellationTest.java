package com.jsgalactic.axiom.server;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.jsgalactic.axiom.Axiom;
import com.jsgalactic.axiom.application.Application;
import com.jsgalactic.axiom.context.Handler;
import com.jsgalactic.axiom.execution.ExecutionContext;
import com.jsgalactic.axiom.http.Request;
import com.jsgalactic.axiom.http.Response;
import java.time.Duration;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/** Cancelled or expired requests never reach application error handlers. */
@Timeout(30)
class ErrorHandlerCancellationTest {
    private final AtomicInteger mapped = new AtomicInteger();
    private final AtomicInteger unwound = new AtomicInteger();

    private Application app(Handler handler) {
        var app = Axiom.create();
        app.use((ctx, next) -> {
            try {
                return next.run();
            } finally {
                unwound.incrementAndGet();
            }
        });
        app.get("/work", handler);
        app.error(Exception.class, (ctx, failure) -> {
            mapped.incrementAndGet();
            return Response.of(500, "mapped");
        });
        return app.start();
    }

    @AfterEach
    void clearInterrupt() {
        Thread.interrupted();
    }

    @Test
    void anExpiredDeadlineSkipsErrorHandlers() throws Exception {
        try (var app = app(ctx -> {
            while (!ctx.execution().isExpired()) { Thread.onSpinWait(); }
            throw new IllegalStateException("late failure");
        })) {
            assertThatThrownBy(() -> app.handle(Request.get("/work"), ExecutionContext.create(Duration.ofMillis(20))))
                    .isInstanceOf(IllegalStateException.class);
            assertThat(unwound).hasValue(1);
            assertThat(mapped).hasValue(0);
        }
    }

    @Test
    void anInterruptedRequestSkipsErrorHandlers() throws Exception {
        var entered = new CountDownLatch(1);
        var outcome = new AtomicReference<Throwable>();
        try (var app = app(ctx -> {
            entered.countDown();
            new CountDownLatch(1).await();
            return "unreachable";
        })) {
            var worker = Thread.ofPlatform().start(() -> {
                try {
                    app.handle(Request.get("/work"));
                } catch (Throwable failure) {
                    outcome.set(failure);
                }
            });
            assertThat(entered.await(10, TimeUnit.SECONDS)).isTrue();
            worker.interrupt();
            worker.join(10_000);
            assertThat(outcome.get()).isInstanceOf(InterruptedException.class);
            assertThat(unwound).hasValue(1);
            assertThat(mapped).hasValue(0);
        }
    }

    @Test
    void failuresOnAnInterruptedThreadAreNotOffered() throws Exception {
        try (var app = app(ctx -> {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("failed while cancelled");
        })) {
            assertThatThrownBy(() -> app.handle(Request.get("/work"))).isInstanceOf(IllegalStateException.class);
            assertThat(Thread.interrupted()).isTrue();
            assertThat(mapped).hasValue(0);
        }
    }

    @Test
    void interruptionAndCancellationAreNeverOffered() throws Exception {
        try (var interrupted = app(ctx -> { throw new InterruptedException(); });
                var cancelled = app(ctx -> { throw new CancellationException(); })) {
            assertThatThrownBy(() -> interrupted.handle(Request.get("/work"))).isInstanceOf(InterruptedException.class);
            assertThatThrownBy(() -> cancelled.handle(Request.get("/work"))).isInstanceOf(CancellationException.class);
            assertThat(mapped).hasValue(0);
        }
    }

    @Test
    void anErrorHandlerInterruptedWhileMappingRestoresTheInterruptFlag() throws Exception {
        try (var app = Axiom.create()) {
            app.get("/work", ctx -> { throw new IllegalStateException(); });
            app.error(IllegalStateException.class, (ctx, failure) -> { throw new InterruptedException(); });
            app.start();
            var response = app.handle(Request.get("/work"));
            assertThat(response.status()).isEqualTo(500);
            assertThat(Thread.interrupted()).isTrue();
        }
    }
}
