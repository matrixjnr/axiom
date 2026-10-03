package com.jsgalactic.axiom.server;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

import com.jsgalactic.axiom.Axiom;
import com.jsgalactic.axiom.context.Middleware;
import com.jsgalactic.axiom.execution.AdmissionPolicy;
import com.jsgalactic.axiom.http.Request;
import com.jsgalactic.axiom.http.Response;
import com.jsgalactic.axiom.routing.Route;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

@Timeout(30)
class GroupAtomicityTest {
    private static final Middleware DENY_ALL = (ctx, next) -> Response.of(401, null);

    @Test
    void aThrowingCallbackLeavesNothingRegistered() throws Exception {
        try (var app = Axiom.create()) {
            try {
                app.group("/admin", admin -> {
                    var route = admin.get("/users/:id", ctx -> "secret");
                    app.admissionPolicy(route, AdmissionPolicy.reject(1));
                    admin.group("/audit", audit -> audit.get("", ctx -> "audit log"));
                    throw new IllegalStateException("configuration failed");
                    // admin.use(DENY_ALL) never runs
                });
            } catch (IllegalStateException ignored) {
                // An application that swallows the failure and starts anyway must not serve the routes.
            }
            assertThat(app.routes()).isEmpty();
            assertThatIllegalArgumentException().isThrownBy(
                    () -> app.admissionPolicy(new Route("GET", "/admin/users/:id")));
            // The rolled-back shape no longer blocks a same-shape template.
            app.get("/admin/users/:name", ctx -> "public", DENY_ALL);
            app.start();
            assertThat(app.handle(Request.get("/admin/users/1")).status()).isEqualTo(401);
            assertThat(app.handle(Request.get("/admin/audit")).status()).isEqualTo(404);
        }
    }

    @Test
    void aNestedFailureRollsBackOnlyItsScopeThenPropagates() throws Exception {
        try (var app = Axiom.create()) {
            app.group("/api", api -> {
                api.get("/kept", ctx -> "kept");
                try {
                    api.group("/admin", admin -> {
                        admin.get("/users", ctx -> "secret");
                        throw new IllegalArgumentException("nested failure");
                    });
                } catch (IllegalArgumentException expected) {
                    assertThat(expected).hasMessage("nested failure");
                }
            });
            assertThat(app.routes()).extracting(Route::path).containsExactly("/api/kept");

            assertThatIllegalArgumentException().isThrownBy(() -> app.group("/outer", outer -> {
                outer.get("/a", ctx -> "a");
                outer.group("/inner", inner -> {
                    inner.get("/b", ctx -> "b");
                    throw new IllegalArgumentException("uncaught");
                });
            }));
            assertThat(app.routes()).extracting(Route::path).containsExactly("/api/kept");
        }
    }

    @Test
    void startIsRefusedWhileAGroupCallbackIsOpen() throws Exception {
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var failure = new AtomicReference<Throwable>();
        try (var app = Axiom.create()) {
            var configuring = Thread.ofPlatform().start(() -> {
                try {
                    app.group("/admin", admin -> {
                        admin.get("/users", ctx -> "secret");
                        entered.countDown();
                        try {
                            assertThat(release.await(10, TimeUnit.SECONDS)).isTrue();
                        } catch (InterruptedException interrupted) {
                            Thread.currentThread().interrupt();
                        }
                        admin.use(DENY_ALL);
                    });
                } catch (Throwable unexpected) {
                    failure.set(unexpected);
                }
            });
            assertThat(entered.await(10, TimeUnit.SECONDS)).isTrue();
            assertThatIllegalStateException().isThrownBy(app::start).withMessageContaining("route group");
            assertThat(app.state()).isEqualTo(com.jsgalactic.axiom.application.Application.State.CONFIGURING);
            release.countDown();
            configuring.join(10_000);
            assertThat(failure.get()).isNull();
            app.start();
            assertThat(app.handle(Request.get("/admin/users")).status()).isEqualTo(401);
        }
    }
}
