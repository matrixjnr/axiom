package io.axiom.test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

import io.axiom.Axiom;
import io.axiom.application.Application;
import io.axiom.http.Request;
import org.junit.jupiter.api.Test;

class TestClientTest {
    @Test
    void usesRealDispatchAndOwnsTheApplicationLifecycle() throws Exception {
        var app = Axiom.create();
        app.get("/", ctx -> "Hello, world!");
        app.post("/created", ctx -> ctx.status(201).text("created"));
        try (var client = TestClient.start(app)) {
            assertThat(client.get("/").body()).isEqualTo("Hello, world!");
            assertThat(client.get("/missing").status()).isEqualTo(404);
            assertThat(client.execute(new Request("POST", "/created")).status()).isEqualTo(201);
            assertThatIllegalStateException().isThrownBy(() -> app.get("/late", ctx -> "late"));
        }
        assertThat(app.state()).isEqualTo(Application.State.CLOSED);
    }

    @Test
    void rejectsWithServiceUnavailableWhenAdmissionCapacityIsHeld() throws Exception {
        var entered = new java.util.concurrent.CountDownLatch(1);
        var release = new java.util.concurrent.CountDownLatch(1);
        var app = Axiom.create();
        app.admissionPolicy(io.axiom.execution.AdmissionPolicy.reject(1));
        app.get("/slow", ctx -> { entered.countDown(); release.await(); return "done"; });
        try (var client = TestClient.start(app)) {
            var first = client.submit(Request.get("/slow"));
            assertThat(entered.await(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            assertThat(client.get("/slow").status()).isEqualTo(503);
            release.countDown();
            assertThat(first.get(5, java.util.concurrent.TimeUnit.SECONDS).body()).isEqualTo("done");
        } finally { release.countDown(); }
    }

    @Test
    void answersGatewayTimeoutWhenTheDeadlineExpires() throws Exception {
        var app = Axiom.create();
        app.requestTimeout(java.time.Duration.ofMillis(50));
        app.get("/hang", ctx -> { new java.util.concurrent.CountDownLatch(1).await(); return "never"; });
        try (var client = TestClient.start(app)) {
            assertThat(client.get("/hang").status()).isEqualTo(504);
        }
    }

    @Test
    void failsWhereTheTransportWouldAnswer500() throws Exception {
        var app = Axiom.create();
        app.get("/object", ctx -> java.util.List.of("not serializable"));
        try (var client = TestClient.start(app)) {
            org.assertj.core.api.Assertions.assertThatThrownBy(() -> client.get("/object"))
                    .isInstanceOf(IllegalStateException.class).hasMessageContaining("cannot be serialized");
        }
    }
}
