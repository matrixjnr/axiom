package com.jsgalactic.axiom.test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.jsgalactic.axiom.Axiom;
import com.jsgalactic.axiom.execution.AdmissionPolicy;
import com.jsgalactic.axiom.http.Request;
import com.jsgalactic.axiom.http.Response;
import com.jsgalactic.axiom.http.ServerSentEvent;
import com.jsgalactic.axiom.http.StreamAbortedException;
import com.jsgalactic.axiom.http.StreamAbortedException.Reason;
import com.jsgalactic.axiom.observability.Metrics;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/** Reading streamed responses through the test client: ordering, backpressure and failure, without sleeping. */
class TestClientStreamTest {
    /** Tells a test, without polling, that a request gave its admission slot back. */
    private static final class Releases implements Metrics {
        final Semaphore released = new Semaphore(0);
        @Override public Counter counter(String name, String... tags) {
            return new Counter() {
                @Override public void increment() { }
                @Override public void add(long amount) { }
            };
        }
        @Override public Gauge gauge(String name, String... tags) {
            return delta -> { if (name.equals("axiom.admission.active") && delta < 0) { released.release(); } };
        }
        @Override public Timer timer(String name, String... tags) { return nanos -> { }; }
        void awaitRelease() throws InterruptedException {
            assertThat(released.tryAcquire(30, TimeUnit.SECONDS)).as("admission slot released").isTrue();
        }
    }

    private static Request get(String target) { return new Request("GET", target); }

    @Test void chunksArriveInOrderEachWriteAsOneChunk() throws Exception {
        var app = Axiom.create();
        app.get("/feed", ctx -> Response.stream(200, "text/plain", out -> {
            out.write("one");
            out.write("two");
            out.write(new byte[0]);
            out.write("three");
        }).withHeader("X-Feed", "1"));
        try (var client = TestClient.start(app); var stream = client.stream(get("/feed"))) {
            assertThat(stream.status()).isEqualTo(200);
            assertThat(stream.headers()).containsEntry("content-type", "text/plain").containsEntry("X-Feed", "1");
            assertThat(stream.nextText()).contains("one");
            assertThat(stream.nextText()).contains("two");
            assertThat(stream.nextText()).contains("three");
            assertThat(stream.next()).isEmpty();
            assertThat(stream.next()).isEmpty();
            stream.completion().get(30, TimeUnit.SECONDS);
        }
    }

    @Test void theHandlerCannotRunMoreThanOneWriteAheadOfTheReader() throws Exception {
        var log = Collections.synchronizedList(new ArrayList<String>());
        var app = Axiom.create();
        app.get("/feed", ctx -> Response.stream(200, "text/plain", out -> {
            for (int i = 1; i <= 4; i++) {
                out.write("chunk " + i);
                log.add("wrote " + i);
            }
        }));
        try (var client = TestClient.start(app); var stream = client.stream(get("/feed"))) {
            for (int i = 1; i <= 4; i++) {
                log.add("taking " + i);
                assertThat(stream.nextText()).contains("chunk " + i);
            }
            assertThat(stream.next()).isEmpty();
            stream.completion().get(30, TimeUnit.SECONDS);
            // Write i+1 can only return after chunk i was taken, however the threads are scheduled.
            for (int i = 1; i < 4; i++) {
                assertThat(log.indexOf("taking " + i)).isLessThan(log.indexOf("wrote " + (i + 1)));
            }
        }
    }

    @Test void aReaderThatReadsNothingHoldsTheHandlerAtItsSecondWrite() throws Exception {
        var secondWriteStarted = new CountDownLatch(1);
        var secondWriteReturned = new CountDownLatch(1);
        var app = Axiom.create();
        app.get("/feed", ctx -> Response.stream(200, "text/plain", out -> {
            out.write("a");
            secondWriteStarted.countDown();
            out.write("b");
            secondWriteReturned.countDown();
        }));
        try (var client = TestClient.start(app); var stream = client.stream(get("/feed"))) {
            assertThat(secondWriteStarted.await(30, TimeUnit.SECONDS)).isTrue();
            // Reading "a" is what lets the second write return.
            assertThat(stream.nextText()).contains("a");
            assertThat(secondWriteReturned.await(30, TimeUnit.SECONDS)).isTrue();
            assertThat(stream.nextText()).contains("b");
        }
    }

    @Test void closingTheResponsePlaysADisconnectAndFreesTheSlot() throws Exception {
        var reason = new CompletableFuture<Reason>();
        var releases = new Releases();
        var app = Axiom.create().metrics(releases).admissionPolicy(AdmissionPolicy.reject(1));
        app.get("/endless", ctx -> Response.stream(200, "text/plain", out -> {
            try { for (;;) { out.write("tick"); } }
            catch (StreamAbortedException aborted) { reason.complete(aborted.reason()); throw aborted; }
        }));
        app.get("/other", ctx -> "other");
        try (var client = TestClient.start(app)) {
            var stream = client.stream(get("/endless"));
            assertThat(stream.nextText()).contains("tick");
            // The stream holds the only slot while it is open.
            assertThat(client.get("/other").status()).isEqualTo(503);
            stream.close();
            assertThat(reason.get(30, TimeUnit.SECONDS)).isEqualTo(Reason.CLIENT_DISCONNECTED);
            releases.awaitRelease();
            assertThat(client.get("/other").body()).isEqualTo("other");
            assertThat(stream.next()).isEmpty();
            stream.close(); // Idempotent.
        }
    }

    @Test void closingInterruptsAHandlerThatIsNotWriting() throws Exception {
        var interrupted = new CompletableFuture<Boolean>();
        var blocked = new CountDownLatch(1);
        var app = Axiom.create();
        app.get("/quiet", ctx -> Response.stream(200, "text/plain", out -> {
            out.write("first");
            blocked.countDown();
            try { new CountDownLatch(1).await(); }
            catch (InterruptedException stopped) { interrupted.complete(true); throw stopped; }
        }));
        try (var client = TestClient.start(app)) {
            var stream = client.stream(get("/quiet"));
            assertThat(blocked.await(30, TimeUnit.SECONDS)).isTrue();
            stream.close();
            assertThat(interrupted.get(30, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test void aBodyThatFailsAfterTheHeadEndsTheStreamAndReportsTheFailure() throws Exception {
        var app = Axiom.create();
        app.get("/broken", ctx -> Response.stream(200, "text/plain", out -> {
            out.write("partial");
            throw new IllegalStateException("handler bug");
        }));
        try (var client = TestClient.start(app); var stream = client.stream(get("/broken"))) {
            assertThat(stream.status()).isEqualTo(200);
            assertThat(stream.nextText()).contains("partial");
            assertThat(stream.next()).isEmpty();
            assertThatThrownBy(() -> stream.completion().get(30, TimeUnit.SECONDS))
                    .isInstanceOf(ExecutionException.class).hasRootCauseInstanceOf(IllegalStateException.class)
                    .hasRootCauseMessage("handler bug");
        }
    }

    @Test void aFailureBeforeTheHeadIsThrownLikeForExecute() {
        var app = Axiom.create();
        app.get("/boom", ctx -> { throw new IllegalStateException("before the head"); });
        try (var client = TestClient.start(app)) {
            assertThatThrownBy(() -> client.stream(get("/boom"))).isInstanceOf(IllegalStateException.class)
                    .hasMessage("before the head");
        }
    }

    @Test void theByteCapEndsTheStreamAndASwallowedAbortStillCountsAsAFailure() throws Exception {
        var refusal = new CompletableFuture<Reason>();
        var app = Axiom.create();
        app.get("/capped", ctx -> Response.stream(200, "text/plain", 10, out -> {
            out.write("12345678");
            try { out.write("abc"); } catch (StreamAbortedException refused) { refusal.complete(refused.reason()); }
        }));
        try (var client = TestClient.start(app); var stream = client.stream(get("/capped"))) {
            assertThat(stream.nextText()).contains("12345678");
            assertThat(stream.next()).isEmpty();
            assertThat(refusal.get(30, TimeUnit.SECONDS)).isEqualTo(Reason.LIMIT_EXCEEDED);
            assertThatThrownBy(() -> stream.completion().get(30, TimeUnit.SECONDS)).hasRootCauseInstanceOf(StreamAbortedException.class);
        }
    }

    @Test void theRequestDeadlineEndsAStreamWhoseReaderStoppedReading() throws Exception {
        var reason = new CompletableFuture<Reason>();
        var app = Axiom.create().requestTimeout(Duration.ofMillis(200));
        app.get("/feed", ctx -> Response.stream(200, "text/plain", out -> {
            try { for (;;) { out.write("x"); } }
            catch (StreamAbortedException aborted) { reason.complete(aborted.reason()); throw aborted; }
        }));
        try (var client = TestClient.start(app); var stream = client.stream(get("/feed"))) {
            // Nothing is read: the hand-off is bounded by the deadline instead of blocking forever.
            assertThat(reason.get(30, TimeUnit.SECONDS)).isEqualTo(Reason.TIMEOUT);
            assertThatThrownBy(() -> stream.completion().get(30, TimeUnit.SECONDS))
                    .hasRootCauseInstanceOf(TimeoutException.class);
        }
    }

    @Test void closingTheClientAbortsOpenStreamsAsShutdown() throws Exception {
        var reason = new CompletableFuture<Reason>();
        var queued = new CountDownLatch(1);
        var app = Axiom.create();
        app.get("/endless", ctx -> Response.stream(200, "text/plain", out -> {
            try {
                out.write("tick");
                out.write("tick"); // Returns once the first was taken: it is now the queued chunk.
                queued.countDown();
                for (;;) { out.write("tick"); }
            }
            catch (StreamAbortedException aborted) { reason.complete(aborted.reason()); throw aborted; }
        }));
        var client = TestClient.start(app);
        var stream = client.stream(get("/endless"));
        assertThat(stream.nextText()).contains("tick");
        assertThat(queued.await(30, TimeUnit.SECONDS)).isTrue();
        client.close();
        assertThat(reason.get(30, TimeUnit.SECONDS)).isEqualTo(Reason.SHUTDOWN);
        // The request was cancelled by the shutdown.
        assertThatThrownBy(() -> stream.completion().get(30, TimeUnit.SECONDS)).isInstanceOf(CancellationException.class);
        // The chunk that was queued stays readable, and then the stream ends instead of hanging.
        assertThat(stream.readAllText()).isEqualTo("tick");
    }

    @Test void headGetsNoBodyAndNeverRunsTheStream() throws Exception {
        var runs = new AtomicInteger();
        var app = Axiom.create();
        app.get("/feed", ctx -> Response.stream(200, "text/plain", out -> { runs.incrementAndGet(); out.write("x"); }));
        try (var client = TestClient.start(app)) {
            var head = client.execute(new Request("HEAD", "/feed"));
            assertThat(head.status()).isEqualTo(200);
            assertThat(head.body()).isNull();
            assertThat(head.headers()).doesNotContainKey("Content-Length");
            try (var stream = client.stream(new Request("HEAD", "/feed"))) {
                assertThat(stream.status()).isEqualTo(200);
                assertThat(stream.next()).isEmpty();
            }
            assertThat(runs).hasValue(0);
        }
    }

    @Test void executeCollectsAFiniteStreamIntoOneResponse() throws Exception {
        var app = Axiom.create();
        app.get("/feed", ctx -> Response.stream(201, "text/csv", out -> { out.write("a,b\n"); out.write("1,2\n"); })
                .withHeader("X-Feed", "1"));
        try (var client = TestClient.start(app)) {
            var response = client.get("/feed");
            assertThat(response.status()).isEqualTo(201);
            assertThat(response.headers()).containsEntry("Content-Type", "text/csv").containsEntry("X-Feed", "1");
            assertThat(response.body()).isEqualTo("a,b\n1,2\n".getBytes(StandardCharsets.UTF_8));
        }
    }

    @Test void executeAppliesTheCapAndPropagatesFailures() {
        var app = Axiom.create();
        app.get("/capped", ctx -> Response.stream(200, "text/plain", 3, out -> out.write("abcd")));
        app.get("/broken", ctx -> Response.stream(200, "text/plain", out -> { throw new IllegalStateException("x"); }));
        try (var client = TestClient.start(app)) {
            assertThatThrownBy(() -> client.get("/capped")).isInstanceOfSatisfying(StreamAbortedException.class,
                    aborted -> assertThat(aborted.reason()).isEqualTo(Reason.LIMIT_EXCEEDED));
            assertThatThrownBy(() -> client.get("/broken")).isInstanceOf(IllegalStateException.class);
        }
    }

    @Test void aResponseThatIsNotStreamedComesAsOneChunk() throws Exception {
        var app = Axiom.create();
        app.get("/plain", ctx -> "plain");
        try (var client = TestClient.start(app)) {
            try (var stream = client.stream(get("/plain"))) {
                assertThat(stream.status()).isEqualTo(200);
                assertThat(stream.readAllText()).isEqualTo("plain");
                assertThat(stream.next()).isEmpty();
                stream.completion().get(30, TimeUnit.SECONDS);
            }
            try (var missing = client.stream(get("/missing"))) {
                assertThat(missing.status()).isEqualTo(404);
                assertThat(missing.headers()).containsEntry("Content-Type", "application/problem+json");
                assertThat(missing.readAllText()).contains("404");
            }
        }
    }

    @Test void admissionFailuresBeforeTheHeadAreProblemResponses() throws Exception {
        var held = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var app = Axiom.create().admissionPolicy(AdmissionPolicy.reject(1));
        app.get("/hold", ctx -> { held.countDown(); release.await(); return "done"; });
        try (var client = TestClient.start(app)) {
            var first = client.submit(get("/hold"));
            assertThat(held.await(30, TimeUnit.SECONDS)).isTrue();
            try (var refused = client.stream(get("/hold"))) {
                assertThat(refused.status()).isEqualTo(503);
            }
            release.countDown();
            assertThat(first.get(30, TimeUnit.SECONDS).status()).isEqualTo(200);
        }
    }

    @Test void serverSentEventsAreReadFrameByFrame() throws Exception {
        var app = Axiom.create();
        app.get("/events", ctx -> Response.sse(events -> {
            events.send(ServerSentEvent.named("tick", "1").withId("1"));
            events.keepAlive();
            events.send("line one\r\nevent: forged");
        }));
        try (var client = TestClient.start(app); var stream = client.stream(get("/events"))) {
            assertThat(stream.status()).isEqualTo(200);
            assertThat(stream.headers()).containsEntry("Content-Type", "text/event-stream")
                    .containsEntry("Cache-Control", "no-store, no-transform").containsEntry("X-Accel-Buffering", "no");
            var frames = new ArrayList<String>();
            for (var frame = stream.nextText(); frame.isPresent(); frame = stream.nextText()) { frames.add(frame.get()); }
            assertThat(frames).containsExactly("event: tick\nid: 1\ndata: 1\n\n", ": keep-alive\n\n",
                    "data: line one\ndata: event: forged\n\n");
        }
    }
}
