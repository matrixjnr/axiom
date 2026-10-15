package com.jsgalactic.axiom.server;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.jsgalactic.axiom.Axiom;
import com.jsgalactic.axiom.http.Request;
import com.jsgalactic.axiom.http.Response;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

class ResponseOwnershipTest {
    record MutableNote(StringBuilder title) { }

    @Test
    void mutationsAfterJsonReturnsCannotChangeTheResponse() throws Exception {
        var source = new StringBuilder("before");
        try (var app = Axiom.create()) {
            app.use((ctx, next) -> {
                var response = next.run();
                source.append("-middleware");
                ((byte[]) response.body())[0] = 'X';
                return response;
            });
            app.get("/", ctx -> {
                var response = ctx.json(new MutableNote(source));
                source.append("-handler");
                return response;
            });
            app.start();
            var response = app.handle(Request.get("/"));
            source.append("-caller");
            assertThat(text(response)).isEqualTo("title=before");
            assertThat(source.toString()).isEqualTo("before-handler-middleware-caller");
        }
    }

    @Test
    @Timeout(10)
    void anotherThreadMayMutateTheSourceAfterEncodingCompletes() throws Exception {
        var ready = new CountDownLatch(1);
        var mutated = new CountDownLatch(1);
        var source = new StringBuilder("before");
        try (var app = Axiom.create(); var worker = Executors.newSingleThreadExecutor()) {
            app.get("/", ctx -> {
                var response = ctx.json(new MutableNote(source));
                ready.countDown();
                if (!mutated.await(5, TimeUnit.SECONDS)) { throw new IllegalStateException("Mutation did not finish"); }
                return response;
            });
            app.start();
            var result = worker.submit(() -> app.handle(Request.get("/")));
            try {
                assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
                source.append("-other-thread");
            } finally { mutated.countDown(); }
            assertThat(text(result.get(5, TimeUnit.SECONDS))).isEqualTo("title=before");
        }
    }

    @Test
    void missingCodecsAndEncodingFailuresAreMappedDuringTheCall() throws Exception {
        try (var app = Axiom.create()) {
            app.error(IllegalStateException.class, (ctx, failure) -> ctx.status(422).text("encoding_failed"));
            app.get("/missing", ctx -> ctx.encode("application/x-missing", new Object()));
            app.get("/unsupported", ctx -> ctx.json(new Object()));
            app.get("/null", ctx -> ctx.json(null));
            app.start();
            for (var path : new String[] {"/missing", "/unsupported"}) {
                var response = app.handle(Request.get(path));
                assertThat(response.status()).isEqualTo(422);
                assertThat(response.body()).isEqualTo("encoding_failed");
            }
            assertThatThrownBy(() -> app.handle(Request.get("/null"))).isInstanceOf(NullPointerException.class);
        }
    }

    @Test
    void validatesContentTypeAndStatusBeforeEncoding() throws Exception {
        try (var app = Axiom.create()) {
            app.get("/", ctx -> {
                for (var type : new String[] {"invalid", "application/*", "application/json\r\nX: y"}) {
                    assertThatThrownBy(() -> ctx.encode(type, new Object())).isInstanceOf(IllegalArgumentException.class);
                }
                ctx.status(204);
                assertThatThrownBy(() -> ctx.json(new Object())).isInstanceOf(IllegalStateException.class)
                        .hasMessageContaining("cannot carry a body");
                return ctx.noContent();
            });
            app.start();
            assertThat(app.handle(Request.get("/")).status()).isEqualTo(204);
        }
    }

    private static String text(Response response) {
        return new String((byte[]) response.body(), StandardCharsets.UTF_8);
    }
}
