package com.jsgalactic.axiom.test;

import static org.assertj.core.api.Assertions.assertThat;

import com.jsgalactic.axiom.Axiom;
import com.jsgalactic.axiom.execution.AdmissionPolicy;
import com.jsgalactic.axiom.http.Request;
import com.jsgalactic.axiom.routing.Route;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

@Timeout(30)
class TestClientMiddlewareTest {
    @Test
    void middlewareRunsUnderTheRequestDeadlineAndIsInterruptedByIt() throws Exception {
        var interrupted = new CountDownLatch(1);
        var app = Axiom.create().requestTimeout(Duration.ofMillis(200));
        app.get("/slow", ctx -> "unreachable", (ctx, next) -> {
            try {
                new CountDownLatch(1).await();
            } catch (InterruptedException cancelled) {
                interrupted.countDown();
                throw cancelled;
            }
            return next.run();
        });
        try (var client = TestClient.start(app)) {
            var response = client.get("/slow");
            assertThat(response.status()).isEqualTo(504);
            assertThat(interrupted.await(10, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test
    void middlewareRunsInsideTheAdmittedTaskOfItsRoute() throws Exception {
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var invocations = new AtomicInteger();
        var thread = new AtomicReference<Thread>();
        var app = Axiom.create();
        var route = new AtomicReference<Route>();
        app.group("/api", api -> {
            api.use((ctx, next) -> {
                invocations.incrementAndGet();
                thread.set(Thread.currentThread());
                entered.countDown();
                release.await();
                return next.run();
            });
            route.set(api.get("/work", ctx -> "done"));
        });
        app.admissionPolicy(route.get(), AdmissionPolicy.reject(1));
        try (var client = TestClient.start(app)) {
            var first = client.submit(Request.get("/api/work"));
            assertThat(entered.await(10, TimeUnit.SECONDS)).isTrue();
            assertThat(thread.get().isVirtual()).isTrue();
            assertThat(client.get("/api/work").status()).isEqualTo(503);
            assertThat(invocations).hasValue(1);
            release.countDown();
            assertThat(first.get(10, TimeUnit.SECONDS).body()).isEqualTo("done");
        }
    }

    @Test
    void concurrentRequestsShareOneChainWithoutSharingContinuations() throws Exception {
        int requests = 8;
        var entered = new CountDownLatch(requests);
        var release = new CountDownLatch(1);
        var app = Axiom.create();
        app.use((ctx, next) -> {
            entered.countDown();
            release.await();
            return next.run().withHeader("X-Item", ctx.path("id"));
        });
        app.get("/items/:id", ctx -> "item " + ctx.path("id"));
        try (var client = TestClient.start(app)) {
            var responses = new java.util.ArrayList<java.util.concurrent.CompletableFuture<com.jsgalactic.axiom.http.Response>>();
            for (int i = 0; i < requests; i++) { responses.add(client.submit(Request.get("/items/" + i))); }
            // Every request is inside the same middleware instance before any continues.
            assertThat(entered.await(10, TimeUnit.SECONDS)).isTrue();
            release.countDown();
            for (int i = 0; i < requests; i++) {
                var response = responses.get(i).get(10, TimeUnit.SECONDS);
                assertThat(response.body()).isEqualTo("item " + i);
                assertThat(response.headers()).containsEntry("X-Item", Integer.toString(i));
            }
        }
    }

    @Test
    void globalMiddlewareDecoratesRouterAnswersThroughTheDispatcher() throws Exception {
        var app = Axiom.create();
        app.use((ctx, next) -> next.run().withHeader("X-Content-Type-Options", "nosniff"));
        app.get("/items", ctx -> "items");
        try (var client = TestClient.start(app)) {
            assertThat(client.get("/items").headers()).containsEntry("X-Content-Type-Options", "nosniff");
            var missing = client.get("/missing");
            assertThat(missing.status()).isEqualTo(404);
            assertThat(missing.headers()).containsEntry("X-Content-Type-Options", "nosniff")
                    .containsEntry("Content-Type", "application/problem+json");
            var head = client.execute(new Request("HEAD", "/items"));
            assertThat(head.body()).isNull();
            assertThat(head.headers()).containsEntry("X-Content-Type-Options", "nosniff")
                    .containsEntry("Content-Length", "5");
        }
    }
}
