package com.jsgalactic.axiom.server;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.jsgalactic.axiom.Axiom;
import com.jsgalactic.axiom.application.Application;
import com.jsgalactic.axiom.http.InvalidRequestPathException;
import com.jsgalactic.axiom.http.Request;
import com.jsgalactic.axiom.http.Response;
import java.io.IOException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class ApplicationTest {
    @Test
    void createsIndependentApplicationsThroughTheProvider() throws Exception {
        try (var first = Axiom.create(); var second = Axiom.create()) {
            first.get("/", ctx -> "hello");
            first.start();
            second.start();
            assertThat(first.handle(Request.get("/")).body()).isEqualTo("hello");
            assertThat(second.handle(Request.get("/")).status()).isEqualTo(404);
        }
    }

    @Test
    void preservesRegistrationOrderAndRejectsDuplicatesWithoutReplacingHandlers() throws Exception {
        try (var app = Axiom.create()) {
            var first = app.get("/one", ctx -> "first");
            var snapshot = app.routes();
            var second = app.post("/two", ctx -> "second");
            assertThat(snapshot).containsExactly(first);
            assertThat(app.routes()).containsExactly(first, second);
            assertThatThrownBy(() -> snapshot.clear()).isInstanceOf(UnsupportedOperationException.class);
            assertThatIllegalArgumentException().isThrownBy(() -> app.get("/one", ctx -> "replacement"));
            app.start();
            assertThat(app.handle(Request.get("/one")).body()).isEqualTo("first");
        }
    }

    @Test
    @SuppressWarnings("try") // Explicit repeated close is the behavior under test.
    void enforcesOneWayLifecycle() {
        try (var app = Axiom.create()) {
            assertThat(app.state()).isEqualTo(Application.State.CONFIGURING);
            assertThatIllegalStateException().isThrownBy(() -> app.handle(Request.get("/")));
            app.get("/", ctx -> "hello");
            assertThat(app.start()).isSameAs(app);
            assertThat(app.start()).isSameAs(app);
            assertThat(app.state()).isEqualTo(Application.State.RUNNING);
            assertThatIllegalStateException().isThrownBy(() -> app.get("/late", ctx -> "late"));
            app.close();
            app.close();
            assertThat(app.state()).isEqualTo(Application.State.CLOSED);
            assertThatIllegalStateException().isThrownBy(app::start);
            assertThatIllegalStateException().isThrownBy(() -> app.get("/late", ctx -> "late"));
            assertThatIllegalStateException().isThrownBy(() -> app.handle(Request.get("/")));
        }
    }

    @Test
    void closeBeforeStartIsTerminal() {
        var app = Axiom.create();
        app.close();
        assertThatIllegalStateException().isThrownBy(app::start);
    }

    @Test
    void distinguishesMissingPathFromMethodMismatch() throws Exception {
        try (var app = Axiom.create()) {
            app.post("/users", ctx -> "post");
            app.get("/users", ctx -> "get");
            app.start();
            assertThat(app.handle(Request.get("/unknown")).status()).isEqualTo(404);
            var mismatch = app.handle(new Request("DELETE", "/users"));
            assertThat(mismatch.status()).isEqualTo(405);
            assertThat(mismatch.headers()).containsEntry("allow", "GET, HEAD, OPTIONS, POST");
            assertThat(app.handle(new Request("get", "/users")).status()).isEqualTo(405);
        }
    }

    @Test
    void rejectsAmbiguousPathsAndMatchesTheRestWithoutNormalizingOrDecoding() throws Exception {
        try (var app = Axiom.create()) {
            app.get("/users", ctx -> "users");
            app.get("/a%20b", ctx -> "encoded");
            app.get("/time/12:00", ctx -> "literal colon");
            app.start();
            for (var path : new String[] {"/users/", "/Users", "/a%2520b", "/a%20B"}) {
                assertThat(app.handle(Request.get(path)).status()).as(path).isEqualTo(404);
            }
            for (var path : new String[] {"//users", "/x/../users", "/./users", "/a%2fb", "/users%2F"}) {
                assertThatThrownBy(() -> app.handle(Request.get(path))).as(path)
                        .isInstanceOf(InvalidRequestPathException.class);
            }
            assertThat(app.handle(Request.get("/a%20b")).body()).isEqualTo("encoded");
            assertThat(app.handle(Request.get("/time/12:00")).body()).isEqualTo("literal colon");
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"/users/:", "/files/*path/more", "/users/{id}"})
    void rejectsMalformedTemplates(String path) {
        try (var app = Axiom.create()) {
            assertThatIllegalArgumentException().isThrownBy(() -> app.get(path, ctx -> "never"));
            assertThat(app.routes()).isEmpty();
        }
    }

    @Test
    void mapsBodiesStatusesAndExplicitResponses() throws Exception {
        var explicit = Response.of(202, "accepted").withHeader("Location", "/jobs/1");
        record Payload(String value) {}
        var payload = new Payload("domain object");
        try (var app = Axiom.create()) {
            app.get("/text", ctx -> ctx.status(201).text("created"));
            app.get("/null", ctx -> null);
            app.get("/empty", ctx -> ctx.noContent());
            app.get("/explicit", ctx -> { ctx.status(500); return explicit; });
            app.get("/object", ctx -> payload);
            app.get("/status", ctx -> { ctx.status(202); return null; });
            app.start();
            var text = app.handle(Request.get("/text"));
            assertThat(text.status()).isEqualTo(201);
            assertThat(text.headers()).containsEntry("content-type", "text/plain; charset=utf-8");
            assertThat(app.handle(Request.get("/null")).status()).isEqualTo(204);
            assertThat(app.handle(Request.get("/empty")).body()).isNull();
            assertThat(app.handle(Request.get("/explicit"))).isSameAs(explicit);
            assertThat(app.handle(Request.get("/object")).body()).isSameAs(payload);
            assertThat(app.handle(Request.get("/status")).status()).isEqualTo(202);
        }
    }

    @Test
    void headFallsBackToGetAndSuppressesBodiesIncludingErrors() throws Exception {
        try (var app = Axiom.create()) {
            app.head("/", ctx -> "metadata");
            app.get("/get-only", ctx -> ctx.status(201).text("body for " + ctx.method()));
            app.post("/post-only", ctx -> "post");
            app.start();
            var response = app.handle(new Request("HEAD", "/"));
            assertThat(response.status()).isEqualTo(200);
            assertThat(response.body()).isNull();
            assertThat(response.headers()).containsKey("content-type");
            var fallback = app.handle(new Request("HEAD", "/get-only"));
            assertThat(fallback.status()).isEqualTo(201);
            assertThat(fallback.body()).isNull();
            assertThat(fallback.headers()).containsEntry("content-type", "text/plain; charset=utf-8");
            // The GET representation's encoded length, without its bytes.
            assertThat(fallback.headers()).containsEntry("content-length", "13");
            assertThat(response.headers()).containsEntry("content-length", "8");
            var mismatch = app.handle(new Request("HEAD", "/post-only"));
            assertThat(mismatch.status()).isEqualTo(405);
            assertThat(mismatch.headers()).containsEntry("allow", "OPTIONS, POST");
            assertThat(mismatch.body()).isNull();
            assertThat(app.handle(new Request("HEAD", "/missing")).body()).isNull();
        }
    }

    @Test
    void bodilessContextStatusesWithABodyFailWithTheRouteAndStatus() throws Exception {
        try (var app = Axiom.create()) {
            app.get("/returned", ctx -> { ctx.status(204); return "body"; });
            app.get("/text", ctx -> ctx.status(304).text("body"));
            app.get("/empty", ctx -> { ctx.status(205); return null; });
            app.start();
            assertThatIllegalStateException().isThrownBy(() -> app.handle(Request.get("/returned")))
                    .withMessageContaining("GET /returned").withMessageContaining("status 204");
            assertThatIllegalStateException().isThrownBy(() -> app.handle(Request.get("/text")))
                    .withMessageContaining("GET /text").withMessageContaining("status 304");
            assertThat(app.handle(Request.get("/empty")).status()).isEqualTo(205);
        }
    }

    @Test
    void propagatesHandlerFailuresAndLeavesTheApplicationUsable() throws Exception {
        var failure = new IOException("original failure");
        try (var app = Axiom.create()) {
            app.get("/fail", ctx -> { throw failure; });
            app.get("/ok", ctx -> "ok");
            app.start();
            assertThatThrownBy(() -> app.handle(Request.get("/fail"))).isSameAs(failure);
            assertThat(app.handle(Request.get("/ok")).body()).isEqualTo("ok");
        }
    }

    @Test
    @Timeout(10)
    void contextsAreIsolatedAndHandlersDoNotHoldTheLifecycleLock() throws Exception {
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var app = Axiom.create();
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            app.get("/slow", ctx -> {
                ctx.status(201);
                entered.countDown();
                if (!release.await(5, TimeUnit.SECONDS)) { throw new AssertionError("Handler was not released"); }
                return ctx.method() + " " + ctx.path();
            });
            app.get("/fast", ctx -> "fast");
            app.start();
            var slow = executor.submit(() -> app.handle(Request.get("/slow")));
            try {
                assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
                var fast = executor.submit(() -> app.handle(Request.get("/fast"))).get(2, TimeUnit.SECONDS);
                assertThat(fast.status()).isEqualTo(200);
                executor.submit(app::close).get(2, TimeUnit.SECONDS);
                assertThatIllegalStateException().isThrownBy(() -> app.handle(Request.get("/fast")));
            } finally {
                release.countDown();
            }
            var finished = slow.get(2, TimeUnit.SECONDS);
            assertThat(finished.status()).isEqualTo(201);
            assertThat(finished.body()).isEqualTo("GET /slow");
        } finally {
            release.countDown();
            app.close();
        }
    }
}
