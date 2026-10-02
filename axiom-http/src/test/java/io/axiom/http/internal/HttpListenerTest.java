package io.axiom.http.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.axiom.Axiom;
import io.axiom.application.Application;
import io.axiom.http.Response;
import io.axiom.lifecycle.Server;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class HttpListenerTest {
    @Test void routesRawPathsAndWritesUtf8AndBytes() throws Exception {
        try (var fixture = new Fixture()) {
            fixture.app.get("/users/:id", ctx -> "héllo " + ctx.path("id"));
            fixture.app.get("/bytes", ctx -> new byte[] {0, 1, -1});
            fixture.app.get("/empty", ctx -> null);
            var server = fixture.listen();
            try (var wire = new Wire(server)) {
                var response = wire.get("/users/a%20b?ignored=yes");
                assertThat(response.status()).isEqualTo(200);
                assertThat(response.text()).isEqualTo("héllo a%20b");
                assertThat(response.headers()).containsEntry("content-type", "text/plain; charset=utf-8");
                assertThat(wire.get("/bytes").body()).containsExactly(0, 1, (byte) -1);
                assertThat(wire.get("/empty").status()).isEqualTo(204);
                assertThat(wire.get("/missing").status()).isEqualTo(404);
                wire.write("POST /users/x HTTP/1.1\r\nHost: localhost\r\n\r\n");
                var mismatch = wire.read(false);
                assertThat(mismatch.status()).isEqualTo(405);
                assertThat(mismatch.headers()).containsEntry("allow", "GET");
            }
        }
    }

    @Test void ownsFramingAndSuppressesHeadAndHopHeaders() throws Exception {
        try (var fixture = new Fixture()) {
            fixture.app.get("/", ctx -> Response.of(200, "ok").withHeader("Content-Length", "999")
                    .withHeader("Transfer-Encoding", "chunked").withHeader("Connection", "X-Hop")
                    .withHeader("X-Hop", "private").withHeader("X-Result", "yes"));
            fixture.app.head("/", ctx -> "not transmitted");
            fixture.app.get("/reset", ctx -> Response.of(205, null));
            fixture.app.get("/cached", ctx -> Response.of(304, null));
            var server = fixture.listen();
            try (var wire = new Wire(server)) {
                var response = wire.get("/");
                assertThat(response.text()).isEqualTo("ok");
                assertThat(response.headers()).containsEntry("content-length", "2")
                        .containsEntry("x-result", "yes").doesNotContainKeys("transfer-encoding", "x-hop");
                wire.write("HEAD / HTTP/1.1\r\nHost: localhost\r\n\r\n");
                assertThat(wire.read(true).headers()).doesNotContainKey("content-length");
                assertThat(wire.get("/reset").headers()).containsEntry("content-length", "0");
                assertThat(wire.get("/cached").headers()).doesNotContainKey("content-length");
                wire.write("GET / HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n");
                assertThat(wire.read(false).text()).isEqualTo("ok");
                assertThat(wire.socket.getInputStream().read()).isEqualTo(-1);
            }
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"exception", "object", "oversize", "header", "application-timeout", "application-cancel", "application-reject"})
    void mapsFailuresToSafeFinalResponse(String failure) throws Exception {
        try (var fixture = new Fixture()) {
            fixture.app.get("/", ctx -> switch (failure) {
                case "exception" -> throw new IOException("secret");
                case "application-timeout" -> throw new java.util.concurrent.TimeoutException("downstream secret");
                case "application-reject" -> throw new java.util.concurrent.RejectedExecutionException("downstream overloaded");
                case "application-cancel" -> throw new java.util.concurrent.CancellationException("application secret");
                case "object" -> new Object();
                case "oversize" -> new byte[1024 * 1024 + 1];
                default -> Response.of(200, "ok").withHeader("X-Invalid", "\u2603");
            });
            try (var wire = new Wire(fixture.listen())) {
                var response = wire.get("/");
                assertThat(response.status()).isEqualTo(500);
                assertThat(response.text()).isEqualTo("Internal Server Error");
                assertThat(wire.socket.getInputStream().read()).isEqualTo(-1);
            }
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "GET / HTTP/1.1\r\n\r\n",
            "GET / HTTP/1.1\r\nHost: a\r\nHost: b\r\n\r\n",
            "GET / HTTP/1.1\r\nHost: a:99999\r\n\r\n",
            "GET / HTTP/1.1\r\nHost: a/path\r\n\r\n",
            "GET http://localhost/ HTTP/1.1\r\nHost: localhost\r\n\r\n",
            "GET /bad%zz HTTP/1.1\r\nHost: localhost\r\n\r\n",
            "POST / HTTP/1.1\r\nHost: a\r\nContent-Length: 0\r\nContent-Length: 1\r\n\r\n",
            "POST / HTTP/1.1\r\nHost: a\r\nTransfer-Encoding: chunked\r\nContent-Length: 1\r\n\r\n"
    })
    void rejectsMalformedRequestsWithoutCallingHandlers(String request) throws Exception {
        try (var fixture = new Fixture()) {
            fixture.app.get("/", ctx -> { throw new AssertionError("must not execute"); });
            try (var wire = new Wire(fixture.listen())) {
                wire.write(request);
                assertThat(wire.read(false).status()).isEqualTo(400);
                assertThat(wire.socket.getInputStream().read()).isEqualTo(-1);
            }
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"Content-Length: 1", "Transfer-Encoding: chunked", "Upgrade: websocket"})
    void rejectsUnsupportedBodiesAndUpgrades(String header) throws Exception {
        try (var fixture = new Fixture(); var wire = new Wire(fixture.listen())) {
            wire.write("POST / HTTP/1.1\r\nHost: localhost\r\n" + header + "\r\n\r\n");
            assertThat(wire.read(false).status()).isEqualTo(501);
        }
    }

    @Test void rejectsExpectationAndOldProtocolAndExcessiveHeaders() throws Exception {
        try (var fixture = new Fixture()) {
            var server = fixture.listen();
            for (var entry : Map.of(
                    "GET / HTTP/1.0\r\n\r\n", 505,
                    "CONNECT localhost:443 HTTP/1.1\r\nHost: localhost\r\n\r\n", 501,
                    "POST / HTTP/1.1\r\nHost: a\r\nExpect: 100-continue\r\n\r\n", 417,
                    "GET / HTTP/1.1\r\nHost: a\r\nX-Large: " + "x".repeat(9000) + "\r\n\r\n", 400
            ).entrySet()) {
                try (var wire = new Wire(server)) {
                    wire.write(entry.getKey());
                    assertThat(wire.read(false).status()).isEqualTo(entry.getValue());
                }
            }
        }
    }

    @Test void serializesPipelinesWithoutBlockingOtherConnections() throws Exception {
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var second = new AtomicBoolean();
        try (var fixture = new Fixture()) {
            fixture.app.get("/first", ctx -> {
                assertThat(Thread.currentThread().isVirtual()).isTrue();
                entered.countDown();
                assertThat(release.await(5, TimeUnit.SECONDS)).isTrue();
                return "first";
            });
            fixture.app.get("/second", ctx -> { second.set(true); return "second"; });
            fixture.app.get("/other", ctx -> "other");
            var server = fixture.listen();
            try (var pipeline = new Wire(server); var other = new Wire(server)) {
                pipeline.write("GET /first HTTP/1.1\r\nHost: a\r\n\r\nGET /second HTTP/1.1\r\nHost: a\r\n\r\n");
                assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
                assertThat(other.get("/other").text()).isEqualTo("other");
                assertThat(second).isFalse();
                release.countDown();
                assertThat(pipeline.read(false).text()).isEqualTo("first");
                assertThat(pipeline.read(false).text()).isEqualTo("second");
            } finally { release.countDown(); }
        }
    }

    @Test void closesAllListenersAndInterruptsHandlers() throws Exception {
        var entered = new CountDownLatch(1);
        var interrupted = new CountDownLatch(1);
        try (var fixture = new Fixture()) {
            fixture.app.get("/", ctx -> {
                entered.countDown();
                try { new CountDownLatch(1).await(); }
                catch (InterruptedException expected) { interrupted.countDown(); Thread.currentThread().interrupt(); }
                return "done";
            });
            var first = fixture.listen();
            var second = fixture.listen();
            first.close();
            first.termination().toCompletableFuture().get(5, TimeUnit.SECONDS);
            assertThat(fixture.app.state()).isEqualTo(Application.State.RUNNING);
            assertThat(second.isOpen()).isTrue();
            try (var wire = new Wire(second)) {
                wire.write("GET / HTTP/1.1\r\nHost: a\r\n\r\n");
                assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
                fixture.app.close();
                assertThat(interrupted.await(5, TimeUnit.SECONDS)).isTrue();
                second.termination().toCompletableFuture().get(5, TimeUnit.SECONDS);
                assertThat(second.isOpen()).isFalse();
            }
            assertThatThrownBy(() -> fixture.app.listen(0)).isInstanceOf(IllegalStateException.class);
        }
    }

    @Test void clientDisconnectInterruptsRunningHandler() throws Exception {
        var entered = new CountDownLatch(1);
        var interrupted = new CountDownLatch(1);
        try (var fixture = new Fixture()) {
            fixture.app.requestTimeout(java.time.Duration.ofHours(1));
            fixture.app.get("/", ctx -> {
                entered.countDown();
                try { new CountDownLatch(1).await(); }
                catch (InterruptedException expected) { interrupted.countDown(); Thread.currentThread().interrupt(); }
                return "abandoned";
            });
            var server = fixture.listen();
            var wire = new Wire(server);
            wire.write("GET / HTTP/1.1\r\nHost: a\r\n\r\n");
            assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
            wire.close();
            assertThat(interrupted.await(5, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test void bindFailureCanBeRetriedAndHandlerCanCloseApplication() throws Exception {
        try (var fixture = new Fixture(); var occupied = new java.net.ServerSocket(0)) {
            fixture.app.get("/", ctx -> { fixture.app.close(); return "closed"; });
            assertThatThrownBy(() -> fixture.app.listen(occupied.getLocalPort())).isInstanceOf(IOException.class);
            assertThat(fixture.app.state()).isEqualTo(Application.State.RUNNING);
            var server = fixture.listen();
            try (var wire = new Wire(server)) {
                wire.write("GET / HTTP/1.1\r\nHost: a\r\n\r\n");
                server.termination().toCompletableFuture().get(5, TimeUnit.SECONDS);
                assertThat(fixture.app.state()).isEqualTo(Application.State.CLOSED);
            }
        }
    }

    @Test void boundsConnectionsAndDoesNotExposeTerminationOwnership() throws Exception {
        try (var fixture = new Fixture()) {
            fixture.app.get("/", ctx -> "ok");
            var server = fixture.listen();
            server.termination().toCompletableFuture().complete(null);
            assertThat(server.termination().toCompletableFuture()).isNotDone();
            var wires = new java.util.ArrayList<Wire>();
            try {
                for (int i = 0; i < 128; i++) {
                    var wire = new Wire(server);
                    wires.add(wire);
                    assertThat(wire.get("/").status()).isEqualTo(200);
                }
                try (var overflow = new Wire(server)) {
                    assertThat(overflow.socket.getInputStream().read()).isEqualTo(-1);
                }
                assertThat(wires.getFirst().get("/").status()).isEqualTo(200);
            } finally {
                for (var wire : wires) { wire.close(); }
            }
        }
    }
    @Test void assignsRequestIdsAndSharesExecutionMetadataWithHandler() throws Exception {
        try (var fixture = new Fixture()) {
            fixture.app.requestTimeout(java.time.Duration.ofSeconds(5));
            fixture.app.get("/", ctx -> {
                assertThat(ctx.execution().remainingTime()).isPositive().isLessThanOrEqualTo(java.time.Duration.ofSeconds(5));
                return Response.of(200, ctx.execution().requestId()).withHeader("X-Request-ID", "application-spoof");
            });
            try (var wire = new Wire(fixture.listen())) {
                wire.write("GET / HTTP/1.1\r\nHost: a\r\nX-Request-ID: client-spoof\r\n\r\n");
                var first = wire.read(false);
                assertThat(first.headers()).containsEntry("X-Request-ID", first.text());
                assertThat(java.util.UUID.fromString(first.text())).isNotNull();
                assertThat(wire.get("/").text()).isNotEqualTo(first.text());
            }
        }
    }

    @Test void deadlineReturns504AndInterruptsHandlerWithoutASecondResponse() throws Exception {
        var entered = new CountDownLatch(1);
        var interrupted = new CountDownLatch(1);
        var returned = new CountDownLatch(1);
        try (var fixture = new Fixture()) {
            fixture.app.requestTimeout(java.time.Duration.ofSeconds(1));
            fixture.app.get("/slow", ctx -> {
                entered.countDown();
                try { new CountDownLatch(1).await(); }
                catch (InterruptedException expected) { interrupted.countDown(); }
                returned.countDown();
                return "late response";
            });
            fixture.app.get("/fast", ctx -> "still serving");
            var server = fixture.listen();
            try (var wire = new Wire(server)) {
                wire.write("GET /slow HTTP/1.1\r\nHost: a\r\n\r\n");
                assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
                var response = wire.read(false);
                assertThat(response.status()).isEqualTo(504);
                assertThat(response.text()).isEqualTo("Gateway Timeout");
                assertThat(response.headers()).containsKey("X-Request-ID");
                assertThat(interrupted.await(5, TimeUnit.SECONDS)).isTrue();
                assertThat(returned.await(5, TimeUnit.SECONDS)).isTrue();
                assertThat(wire.socket.getInputStream().read()).isEqualTo(-1);
            }
            try (var other = new Wire(server)) { assertThat(other.get("/fast").text()).isEqualTo("still serving"); }
        }
    }

    @Test void timeoutClosesPipelineWithoutExecutingLaterRequests() throws Exception {
        var entered = new CountDownLatch(1);
        var later = new AtomicBoolean();
        try (var fixture = new Fixture()) {
            fixture.app.requestTimeout(java.time.Duration.ofSeconds(1));
            fixture.app.get("/slow", ctx -> { entered.countDown(); new CountDownLatch(1).await(); return null; });
            fixture.app.get("/later", ctx -> { later.set(true); return "later"; });
            try (var wire = new Wire(fixture.listen())) {
                wire.write("GET /slow HTTP/1.1\r\nHost: a\r\n\r\nGET /later HTTP/1.1\r\nHost: a\r\n\r\n");
                assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
                assertThat(wire.read(false).status()).isEqualTo(504);
                assertThat(wire.socket.getInputStream().read()).isEqualTo(-1);
                assertThat(later).isFalse();
            }
        }
    }

    @Test void blockingMoreThanFourHandlersDoesNotQueueBehindPlatformWorkers() throws Exception {
        var entered = new CountDownLatch(5);
        var release = new CountDownLatch(1);
        try (var fixture = new Fixture()) {
            fixture.app.get("/", ctx -> {
                assertThat(Thread.currentThread().isVirtual()).isTrue();
                entered.countDown();
                assertThat(release.await(5, TimeUnit.SECONDS)).isTrue();
                return "ok";
            });
            var server = fixture.listen();
            var wires = new java.util.ArrayList<Wire>();
            try {
                for (int i = 0; i < 5; i++) {
                    var wire = new Wire(server);
                    wires.add(wire);
                    wire.write("GET / HTTP/1.1\r\nHost: a\r\n\r\n");
                }
                assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
                release.countDown();
                for (var wire : wires) { assertThat(wire.read(false).text()).isEqualTo("ok"); }
            } finally {
                release.countDown();
                for (var wire : wires) { wire.close(); }
            }
        }
    }
    @Test void routeQueueTimeoutUsesTemplateIdentityAndLeavesOtherRoutesAvailable() throws Exception {
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var calls = new java.util.concurrent.atomic.AtomicInteger();
        try (var fixture = new Fixture()) {
            fixture.app.admissionPolicy(new io.axiom.execution.AdmissionPolicy(2, 4, java.time.Duration.ofSeconds(5)));
            var limited = fixture.app.get("/limited/:id", ctx -> {
                calls.incrementAndGet();
                entered.countDown();
                assertThat(release.await(5, TimeUnit.SECONDS)).isTrue();
                return ctx.path("id");
            });
            fixture.app.admissionPolicy(limited,
                    new io.axiom.execution.AdmissionPolicy(1, 1, java.time.Duration.ofSeconds(1)));
            fixture.app.get("/other", ctx -> "available");
            var server = fixture.listen();
            try (var first = new Wire(server); var second = new Wire(server); var other = new Wire(server)) {
                first.write("GET /limited/a HTTP/1.1\r\nHost: a\r\n\r\n");
                assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
                var timeout = second.get("/limited/b");
                assertThat(timeout.status()).isEqualTo(503);
                assertThat(timeout.headers()).containsKey("X-Request-ID");
                assertThat(second.socket.getInputStream().read()).isEqualTo(-1);
                assertThat(calls).hasValue(1);
                assertThat(server.admission().queueTimeouts()).isEqualTo(1);
                assertThat(server.admission().queued()).isZero();
                assertThat(other.get("/other").text()).isEqualTo("available");
                release.countDown();
                assertThat(first.read(false).text()).isEqualTo("a");
            } finally { release.countDown(); }
        }
    }

    @Test void aggregateLimitAppliesAcrossDifferentRoutes() throws Exception {
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        try (var fixture = new Fixture()) {
            fixture.app.admissionPolicy(io.axiom.execution.AdmissionPolicy.reject(1));
            fixture.app.get("/busy", ctx -> {
                entered.countDown();
                assertThat(release.await(5, TimeUnit.SECONDS)).isTrue();
                return "done";
            });
            fixture.app.get("/other", ctx -> { throw new AssertionError("must be rejected before invocation"); });
            var server = fixture.listen();
            try (var first = new Wire(server); var other = new Wire(server)) {
                first.write("GET /busy HTTP/1.1\r\nHost: a\r\n\r\n");
                assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
                assertThat(other.get("/other").status()).isEqualTo(503);
                assertThat(server.admission().active()).isEqualTo(1);
                assertThat(server.admission().rejected()).isEqualTo(1);
                release.countDown();
                assertThat(first.read(false).text()).isEqualTo("done");
            } finally { release.countDown(); }
        }
    }
    private static final class Fixture implements AutoCloseable {
        final Application app = Axiom.create();
        final java.util.List<Server> servers = new java.util.ArrayList<>();
        Server listen() throws IOException { var server = app.listen(0); servers.add(server); return server; }
        @Override public void close() {
            app.close();
            for (var server : servers) { server.termination().toCompletableFuture().orTimeout(10, TimeUnit.SECONDS).join(); }
        }
    }

    private static final class Wire implements AutoCloseable {
        final Socket socket = new Socket();
        Wire(Server server) throws IOException {
            socket.connect(server.localAddress(), 5000);
            socket.setSoTimeout(5000);
        }
        void write(String text) throws IOException {
            socket.getOutputStream().write(text.getBytes(StandardCharsets.US_ASCII));
            socket.getOutputStream().flush();
        }
        Reply get(String path) throws IOException {
            write("GET " + path + " HTTP/1.1\r\nHost: localhost\r\n\r\n");
            return read(false);
        }
        Reply read(boolean head) throws IOException {
            String status = line();
            if (!status.startsWith("HTTP/1.1 ")) { throw new IOException("Unexpected status: " + status); }
            int code = Integer.parseInt(status.split(" ")[1]);
            var headers = new TreeMap<String, String>(String.CASE_INSENSITIVE_ORDER);
            for (String line; !(line = line()).isEmpty();) {
                int colon = line.indexOf(':');
                headers.put(line.substring(0, colon), line.substring(colon + 1).trim());
            }
            int length = head ? 0 : Integer.parseInt(headers.getOrDefault("Content-Length", "0"));
            var bytes = socket.getInputStream().readNBytes(length);
            if (bytes.length != length) { throw new IOException("Truncated body"); }
            return new Reply(code, headers, bytes);
        }
        String line() throws IOException {
            var bytes = new ByteArrayOutputStream();
            for (int value; (value = socket.getInputStream().read()) != -1;) {
                if (value == '\n') { return bytes.toString(StandardCharsets.US_ASCII).replace("\r", ""); }
                bytes.write(value);
                if (bytes.size() > 16384) { throw new IOException("Unbounded line"); }
            }
            throw new IOException("Unexpected EOF");
        }
        @Override public void close() throws IOException { socket.close(); }
    }
    private record Reply(int status, Map<String, String> headers, byte[] body) {
        String text() { return new String(body, StandardCharsets.UTF_8); }
    }
}
