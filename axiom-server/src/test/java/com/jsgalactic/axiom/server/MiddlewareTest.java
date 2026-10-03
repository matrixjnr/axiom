package com.jsgalactic.axiom.server;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.jsgalactic.axiom.Axiom;
import com.jsgalactic.axiom.context.Middleware;
import com.jsgalactic.axiom.error.ForbiddenException;
import com.jsgalactic.axiom.http.Request;
import com.jsgalactic.axiom.http.Response;
import com.jsgalactic.axiom.routing.Route;
import com.jsgalactic.axiom.routing.RouteGroup;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class MiddlewareTest {
    private final List<String> calls = java.util.Collections.synchronizedList(new ArrayList<>());

    private Middleware record(String name) {
        return (ctx, next) -> {
            calls.add(name + ">");
            var response = next.run();
            calls.add("<" + name);
            return response;
        };
    }

    private static Middleware header(String name, String value) {
        return (ctx, next) -> next.run().withHeader(name, value);
    }

    private static String text(Response response) {
        return response.body() instanceof byte[] bytes ? new String(bytes, StandardCharsets.UTF_8)
                : String.valueOf(response.body());
    }

    @Test
    void runsGlobalThenGroupsOuterToInnerThenRouteThenHandler() throws Exception {
        try (var app = Axiom.create()) {
            app.group("/api", api -> {
                api.group("/v1", v1 -> {
                    v1.get("/items/:id", ctx -> {
                        calls.add("handler " + ctx.path("id"));
                        return "item";
                    }, record("route1"), record("route2"));
                    v1.use(record("inner"));
                });
                api.use(record("outer"));
            });
            app.use(record("global1"));
            app.use(record("global2"));
            app.start();
            var response = app.handle(Request.get("/api/v1/items/7"));
            assertThat(response.body()).isEqualTo("item");
            assertThat(calls).containsExactly("global1>", "global2>", "outer>", "inner>", "route1>", "route2>",
                    "handler 7", "<route2", "<route1", "<inner", "<outer", "<global2", "<global1");
            assertThat(app.routes()).containsExactly(new Route("GET", "/api/v1/items/:id"));
        }
    }

    @Test
    void shortCircuitsWithoutRunningTheHandler() throws Exception {
        try (var app = Axiom.create()) {
            app.get("/secret", ctx -> {
                calls.add("handler");
                return "secret";
            }, (ctx, next) -> ctx.header("Authorization").isPresent() ? next.run() : Response.of(401, null));
            app.start();
            assertThat(app.handle(Request.get("/secret")).status()).isEqualTo(401);
            assertThat(calls).isEmpty();
            var authorized = Request.get("/secret").withHeaders(java.util.Map.of("Authorization", "Bearer x"));
            assertThat(app.handle(authorized).body()).isEqualTo("secret");
        }
    }

    @Test
    void wrapsMappedHandlerResultsIncludingContextStatus() throws Exception {
        var seen = new AtomicReference<Response>();
        try (var app = Axiom.create()) {
            app.use((ctx, next) -> {
                var response = next.run();
                seen.set(response);
                return response.withHeader("X-Frame-Options", "DENY");
            });
            app.post("/items", ctx -> ctx.status(201).response("created"));
            app.get("/empty", ctx -> null);
            app.start();
            var created = app.handle(new Request("POST", "/items"));
            assertThat(created.status()).isEqualTo(201);
            assertThat(created.headers()).containsEntry("X-Frame-Options", "DENY")
                    .containsEntry("Content-Type", "text/plain; charset=utf-8");
            assertThat(seen.get().body()).isEqualTo("created");
            var empty = app.handle(Request.get("/empty"));
            assertThat(empty.status()).isEqualTo(204);
            assertThat(empty.headers()).containsEntry("X-Frame-Options", "DENY");
        }
    }

    @Test
    void statusSetByMiddlewareAppliesToTheHandlerResult() throws Exception {
        try (var app = Axiom.create()) {
            app.get("/accepted", ctx -> "queued", (ctx, next) -> {
                ctx.status(202);
                return next.run();
            });
            app.start();
            assertThat(app.handle(Request.get("/accepted")).status()).isEqualTo(202);
        }
    }

    @Test
    void encodesCodecValuesOnceAfterTheWholeChain() throws Exception {
        record Item(String name) { }
        var seen = new AtomicReference<Object>();
        try (var app = Axiom.create()) {
            app.use((ctx, next) -> {
                var response = next.run();
                seen.set(response.body());
                return response;
            });
            app.get("/item", ctx -> ctx.json(new Item("pen")));
            app.get("/blocked", ctx -> "never", (ctx, next) -> ctx.status(403).json(new Item("none")));
            app.start();
            assertThat(text(app.handle(Request.get("/item")))).isEqualTo("name=pen");
            assertThat(seen.get()).isEqualTo(new Item("pen"));
            var blocked = app.handle(Request.get("/blocked"));
            assertThat(blocked.status()).isEqualTo(403);
            assertThat(text(blocked)).isEqualTo("name=none");
            var refused = app.handle(Request.get("/item").withHeaders(java.util.Map.of("Accept", "text/plain")));
            assertThat(refused.status()).isEqualTo(406);
        }
    }

    @Test
    void observesAndTranslatesExceptionsFromTheRestOfTheChain() throws Exception {
        try (var app = Axiom.create()) {
            app.use((ctx, next) -> {
                try {
                    return next.run();
                } catch (IllegalStateException failure) {
                    calls.add("observed");
                    return Response.of(409, "conflict");
                }
            });
            app.get("/conflict", ctx -> { throw new IllegalStateException("POISON internal detail"); });
            app.get("/propagates", ctx -> { throw new UnsupportedOperationException("POISON"); });
            app.start();
            var translated = app.handle(Request.get("/conflict"));
            assertThat(translated.status()).isEqualTo(409);
            assertThat(calls).containsExactly("observed");
            assertThatThrownBy(() -> app.handle(Request.get("/propagates")))
                    .isInstanceOf(UnsupportedOperationException.class);
        }
    }

    @Test
    void handlesMiddlewareExceptionsLikeHandlerExceptions() throws Exception {
        try (var app = Axiom.create()) {
            app.group("/admin", admin -> {
                admin.use((ctx, next) -> { throw new ForbiddenException(); });
                admin.get("/users", ctx -> "users");
            });
            app.get("/broken", ctx -> "unreachable", (ctx, next) -> { throw new IllegalArgumentException("POISON"); });
            app.start();
            var forbidden = app.handle(Request.get("/admin/users"));
            assertThat(forbidden.status()).isEqualTo(403);
            assertThat(forbidden.headers()).containsEntry("Content-Type", "application/problem+json");
            assertThat(text(forbidden)).doesNotContain("POISON").contains("\"code\":\"forbidden\"");
            assertThatThrownBy(() -> app.handle(Request.get("/broken"))).isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    void rejectsRepeatedLateAndNullContinuations() throws Exception {
        var saved = new AtomicReference<Middleware.Next>();
        try (var app = Axiom.create()) {
            app.get("/twice", ctx -> {
                calls.add("handler");
                return "once";
            }, (ctx, next) -> {
                next.run();
                return next.run();
            });
            app.get("/late", ctx -> "late", (ctx, next) -> {
                saved.set(next);
                return Response.of(200, "early");
            });
            app.get("/null", ctx -> "value", (ctx, next) -> null);
            app.start();
            assertThatIllegalStateException().isThrownBy(() -> app.handle(Request.get("/twice")));
            assertThat(calls).containsExactly("handler");
            assertThat(app.handle(Request.get("/late")).body()).isEqualTo("early");
            assertThatIllegalStateException().isThrownBy(() -> saved.get().run());
            assertThatIllegalStateException().isThrownBy(() -> app.handle(Request.get("/null")))
                    .withMessageContaining("returned null");
        }
    }

    @Test
    void rejectsContinuationsFromAnotherThread() throws Exception {
        var failure = new AtomicReference<Throwable>();
        try (var app = Axiom.create()) {
            app.get("/offloaded", ctx -> {
                calls.add("handler");
                return "ran";
            }, (ctx, next) -> {
                var other = Thread.ofPlatform().start(() -> {
                    try {
                        next.run();
                    } catch (Throwable thrown) {
                        failure.set(thrown);
                    }
                });
                other.join(10_000);
                return Response.of(202, null);
            });
            app.start();
            assertThat(app.handle(Request.get("/offloaded")).status()).isEqualTo(202);
            assertThat(failure.get()).isInstanceOf(IllegalStateException.class).hasMessageContaining("thread");
            assertThat(calls).isEmpty();
        }
    }

    @Test
    void globalMiddlewareWrapsRouterAnswersButGroupAndRouteMiddlewareDoNot() throws Exception {
        var routes = new ArrayList<String>();
        try (var app = Axiom.create()) {
            app.use((ctx, next) -> {
                try {
                    routes.add(ctx.route().path());
                } catch (IllegalStateException unmatched) {
                    routes.add("none:" + ctx.pathParameters().size());
                }
                return next.run().withHeader("X-Content-Type-Options", "nosniff");
            });
            app.group("/api", api -> {
                api.use(record("group"));
                api.get("/items/:id", ctx -> "item", record("route"));
            });
            app.start();

            var missing = app.handle(Request.get("/api/missing"));
            assertThat(missing.status()).isEqualTo(404);
            var wrongMethod = app.handle(new Request("DELETE", "/api/items/1"));
            assertThat(wrongMethod.status()).isEqualTo(405);
            assertThat(wrongMethod.headers()).containsEntry("Allow", "GET, HEAD, OPTIONS");
            var options = app.handle(new Request("OPTIONS", "/api/items/1"));
            assertThat(options.status()).isEqualTo(204);
            assertThat(options.headers()).containsEntry("Allow", "GET, HEAD, OPTIONS");
            var server = app.handle(new Request("OPTIONS", "*"));
            assertThat(server.status()).isEqualTo(204);
            var unknownMethod = app.handle(new Request("BREW", "/nowhere"));
            assertThat(unknownMethod.status()).isEqualTo(501);
            for (var response : List.of(missing, wrongMethod, options, server, unknownMethod)) {
                assertThat(response.headers()).containsEntry("X-Content-Type-Options", "nosniff");
            }
            assertThat(calls).isEmpty();
            assertThat(routes).containsOnly("none:0");

            assertThat(app.handle(Request.get("/api/items/1")).headers()).containsEntry("X-Content-Type-Options", "nosniff");
            assertThat(calls).containsExactly("group>", "route>", "<route", "<group");
            assertThat(routes).endsWith("/api/items/:id");
        }
    }

    @Test
    void globalMiddlewareCanAlwaysAskWhetherARouteMatched() throws Exception {
        var seen = java.util.Collections.synchronizedList(new ArrayList<String>());
        try (var app = Axiom.create()) {
            app.use((ctx, next) -> {
                var response = next.run();
                seen.add(ctx.method() + " " + ctx.path() + " " + response.status() + " "
                        + ctx.matchedRoute().map(Route::path).orElse("-"));
                return response;
            });
            app.get("/items/:id", ctx -> "item " + ctx.matchedRoute().orElseThrow().path());
            app.start();
            assertThat(app.handle(Request.get("/missing")).status()).isEqualTo(404);
            assertThat(app.handle(new Request("DELETE", "/items/1")).status()).isEqualTo(405);
            assertThat(app.handle(new Request("OPTIONS", "/items/1")).status()).isEqualTo(204);
            assertThat(app.handle(new Request("OPTIONS", "*")).status()).isEqualTo(204);
            assertThat(app.handle(new Request("BREW", "/missing")).status()).isEqualTo(501);
            assertThat(app.handle(Request.get("/items/1")).body()).isEqualTo("item /items/:id");
            assertThat(app.handle(new Request("HEAD", "/items/1")).status()).isEqualTo(200);
            assertThat(seen).containsExactly("GET /missing 404 -", "DELETE /items/1 405 -", "OPTIONS /items/1 204 -",
                    "OPTIONS * 204 -", "BREW /missing 501 -", "GET /items/1 200 /items/:id",
                    "HEAD /items/1 200 /items/:id");
        }
    }

    @Test
    void globalMiddlewareCanReplaceRouterAnswers() throws Exception {
        try (var app = Axiom.create()) {
            app.use((ctx, next) -> {
                var response = next.run();
                return response.status() == 404 ? Response.of(404, "custom not found") : response;
            });
            app.start();
            assertThat(app.handle(Request.get("/anything")).body()).isEqualTo("custom not found");
        }
    }

    @Test
    void requestsRejectedBeforeRoutingDoNotRunMiddleware() throws Exception {
        try (var app = Axiom.create()) {
            app.maxRequestBody(1);
            app.use(record("global"));
            app.post("/upload", ctx -> "stored");
            app.start();
            var large = new Request("POST", "/upload").withBody(com.jsgalactic.axiom.http.Body.of("text/plain", new byte[2]));
            assertThat(app.handle(large).status()).isEqualTo(413);
            assertThat(app.handle(new Request("CONNECT", "/upload")).status()).isEqualTo(501);
            assertThat(calls).isEmpty();
        }
    }

    @Test
    void headUsesTheGetChainAndStripsTheBodyAfterMiddleware() throws Exception {
        try (var app = Axiom.create()) {
            app.use(header("X-Global", "1"));
            app.group("/docs", docs -> {
                docs.use(header("X-Group", "1"));
                docs.get("/:name", ctx -> "document " + ctx.path("name"), record("route"));
            });
            app.start();
            var head = app.handle(new Request("HEAD", "/docs/a"));
            assertThat(head.status()).isEqualTo(200);
            assertThat(head.body()).isNull();
            assertThat(head.headers()).containsEntry("X-Global", "1").containsEntry("X-Group", "1")
                    .containsEntry("Content-Length", "10");
            assertThat(calls).containsExactly("route>", "<route");
            assertThat(app.handle(new Request("HEAD", "/docs/a/b")).headers()).containsEntry("X-Global", "1")
                    .doesNotContainKey("X-Group");
        }
    }

    @Test
    void useAppliesToTheWholeScopeWhereverItIsCalled() throws Exception {
        try (var app = Axiom.create()) {
            app.get("/before", ctx -> "before");
            app.use(header("X-Global", "1"));
            app.group("", scoped -> {
                scoped.get("/scoped", ctx -> "scoped");
                scoped.use(header("X-Scoped", "1"));
            });
            app.start();
            assertThat(app.handle(Request.get("/before")).headers()).containsEntry("X-Global", "1")
                    .doesNotContainKey("X-Scoped");
            assertThat(app.handle(Request.get("/scoped")).headers()).containsEntry("X-Global", "1")
                    .containsEntry("X-Scoped", "1");
        }
    }

    @Test
    void composesGroupPrefixesWithCapturesAndRelativePaths() throws Exception {
        try (var app = Axiom.create()) {
            app.group("/teams/:team", team -> {
                team.get("", ctx -> "team " + ctx.path("team"));
                team.get("/", ctx -> "team slash");
                team.group("/users", users -> users.get("/:user", ctx -> ctx.pathParameters().toString()));
            });
            app.start();
            assertThat(app.routes()).extracting(Route::path)
                    .containsExactly("/teams/:team", "/teams/:team/", "/teams/:team/users/:user");
            assertThat(app.handle(Request.get("/teams/a")).body()).isEqualTo("team a");
            assertThat(app.handle(Request.get("/teams/a/")).body()).isEqualTo("team slash");
            assertThat(app.handle(Request.get("/teams/a/users/b")).body()).isEqualTo("{team=a, user=b}");
        }
    }

    @Test
    void validatesPrefixesAndComposedTemplates() {
        try (var app = Axiom.create()) {
            assertThatIllegalArgumentException().isThrownBy(() -> app.group("api", g -> { }));
            assertThatIllegalArgumentException().isThrownBy(() -> app.group("/api/", g -> { }));
            assertThatIllegalArgumentException().isThrownBy(() -> app.group("/", g -> { }));
            assertThatIllegalArgumentException().isThrownBy(() -> app.group("/files/*rest", g -> { }));
            assertThatIllegalArgumentException().isThrownBy(() -> app.group("/a/../b", g -> { }));
            assertThatIllegalArgumentException().isThrownBy(() -> app.group("/a%2fb", g -> { }));
            assertThatIllegalArgumentException().isThrownBy(() -> app.get("", ctx -> "root"));
            app.group("/users/:id", users -> {
                assertThatIllegalArgumentException().isThrownBy(() -> users.get("items", ctx -> "x"));
                assertThatIllegalArgumentException().isThrownBy(() -> users.get("//items", ctx -> "x"));
                assertThatIllegalArgumentException().isThrownBy(() -> users.get("/:id", ctx -> "x"))
                        .withMessageContaining("Duplicate capture name");
                assertThatIllegalArgumentException().isThrownBy(() -> users.group("/x/:id", nested -> { }));
                users.get("/:other", ctx -> "x");
            });
            assertThatIllegalArgumentException().isThrownBy(() -> app.get("/users/:a/:b", ctx -> "same shape"))
                    .withMessageContaining("Ambiguous routes");
            assertThatThrownBy(() -> app.get("/x", ctx -> "x", (Middleware) null)).isInstanceOf(NullPointerException.class);
            assertThatThrownBy(() -> app.use(null)).isInstanceOf(NullPointerException.class);
        }
    }

    @Test
    void groupsCloseWhenTheirCallbackReturnsAndRegistrationEndsAtStartup() {
        var saved = new AtomicReference<RouteGroup>();
        try (var app = Axiom.create()) {
            app.group("/api", saved::set);
            assertThatIllegalStateException().isThrownBy(() -> saved.get().get("/late", ctx -> "late"));
            assertThatIllegalStateException().isThrownBy(() -> saved.get().use(record("late")));
            assertThatIllegalStateException().isThrownBy(() -> saved.get().group("/x", g -> { }));
            assertThatIllegalStateException().isThrownBy(() -> app.group("/fails", g -> {
                g.get("/kept", ctx -> "kept");
                throw new IllegalStateException("configuration failed");
            }));
            assertThat(app.routes()).isEmpty();
            app.start();
            assertThatIllegalStateException().isThrownBy(() -> app.use(record("late")));
            assertThatIllegalStateException().isThrownBy(() -> app.group("/late", g -> { }));
            assertThatIllegalStateException().isThrownBy(() -> app.get("/late", ctx -> "late", record("late")));
        }
    }

    @Test
    void routeLevelPoliciesApplyToGroupRoutes() {
        try (var app = Axiom.create()) {
            var route = new AtomicReference<Route>();
            app.group("/api", api -> route.set(api.get("/slow", ctx -> "slow")));
            var policy = com.jsgalactic.axiom.execution.AdmissionPolicy.reject(1);
            app.admissionPolicy(route.get(), policy);
            app.start();
            assertThat(app.admissionPolicy(new Route("GET", "/api/slow"))).isEqualTo(policy);
            assertThat(app.resolve(Request.get("/api/slow"))).contains(route.get());
        }
    }
}
