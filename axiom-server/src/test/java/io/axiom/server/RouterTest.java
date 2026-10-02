package io.axiom.server;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.axiom.Axiom;
import io.axiom.application.Application;
import io.axiom.http.InvalidRequestPathException;
import io.axiom.http.Request;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

class RouterTest {
    @Test
    void exposesTheMatchedTemplateAndRawCaptures() throws Exception {
        try (var app = Axiom.create()) {
            var route = app.get("/teams/:team/users/:user", ctx -> {
                assertThat(ctx.method()).isEqualTo("GET");
                assertThat(ctx.path()).isEqualTo("/teams/a%20b/users/%E2%82%AC");
                assertThat(ctx.route().path()).isEqualTo("/teams/:team/users/:user");
                assertThat(ctx.path("team")).isEqualTo("a%20b");
                assertThat(ctx.path("user")).isEqualTo("%E2%82%AC");
                assertThat(ctx.pathParameters()).containsExactly(
                        Map.entry("team", "a%20b"), Map.entry("user", "%E2%82%AC"));
                assertThat(ctx.path("team")).isEqualTo("a%20b");
                assertThatThrownBy(() -> ctx.pathParameters().put("team", "changed"))
                        .isInstanceOf(UnsupportedOperationException.class);
                assertThatIllegalArgumentException().isThrownBy(() -> ctx.path("missing"));
                return ctx.route();
            });
            app.start();
            assertThat(app.handle(Request.get("/teams/a%20b/users/%E2%82%AC")).body()).isEqualTo(route);
        }
    }

    @Test
    void staticRoutesExposeAnEmptyImmutableParameterMap() throws Exception {
        try (var app = Axiom.create()) {
            app.get("/", ctx -> {
                assertThat(ctx.pathParameters()).isEmpty();
                assertThatIllegalArgumentException().isThrownBy(() -> ctx.path("id"));
                assertThatThrownBy(() -> ctx.pathParameters().put("id", "x"))
                        .isInstanceOf(UnsupportedOperationException.class);
                return "ok";
            });
            app.start();
            assertThat(app.handle(Request.get("/")).body()).isEqualTo("ok");
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"/a/b", "/a%20b", "/a+b", "/a/b/", "/é", "/"})
    void wildcardPreservesTheRawRemainder(String suffix) throws Exception {
        try (var app = Axiom.create()) {
            app.get("/files/*path", ctx -> ctx.path("path"));
            app.start();
            assertThat(app.handle(Request.get("/files" + suffix)).body()).isEqualTo(suffix.substring(1));
            assertThat(app.handle(Request.get("/files")).status()).isEqualTo(404);
        }
    }

    @Test
    void parametersRequireNonEmptySegmentsAndTrailingSlashesRemainDistinct() throws Exception {
        try (var app = Axiom.create()) {
            app.get("/users/:id", ctx -> ctx.path("id"));
            app.get("/users/:id/", ctx -> "trailing:" + ctx.path("id"));
            app.start();
            assertThat(app.handle(Request.get("/users/")).status()).isEqualTo(404);
            assertThat(app.handle(Request.get("/users/7")).body()).isEqualTo("7");
            assertThat(app.handle(Request.get("/users/7/")).body()).isEqualTo("trailing:7");
            assertThat(app.handle(Request.get("/Users/7")).status()).isEqualTo(404);
        }
    }

    @Test
    void decodesCapturesOnceAsStrictUtf8() throws Exception {
        try (var app = Axiom.create()) {
            app.get("/users/:id", ctx -> ctx.pathDecoded("id"));
            app.get("/files/*path", ctx -> ctx.pathDecoded("path"));
            app.start();
            assertThat(app.handle(Request.get("/users/a%20b%E2%82%AC")).body()).isEqualTo("a b\u20ac");
            assertThat(app.handle(Request.get("/users/%2541")).body()).isEqualTo("%41");
            assertThat(app.handle(Request.get("/users/é+x")).body()).isEqualTo("é+x");
            assertThat(app.handle(Request.get("/files/a%20b/c/")).body()).isEqualTo("a b/c/");
            assertThat(app.handle(Request.get("/files/")).body()).isEqualTo("");
            for (var invalid : new String[] {"/users/%FF", "/users/%C3", "/users/%C3%28", "/files/a/%ED%A0%80"}) {
                assertThatIllegalArgumentException().as(invalid)
                        .isThrownBy(() -> app.handle(Request.get(invalid)));
            }
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"/files/../secret", "/files/a/./b", "/files//etc", "/files/%2e%2e/secret",
            "/files/a%2Fb", "/files/a%5Cb", "/files/a%00"})
    void wildcardsNeverReceiveTraversalOrEncodedSeparators(String path) {
        assertThatThrownBy(() -> Request.get(path)).isInstanceOf(InvalidRequestPathException.class);
    }

    @Test
    void precedenceIsIndependentOfRegistrationOrder() throws Exception {
        var templates = List.of("/users/new", "/users/:id", "/users/*rest", "/*all");
        for (int seed = 0; seed < 12; seed++) {
            var shuffled = new ArrayList<>(templates);
            Collections.shuffle(shuffled, new Random(seed));
            try (var app = Axiom.create()) {
                for (var template : shuffled) { app.get(template, ctx -> ctx.route().path()); }
                app.start();
                assertThat(app.handle(Request.get("/users/new")).body()).isEqualTo("/users/new");
                assertThat(app.handle(Request.get("/users/7")).body()).isEqualTo("/users/:id");
                assertThat(app.handle(Request.get("/users/7/photos")).body()).isEqualTo("/users/*rest");
                assertThat(app.handle(Request.get("/users/")).body()).isEqualTo("/users/*rest");
                assertThat(app.handle(Request.get("/other")).body()).isEqualTo("/*all");
            }
        }
    }

    @Test
    void backtracksOnlyWhenTheMoreSpecificBranchCannotMatchTheCompletePath() throws Exception {
        try (var app = Axiom.create()) {
            app.get("/a/static/dead", ctx -> "dead");
            app.get("/a/:id/end", ctx -> "param:" + ctx.path("id"));
            app.get("/a/*rest", ctx -> "wild:" + ctx.path("rest"));
            app.get("/:first/static", ctx -> "root:" + ctx.path("first"));
            app.start();
            assertThat(app.handle(Request.get("/a/static/end")).body()).isEqualTo("param:static");
            assertThat(app.handle(Request.get("/a/value/missing")).body()).isEqualTo("wild:value/missing");
            assertThat(app.handle(Request.get("/a/static")).body()).isEqualTo("wild:static");
            assertThat(app.handle(Request.get("/b/static")).body()).isEqualTo("root:b");
        }
    }

    @Test
    void selectsThePathBeforeTheMethodAndPrecomputesItsAllowHeader() throws Exception {
        try (var app = Axiom.create()) {
            app.post("/users/new", ctx -> "static post");
            app.get("/users/:id", ctx -> "parameter get");
            app.put("/users/:name", ctx -> ctx.path("name"));
            app.patch("/users/*rest", ctx -> "wildcard patch");
            app.start();
            var literalMismatch = app.handle(Request.get("/users/new"));
            assertThat(literalMismatch.status()).isEqualTo(405);
            assertThat(literalMismatch.headers()).containsEntry("Allow", "POST");
            var parameterMismatch = app.handle(new Request("PATCH", "/users/7"));
            assertThat(parameterMismatch.status()).isEqualTo(405);
            assertThat(parameterMismatch.headers()).containsEntry("Allow", "GET, PUT");
            assertThat(app.handle(new Request("PUT", "/users/7")).body()).isEqualTo("7");
            assertThat(app.handle(new Request("PATCH", "/users/7/photo")).body()).isEqualTo("wildcard patch");
        }
    }

    @Test
    void parameterNamesBelongToEndpointsNotSharedTrieEdges() throws Exception {
        try (var app = Axiom.create()) {
            app.get("/:team/members/:member", ctx -> ctx.pathParameters());
            app.get("/:organization/settings/:key", ctx -> ctx.pathParameters());
            app.post("/:company/members/:person", ctx -> ctx.pathParameters());
            app.start();
            assertThat(app.handle(Request.get("/t/members/m")).body())
                    .isEqualTo(Map.of("team", "t", "member", "m"));
            assertThat(app.handle(Request.get("/o/settings/k")).body())
                    .isEqualTo(Map.of("organization", "o", "key", "k"));
            assertThat(app.handle(new Request("POST", "/c/members/p")).body())
                    .isEqualTo(Map.of("company", "c", "person", "p"));
        }
    }

    @ParameterizedTest
    @CsvSource({"/users/:id,/users/:name", "/:a/:b,/:x/:y", "/files/*path,/files/*rest",
            "/:id/*tail,/:name/*rest"})
    void rejectsEquivalentTemplatesAtStartupWithoutPublishingPartialState(String first, String second) {
        try (var app = Axiom.create()) {
            app.get(first, ctx -> "first");
            app.get(second, ctx -> "second");
            assertThatIllegalArgumentException().isThrownBy(app::start)
                    .withMessageContaining("Ambiguous routes for GET").withMessageContaining(first)
                    .withMessageContaining(second);
            assertThat(app.state()).isEqualTo(Application.State.CONFIGURING);
            assertThat(app.routes()).hasSize(2);
            assertThatIllegalStateException().isThrownBy(() -> app.handle(Request.get("/users/1")));
            assertThatIllegalArgumentException().isThrownBy(app::start);
        }
    }

    @Test
    void failedBacktrackingDoesNotLeakCaptureValues() throws Exception {
        try (var app = Axiom.create()) {
            app.get("/:wrong/fixed/no", ctx -> ctx.pathParameters());
            app.get("/:right/:next/yes", ctx -> ctx.pathParameters());
            app.start();
            assertThat(app.handle(Request.get("/a/fixed/yes")).body())
                    .isEqualTo(Map.of("right", "a", "next", "fixed"));
        }
    }

    @Test
    void headSuppressesBodiesForParameterizedAndWildcardRoutesAndMethodErrors() throws Exception {
        try (var app = Axiom.create()) {
            app.head("/users/:id", ctx -> ctx.path("id"));
            app.head("/files/*path", ctx -> ctx.path("path"));
            app.get("/:other", ctx -> "get-only");
            app.start();
            assertThat(app.handle(new Request("HEAD", "/users/1")).body()).isNull();
            assertThat(app.handle(new Request("HEAD", "/files/a/b")).body()).isNull();
            var mismatch = app.handle(new Request("HEAD", "/other"));
            assertThat(mismatch.status()).isEqualTo(405);
            assertThat(mismatch.body()).isNull();
        }
    }

    @Test
    void supportsLargeRouteSetsWithoutChangingSpecificity() throws Exception {
        try (var app = Axiom.create()) {
            for (int i = 0; i < 5_000; i++) {
                app.get("/groups/g" + i + "/items/:id", ctx -> ctx.path("id"));
                app.get("/static/" + i, ctx -> ctx.path());
            }
            app.start();
            for (int i = 0; i < 5_000; i += 97) {
                assertThat(app.handle(Request.get("/groups/g" + i + "/items/value")).body()).isEqualTo("value");
                assertThat(app.handle(Request.get("/static/" + i)).body()).isEqualTo("/static/" + i);
            }
            assertThat(app.handle(Request.get("/groups/missing/items/value")).status()).isEqualTo(404);
        }
    }

    @Test
    void deepTemplatesCompileAndMatchWithoutRecursiveStackGrowth() throws Exception {
        var prefix = "/s".repeat(5_000);
        try (var app = Axiom.create()) {
            app.get(prefix + "/:id", ctx -> ctx.path("id"));
            app.get(prefix + "/static", ctx -> "static");
            app.start();
            assertThat(app.handle(Request.get(prefix + "/value")).body()).isEqualTo("value");
            assertThat(app.handle(Request.get(prefix + "/static")).body()).isEqualTo("static");
        }
    }

    @Test
    @Timeout(15)
    void concurrentRequestsNeverShareCaptures() throws Exception {
        var start = new CountDownLatch(1);
        try (var app = Axiom.create(); var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            app.get("/users/:id/files/*path", ctx -> ctx.path("id") + ":" + ctx.pathParameters().get("path"));
            app.start();
            var calls = new ArrayList<Callable<String>>();
            for (int i = 0; i < 200; i++) {
                final int id = i;
                calls.add(() -> {
                    if (!start.await(5, TimeUnit.SECONDS)) { throw new AssertionError("Start gate timed out"); }
                    return (String) app.handle(Request.get("/users/" + id + "/files/a/" + id)).body();
                });
            }
            var futures = calls.stream().map(executor::submit).toList();
            start.countDown();
            for (int i = 0; i < futures.size(); i++) {
                assertThat(futures.get(i).get(5, TimeUnit.SECONDS)).isEqualTo(i + ":a/" + i);
            }
        } finally {
            start.countDown();
        }
    }
}
