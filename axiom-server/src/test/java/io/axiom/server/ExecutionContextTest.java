package io.axiom.server;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import io.axiom.Axiom;
import io.axiom.execution.ExecutionContext;
import io.axiom.http.Request;
import java.time.Duration;
import org.junit.jupiter.api.Test;

class ExecutionContextTest {
    @Test void freezesTimeoutAtStartupAndPreservesExplicitExecutionIdentity() throws Exception {
        try (var app = Axiom.create()) {
            assertThat(app.requestTimeout()).isEqualTo(Duration.ofSeconds(10));
            assertThatThrownBy(() -> app.requestTimeout(Duration.ZERO)).isInstanceOf(IllegalArgumentException.class);
            assertThat(app.requestTimeout()).isEqualTo(Duration.ofSeconds(10));
            app.requestTimeout(Duration.ofSeconds(5));
            app.get("/", ctx -> ctx.execution().requestId());
            app.start();
            assertThatThrownBy(() -> app.requestTimeout(Duration.ofSeconds(1))).isInstanceOf(IllegalStateException.class);
            var execution = ExecutionContext.create(Duration.ofSeconds(5));
            assertThat(app.handle(Request.get("/"), execution).body()).isEqualTo(execution.requestId());
            assertThat(app.handle(Request.get("/")).body()).isNotEqualTo(execution.requestId());
        }
    }

    @Test void synchronousHandlerRetainsCallerThreadAndExceptionSemantics() throws Exception {
        var caller = Thread.currentThread();
        try (var app = Axiom.create()) {
            app.get("/", ctx -> {
                assertThat(Thread.currentThread()).isSameAs(caller);
                assertThat(ctx.execution()).isNotNull();
                throw new IllegalArgumentException("unchanged");
            });
            app.start();
            assertThatThrownBy(() -> app.handle(Request.get("/")))
                    .isInstanceOf(IllegalArgumentException.class).hasMessage("unchanged");
        }
    }
}
