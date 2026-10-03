package com.jsgalactic.axiom.server;

import static org.assertj.core.api.Assertions.assertThat;

import com.jsgalactic.axiom.Axiom;
import com.jsgalactic.axiom.context.SecurityIdentity;
import com.jsgalactic.axiom.http.Request;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** The request-scoped identity slot of the runtime's context. */
class IdentityTest {
    @Test
    void middlewareAttachesAnIdentityThatTheHandlerAndErrorHandlersSee() throws Exception {
        try (var app = Axiom.create()) {
            app.use((ctx, next) -> {
                if (ctx.header("X-User").isPresent()) {
                    ctx.identity(new SecurityIdentity(ctx.header("X-User").get(), Set.of("reader"), Set.of()));
                }
                return next.run();
            });
            app.get("/me", ctx -> ctx.identity().map(SecurityIdentity::principal).orElse("anonymous"));
            app.get("/fail", ctx -> { throw new IllegalStateException("boom"); });
            app.error(IllegalStateException.class,
                    (ctx, failure) -> ctx.text("failed for " + ctx.identity().map(SecurityIdentity::principal).orElse("-")));
            app.start();
            assertThat(app.handle(Request.get("/me")).body()).isEqualTo("anonymous");
            assertThat(app.handle(Request.get("/me").withHeaders(java.util.Map.of("X-User", "ada"))).body()).isEqualTo("ada");
            // A fresh context per request: the previous identity does not leak into the next one.
            assertThat(app.handle(Request.get("/me")).body()).isEqualTo("anonymous");
            assertThat(app.handle(Request.get("/fail").withHeaders(java.util.Map.of("X-User", "ada"))).body())
                    .isEqualTo("failed for ada");
        }
    }

    @Test
    void aVerifiedIdentityCannotBeReplacedLaterInTheChain() throws Exception {
        try (var app = Axiom.create()) {
            app.use((ctx, next) -> { ctx.identity(SecurityIdentity.of("ada")); return next.run(); });
            app.get("/escalate", ctx -> {
                try {
                    ctx.identity(new SecurityIdentity("root", Set.of("admin"), Set.of()));
                    return "replaced";
                } catch (IllegalStateException refused) {
                    return "kept " + ctx.identity().orElseThrow().principal();
                }
            });
            app.start();
            assertThat(app.handle(Request.get("/escalate")).body()).isEqualTo("kept ada");
        }
    }

    @Test
    void routerAnswersRunGlobalMiddlewareWithAnIdentitySlotToo() throws Exception {
        try (var app = Axiom.create()) {
            app.use((ctx, next) -> {
                ctx.identity(SecurityIdentity.of("ada"));
                return next.run().withHeader("X-Principal", ctx.identity().orElseThrow().principal());
            });
            app.get("/known", ctx -> "ok");
            app.start();
            assertThat(app.handle(Request.get("/unknown")).headers()).containsEntry("X-Principal", "ada");
        }
    }
}
