package com.jsgalactic.axiom.server;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.jsgalactic.axiom.Axiom;
import com.jsgalactic.axiom.http.InvalidRequestPathException;
import com.jsgalactic.axiom.http.Request;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** The path and wildcard policy of the routing guide: reject ambiguous paths, match exactly, never redirect. */
class PathPolicyTest {
    @ParameterizedTest
    @ValueSource(strings = {"//files/a", "/files//a", "/files/./a", "/files/../a", "/files/a\\b", "/files/a%00b",
            "/files/a%2Fb", "/files/a%2fb", "/files/a%5Cb", "/files/%2e%2e/a", "/files/%2E/a", "/files/a%zz"})
    void rejectsPathsThatCouldBeReadAsAnotherResourceBeforeRouting(String path) {
        assertThatThrownBy(() -> new Request("GET", path)).isInstanceOf(InvalidRequestPathException.class);
    }

    @Test void matchesExactlyAndNeverRedirects() throws Exception {
        try (var app = Axiom.create()) {
            app.get("/users", ctx -> "list");
            app.get("/files/*path", ctx -> "file:" + ctx.path("path"));
            app.start();
            assertThat(app.handle(Request.get("/users")).body()).isEqualTo("list");
            // A trailing slash and a different case are different paths: 404, never a redirect.
            for (var path : new String[] {"/users/", "/Users", "/USERS"}) {
                var response = app.handle(Request.get(path));
                assertThat(response.status()).as(path).isEqualTo(404);
                assertThat(response.headers()).as(path).doesNotContainKey("Location");
            }
            // A wildcard remainder is raw, may be empty, and never holds an empty or dot segment.
            assertThat(app.handle(Request.get("/files/")).body()).isEqualTo("file:");
            assertThat(app.handle(Request.get("/files/a/b%20c")).body()).isEqualTo("file:a/b%20c");
            assertThat(app.handle(Request.get("/files")).status()).isEqualTo(404);
        }
        // Registering both spellings is how an application serves both.
        try (var both = Axiom.create()) {
            both.get("/users", ctx -> "list");
            both.get("/users/", ctx -> "list");
            both.start();
            assertThat(both.handle(Request.get("/users/")).body()).isEqualTo("list");
        }
    }
}
