package io.axiom.test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import io.axiom.Axiom;
import io.axiom.execution.AdmissionPolicy;
import io.axiom.http.Request;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/** Method handling through the test client, which must agree with the listener. */
class TestClientMethodsTest {
    @Test
    void rejectsMethodsThatAreNotTokensAndMatchesTheRestCaseSensitively() throws Exception {
        var app = Axiom.create();
        app.route("PROPFIND", "/x", ctx -> ctx.method());
        app.get("/x", ctx -> ctx.method());
        try (var client = TestClient.start(app)) {
            // The listener answers these with 400 before routing.
            assertThatIllegalArgumentException().isThrownBy(() -> Request.fromTarget("G(T", "/x"));
            assertThatIllegalArgumentException().isThrownBy(() -> Request.fromTarget("G T", "/x"));
            assertThat(client.execute(new Request("PROPFIND", "/x")).body()).isEqualTo("PROPFIND");
            var lower = client.execute(new Request("propfind", "/x"));
            assertThat(lower.status()).isEqualTo(405);
            assertThat(lower.headers()).containsEntry("Allow", "GET, HEAD, PROPFIND");
        }
    }

    @Test
    void answersOptionsAutomatically() throws Exception {
        var app = Axiom.create();
        app.get("/x", ctx -> "x");
        try (var client = TestClient.start(app)) {
            var options = client.execute(new Request("OPTIONS", "/x"));
            assertThat(options.status()).isEqualTo(204);
            assertThat(options.headers()).containsEntry("Allow", "GET, HEAD, OPTIONS");
            assertThat(client.execute(new Request("OPTIONS", "/missing")).status()).isEqualTo(404);
        }
    }

    @Test
    void answersOptionsAsteriskAndRejectsTheAsteriskFormForOtherMethods() throws Exception {
        var app = Axiom.create();
        app.get("/x", ctx -> "x");
        app.put("/y", ctx -> "y");
        try (var client = TestClient.start(app)) {
            var asterisk = client.execute(Request.fromTarget("OPTIONS", "*"));
            assertThat(asterisk.status()).isEqualTo(204);
            assertThat(asterisk.headers()).containsEntry("Allow", "GET, HEAD, OPTIONS, PUT");
            assertThatIllegalArgumentException().isThrownBy(() -> Request.fromTarget("GET", "*"));
        }
    }

    @Test
    void refusesTraceRoutesAndAnswersTraceWith405() throws Exception {
        var app = Axiom.create();
        app.get("/x", ctx -> "x");
        assertThatIllegalArgumentException().isThrownBy(() -> app.route("TRACE", "/x", ctx -> "x"));
        try (var client = TestClient.start(app)) {
            var trace = client.execute(new Request("TRACE", "/x"));
            assertThat(trace.status()).isEqualTo(405);
            assertThat(trace.headers()).containsEntry("Allow", "GET, HEAD");
        }
    }

    @Test
    void refusesConnectRoutesAndAnswersConnectWith501() throws Exception {
        var app = Axiom.create();
        app.get("/x", ctx -> "x");
        assertThatIllegalArgumentException().isThrownBy(() -> app.route("CONNECT", "/x", ctx -> "x"));
        try (var client = TestClient.start(app)) {
            assertThat(client.execute(new Request("CONNECT", "/x")).status()).isEqualTo(501);
        }
    }

    @Test
    void routesCustomMethodsAndAnswersUnrecognizedMethodsWith501() throws Exception {
        var app = Axiom.create();
        app.route("REPORT", "/r", ctx -> "report");
        try (var client = TestClient.start(app)) {
            assertThat(client.execute(new Request("REPORT", "/r")).body()).isEqualTo("report");
            assertThat(client.execute(new Request("FOO", "/r")).status()).isEqualTo(405);
            assertThat(client.execute(new Request("REPORT", "/missing")).status()).isEqualTo(404);
            assertThat(client.execute(new Request("FOO", "/missing")).status()).isEqualTo(501);
            assertThat(client.execute(new Request("get", "/missing")).status()).isEqualTo(501);
            assertThat(client.get("/missing").status()).isEqualTo(404);
        }
    }

    @Test
    void ignoresMethodOverrideHeaders() throws Exception {
        var app = Axiom.create();
        app.post("/x", ctx -> "post");
        app.delete("/x", ctx -> "delete");
        try (var client = TestClient.start(app)) {
            for (var header : new String[] {"X-HTTP-Method-Override", "X-HTTP-Method", "X-Method-Override"}) {
                var request = new Request("POST", "/x", java.util.Map.of(header, "DELETE"), io.axiom.http.Body.empty());
                assertThat(client.execute(request).body()).as(header).isEqualTo("post");
            }
        }
    }

    @Test
    void admitsAutomaticOptionsUnderTheDefaultPolicy() throws Exception {
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var app = Axiom.create();
        app.admissionPolicy(AdmissionPolicy.reject(1));
        app.get("/slow", ctx -> { entered.countDown(); release.await(); return "done"; });
        try (var client = TestClient.start(app)) {
            var first = client.submit(Request.get("/slow"));
            assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
            // Automatic OPTIONS runs no handler but is admitted like any request, so it can be refused.
            assertThat(client.execute(new Request("OPTIONS", "/slow")).status()).isEqualTo(503);
            release.countDown();
            assertThat(first.get(5, TimeUnit.SECONDS).body()).isEqualTo("done");
            assertThat(client.execute(new Request("OPTIONS", "/slow")).status()).isEqualTo(204);
        } finally { release.countDown(); }
    }
}
