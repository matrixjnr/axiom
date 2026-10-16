import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.jsgalactic.axiom.execution.ExecutionContext;
import com.jsgalactic.axiom.test.TestClient;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/** Real HTTP/1.1 cancellation, with endpoint state coordinated before interruption. */
@Tag("integration")
@Timeout(30)
class DownstreamHttpLiveTest {
    private static final Duration BUDGET = Duration.ofSeconds(10);
    private static final String PARTIAL_BODY = "HTTP/1.1 200 OK\r\nContent-Length: 100\r\n\r\npartial";

    private static HttpClient client() {
        return HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1)
                .followRedirects(HttpClient.Redirect.NEVER).connectTimeout(Duration.ofSeconds(2)).build();
    }

    @Test void readsUtf8AndLeavesBorrowedClientUsable() throws Exception {
        try (var first = new Endpoint(response(200, "café"), false);
                var second = new Endpoint(response(200, "second"), false); var http = client()) {
            assertThat(DownstreamHttp.fetch(http, first.uri(), BUDGET)).isEqualTo("café");
            assertThat(DownstreamHttp.fetch(http, second.uri(), BUDGET)).isEqualTo("second");
            first.finished.get(5, TimeUnit.SECONDS);
            second.finished.get(5, TimeUnit.SECONDS);
        }
    }

    @Test void rejectsUnexpectedStatusAndOversizedBodies() throws Exception {
        try (var error = new Endpoint(response(503, "unavailable"), false);
                var large = new Endpoint(response(200, "x".repeat(DownstreamHttp.MAX_BODY_BYTES + 1)), false);
                var http = client()) {
            assertThatThrownBy(() -> DownstreamHttp.fetch(http, error.uri(), BUDGET))
                    .isInstanceOf(IOException.class).hasMessageContaining("503");
            assertThatThrownBy(() -> DownstreamHttp.fetch(http, large.uri(), BUDGET))
                    .isInstanceOf(IOException.class).hasMessageContaining("64 KiB");
        }
    }

    @Test void acceptsExactlyTheBodyLimit() throws Exception {
        var text = "x".repeat(DownstreamHttp.MAX_BODY_BYTES);
        try (var endpoint = new Endpoint(response(200, text), false); var http = client()) {
            assertThat(DownstreamHttp.fetch(http, endpoint.uri(), BUDGET)).isEqualTo(text);
        }
    }

    @Test void encodesResultsAfterBothActualDownstreamCalls() throws Exception {
        try (var profile = new Endpoint(response(200, "Alice"), false);
                var recommendations = new Endpoint(response(200, "Book"), false); var http = client();
                var app = TestClient.start(FanOutApi.create(
                        (id, budget) -> DownstreamHttp.fetch(http, profile.uri(), budget),
                        (id, budget) -> DownstreamHttp.fetch(http, recommendations.uri(), budget)))) {
            var response = app.get("/summary/42");
            assertThat(response.status()).isEqualTo(200);
            assertThat(new String((byte[]) response.body(), StandardCharsets.UTF_8))
                    .isEqualTo("{\"profile\":\"Alice\",\"recommendations\":\"Book\"}");
        }
    }

    @Test void interruptionCancelsCallBeforeResponseHeaders() throws Exception {
        assertInterruptCancels("");
    }

    @Test void interruptionCancelsCallDuringResponseBody() throws Exception {
        assertInterruptCancels(PARTIAL_BODY);
    }

    private static void assertInterruptCancels(String response) throws Exception {
        try (var endpoint = new Endpoint(response); var http = client()) {
            var failure = new AtomicReference<Throwable>();
            var parent = Thread.ofVirtual().start(() -> {
                try { DownstreamHttp.fetch(http, endpoint.uri(), BUDGET); }
                catch (Throwable thrown) { failure.set(thrown); }
            });
            try {
                assertThat(endpoint.sent.await(5, TimeUnit.SECONDS)).isTrue();
                parent.interrupt();
                parent.join(5000);
                assertThat(parent.isAlive()).isFalse();
                assertThat(failure.get()).isInstanceOf(InterruptedException.class);
                assertThat(endpoint.finished.get(5, TimeUnit.SECONDS)).isTrue();
            } finally {
                parent.interrupt();
                parent.join(5000);
            }
        }
    }

    @Test void boundsWaitForAStalledBodyAfterHeaders() throws Exception {
        try (var endpoint = new Endpoint(PARTIAL_BODY); var http = client()) {
            assertThatThrownBy(() -> DownstreamHttp.fetch(http, endpoint.uri(), Duration.ofSeconds(3)))
                    .isInstanceOfAny(TimeoutException.class, HttpTimeoutException.class);
            assertThat(endpoint.sent.getCount()).isZero();
            assertThat(endpoint.finished.get(5, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test void siblingFailureCancelsActualHttpCallBeforeFanOutReturns() throws Exception {
        try (var endpoint = new Endpoint(PARTIAL_BODY); var http = client()) {
            var failure = new IOException("other lookup failed");
            FanOutApi.Lookup blocked = (id, budget) -> DownstreamHttp.fetch(http, endpoint.uri(), budget);
            FanOutApi.Lookup failing = (id, budget) -> {
                assertThat(endpoint.sent.await(5, TimeUnit.SECONDS)).isTrue();
                throw failure;
            };
            assertThatThrownBy(() -> FanOutApi.fetch("42", ExecutionContext.create(BUDGET), blocked, failing))
                    .isSameAs(failure);
            assertThat(endpoint.finished.get(5, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test void rejectsExpiredBudgetBeforeConnecting() throws Exception {
        try (var endpoint = new Endpoint(""); var http = client()) {
            assertThatThrownBy(() -> DownstreamHttp.fetch(http, endpoint.uri(), Duration.ZERO))
                    .isInstanceOf(TimeoutException.class);
            assertThat(endpoint.sent.getCount()).isEqualTo(1);
        }
    }

    private static String response(int status, String body) {
        return "HTTP/1.1 " + status + " Result\r\nContent-Length: "
                + body.getBytes(StandardCharsets.UTF_8).length + "\r\n\r\n" + body;
    }

    /** One request, then observe peer EOF/reset; resource cleanup also handles failed assertions. */
    private static final class Endpoint implements AutoCloseable {
        private final ServerSocket listener;
        private final AtomicReference<Socket> active = new AtomicReference<>();
        private final CountDownLatch sent = new CountDownLatch(1);
        private final CompletableFuture<Boolean> finished = new CompletableFuture<>();
        private final Thread worker;

        private Endpoint(String response) throws IOException {
            this(response, true);
        }

        private Endpoint(String response, boolean observeDisconnect) throws IOException {
            listener = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"));
            worker = Thread.ofVirtual().start(() -> {
                try (var socket = listener.accept()) {
                    active.set(socket);
                    socket.setSoTimeout(10000);
                    var reader = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.US_ASCII));
                    String line;
                    do {
                        line = reader.readLine();
                        if (line == null) { throw new IOException("Incomplete request"); }
                    } while (!line.isEmpty());
                    socket.getOutputStream().write(response.getBytes(StandardCharsets.UTF_8));
                    socket.getOutputStream().flush();
                    sent.countDown();
                    if (!observeDisconnect) { finished.complete(true); return; }
                    try { finished.complete(reader.read() == -1); }
                    catch (SocketException reset) { finished.complete(true); }
                } catch (Throwable failure) { finished.completeExceptionally(failure); }
            });
        }

        private URI uri() { return URI.create("http://127.0.0.1:" + listener.getLocalPort() + "/lookup"); }

        @Override public void close() throws IOException {
            listener.close();
            var socket = active.get();
            if (socket != null) { socket.close(); }
            try { worker.join(5000); }
            catch (InterruptedException failure) {
                Thread.currentThread().interrupt();
                throw new IOException("Interrupted while closing endpoint", failure);
            }
            assertThat(worker.isAlive()).isFalse();
        }
    }
}
