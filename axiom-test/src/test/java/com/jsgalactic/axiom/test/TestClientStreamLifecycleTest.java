package com.jsgalactic.axiom.test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.jsgalactic.axiom.Axiom;
import com.jsgalactic.axiom.http.BodyWriter;
import com.jsgalactic.axiom.http.Request;
import com.jsgalactic.axiom.http.Response;
import com.jsgalactic.axiom.http.StreamAbortedException;
import com.jsgalactic.axiom.http.StreamAbortedException.Reason;
import com.jsgalactic.axiom.http.StreamOutcome;
import com.jsgalactic.axiom.observability.Metrics;
import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

/**
 * Stream lifetimes, outcome observers, shutdown signals, buffering and stream metrics through the
 * test client. Everything waits for an event (a latch, a future, a read), never for a fixed time.
 */
class TestClientStreamLifecycleTest {
    private static Request get(String target) { return new Request("GET", target); }

    /** Counts what the stream metrics receive. */
    private static final class Recorder implements Metrics {
        final Map<String, AtomicLong> values = new ConcurrentHashMap<>();
        final CountDownLatch backpressure = new CountDownLatch(1);
        private static String key(String name, String... tags) {
            var key = new StringBuilder(name).append('{');
            for (int i = 0; i < tags.length; i += 2) { key.append(i > 0 ? "," : "").append(tags[i]).append('=').append(tags[i + 1]); }
            return key.append('}').toString();
        }
        long value(String name, String... tags) {
            var found = values.get(key(name, tags));
            return found == null ? 0 : found.get();
        }
        @Override public Counter counter(String name, String... tags) {
            var value = values.computeIfAbsent(key(name, tags), k -> new AtomicLong());
            return new Counter() {
                @Override public void increment() {
                    value.incrementAndGet();
                    if (name.equals("axiom.http.stream.backpressure")) { backpressure.countDown(); }
                }
                @Override public void add(long amount) { value.addAndGet(amount); }
            };
        }
        @Override public Gauge gauge(String name, String... tags) {
            return values.computeIfAbsent(key(name, tags), k -> new AtomicLong())::addAndGet;
        }
        @Override public Timer timer(String name, String... tags) { return nanos -> { }; }
    }

    // --- Outcome observers (middleware can see how a stream ended) ---

    @Test void middlewareLearnsHowAStreamEndedAndCanWrapItsBody() throws Exception {
        var outcomes = new CompletableFuture<StreamOutcome>();
        var wrapped = new AtomicLong();
        var app = Axiom.create();
        app.use((ctx, next) -> {
            var response = next.run();
            if (!response.isStreaming()) { return response; }
            return response.mapStream(body -> out -> body.writeTo(new CountingWriter(out, wrapped))).onStreamEnd(outcomes::complete);
        });
        app.get("/feed", ctx -> Response.stream(200, "text/plain", out -> { out.write("one"); out.write("three"); }));
        try (var client = TestClient.start(app)) {
            assertThat(client.get("/feed").body()).isEqualTo("onethree".getBytes());
            var outcome = outcomes.get(30, TimeUnit.SECONDS);
            assertThat(outcome.kind()).isEqualTo(StreamOutcome.Kind.COMPLETED);
            assertThat(outcome.completed()).isTrue();
            assertThat(outcome.bytesWritten()).isEqualTo(8);
            assertThat(outcome.failure()).isNull();
            assertThat(outcome.elapsed()).isGreaterThanOrEqualTo(Duration.ZERO);
            assertThat(wrapped).hasValue(8);
        }
    }

    private static final class CountingWriter implements BodyWriter {
        private final BodyWriter out;
        private final AtomicLong counted;
        CountingWriter(BodyWriter out, AtomicLong counted) { this.out = out; this.counted = counted; }
        @Override public void write(byte[] bytes, int offset, int length) throws IOException {
            out.write(bytes, offset, length);
            counted.addAndGet(length);
        }
        @Override public long bytesWritten() { return out.bytesWritten(); }
    }

    @Test void observersRunInnerFirstAndAFailingObserverDoesNotStopTheOthers() throws Exception {
        var order = Collections.synchronizedList(new ArrayList<String>());
        var done = new CountDownLatch(1);
        var app = Axiom.create();
        app.use((ctx, next) -> next.run().onStreamEnd(outcome -> { order.add("outer"); done.countDown(); }));
        app.use((ctx, next) -> next.run().onStreamEnd(outcome -> { order.add("inner"); throw new IllegalStateException("broken observer"); }));
        app.get("/feed", ctx -> Response.stream(200, "text/plain", out -> out.write("x"))
                .onStreamEnd(outcome -> order.add("handler")));
        try (var client = TestClient.start(app)) {
            assertThat(client.get("/feed").status()).isEqualTo(200);
            assertThat(done.await(30, TimeUnit.SECONDS)).isTrue();
            assertThat(order).containsExactly("handler", "inner", "outer");
        }
    }

    @Test void theOutcomeSaysWhyEachStreamEnded() throws Exception {
        var outcomes = new ConcurrentHashMap<String, CompletableFuture<StreamOutcome>>();
        var app = Axiom.create();
        for (var name : List.of("failed", "capped", "gone")) { outcomes.put(name, new CompletableFuture<>()); }
        app.get("/failed", ctx -> Response.stream(200, "text/plain", out -> { out.write("a"); throw new IllegalStateException("bug"); })
                .onStreamEnd(outcomes.get("failed")::complete));
        app.get("/capped", ctx -> Response.stream(200, "text/plain", 4, out -> out.write("12345"))
                .onStreamEnd(outcomes.get("capped")::complete));
        app.get("/gone", ctx -> Response.stream(200, "text/plain", out -> { for (;;) { out.write("x"); } })
                .onStreamEnd(outcomes.get("gone")::complete));
        try (var client = TestClient.start(app)) {
            assertThatThrownBy(() -> client.get("/failed")).isInstanceOf(IllegalStateException.class);
            var failed = outcomes.get("failed").get(30, TimeUnit.SECONDS);
            assertThat(failed.kind()).isEqualTo(StreamOutcome.Kind.FAILED);
            assertThat(failed.failure()).isInstanceOf(IllegalStateException.class);
            assertThat(failed.bytesWritten()).isEqualTo(1);

            assertThatThrownBy(() -> client.get("/capped")).isInstanceOf(StreamAbortedException.class);
            var capped = outcomes.get("capped").get(30, TimeUnit.SECONDS);
            assertThat(capped.kind()).isEqualTo(StreamOutcome.Kind.LIMIT_EXCEEDED);
            assertThat(capped.completed()).isFalse();
            assertThat(capped.bytesWritten()).isZero();

            {
                var stream = client.stream(get("/gone"));
                assertThat(stream.nextText()).contains("x");
                stream.close();
                var gone = outcomes.get("gone").get(30, TimeUnit.SECONDS);
                assertThat(gone.kind()).isEqualTo(StreamOutcome.Kind.CLIENT_DISCONNECTED);
                assertThat(gone.failure()).isInstanceOf(StreamAbortedException.class);
            }
        }
    }

    @Test void aBodyThatSwallowsAnAbortStillReportsTheAbortNotACompletion() throws Exception {
        var outcome = new CompletableFuture<StreamOutcome>();
        var app = Axiom.create();
        app.get("/swallow", ctx -> Response.stream(200, "text/plain", 2, out -> {
            try { out.write("abc"); } catch (StreamAbortedException ignored) { /* swallowed */ }
        }).onStreamEnd(outcome::complete));
        try (var client = TestClient.start(app)) {
            assertThatThrownBy(() -> client.get("/swallow")).isInstanceOf(StreamAbortedException.class);
            var result = outcome.get(30, TimeUnit.SECONDS);
            assertThat(result.kind()).isEqualTo(StreamOutcome.Kind.LIMIT_EXCEEDED);
            assertThat(result.failure()).isNull();
        }
    }

    @Test void observersAreNotCalledForABodyThatNeverRunsAndStreamOnlyMethodsRejectOtherResponses() throws Exception {
        var calls = new AtomicInteger();
        var app = Axiom.create();
        app.get("/feed", ctx -> Response.stream(200, "text/plain", out -> out.write("x")).onStreamEnd(outcome -> calls.incrementAndGet()));
        try (var client = TestClient.start(app)) {
            assertThat(client.execute(new Request("HEAD", "/feed")).status()).isEqualTo(200);
            assertThat(calls).hasValue(0);
        }
        var plain = Response.of(200, "text");
        assertThatIllegalStateException().isThrownBy(() -> plain.onStreamEnd(outcome -> { }));
        assertThatIllegalStateException().isThrownBy(() -> plain.mapStream(body -> body));
        assertThatIllegalStateException().isThrownBy(() -> plain.withStreamLifetime(Duration.ofSeconds(1)));
        assertThat(plain.streamLifetime()).isNull();
        assertThat(Response.stream(200, "text/plain", out -> { }).streamLifetime()).isNull();
        var observer = plain.streamEndObserver();
        observer.accept(new StreamOutcome(StreamOutcome.Kind.COMPLETED, 0, Duration.ZERO, null));
    }

    // --- Lifetime ---

    @Test void aStreamCanOutliveTheRequestDeadlineWhileOrdinaryRequestsKeepIt() throws Exception {
        var remaining = new CompletableFuture<Duration>();
        var ordinary = new CompletableFuture<Duration>();
        var app = Axiom.create().requestTimeout(Duration.ofSeconds(20));
        app.get("/plain", ctx -> {
            ordinary.complete(ctx.execution().remainingTime());
            return "ok";
        });
        app.get("/sse", ctx -> Response.sse(events -> {
            remaining.complete(ctx.execution().remainingTime());
            events.send("tick");
        }).withStreamLifetime(Duration.ofHours(2)));
        try (var client = TestClient.start(app)) {
            client.get("/plain");
            client.get("/sse");
            assertThat(ordinary.get(30, TimeUnit.SECONDS)).isLessThanOrEqualTo(Duration.ofSeconds(20));
            assertThat(remaining.get(30, TimeUnit.SECONDS)).isGreaterThan(Duration.ofHours(1));
        }
    }

    @Test void aShorterLifetimeNeverCutsAStreamShortAndTheLimitsAreValidated() {
        var stream = Response.stream(200, "text/plain", out -> { });
        assertThat(stream.withStreamLifetime(Duration.ofDays(1)).streamLifetime()).isEqualTo(Duration.ofDays(1));
        assertThat(stream.withStreamLifetime(Duration.ofSeconds(5)).withHeader("X", "y").streamLifetime())
                .isEqualTo(Duration.ofSeconds(5));
        assertThatIllegalArgumentException().isThrownBy(() -> stream.withStreamLifetime(Duration.ZERO));
        assertThatIllegalArgumentException().isThrownBy(() -> stream.withStreamLifetime(Duration.ofSeconds(-1)));
        assertThatIllegalArgumentException().isThrownBy(() -> stream.withStreamLifetime(Duration.ofDays(1).plusNanos(1)));
    }

    @Test void aStreamWithoutALifetimeStillEndsAtTheRequestDeadline() throws Exception {
        var reason = new CompletableFuture<Reason>();
        var app = Axiom.create().requestTimeout(Duration.ofMillis(100));
        app.get("/stuck", ctx -> Response.stream(200, "text/plain", out -> {
            out.write("x");
            try { out.write("y"); } // Blocks: nobody reads, and the deadline passes.
            catch (StreamAbortedException aborted) { reason.complete(aborted.reason()); throw aborted; }
        }));
        try (var client = TestClient.start(app); var stream = client.stream(get("/stuck"))) {
            assertThat(stream.status()).isEqualTo(200);
            assertThat(reason.get(30, TimeUnit.SECONDS)).isEqualTo(Reason.TIMEOUT);
        }
    }

    // --- Shutdown signal ---

    @Test void aBodyCanEndNormallyWhenShutdownBeginsAndAnActionRunsOnce() throws Exception {
        var woken = new AtomicInteger();
        var seenBefore = new CompletableFuture<Boolean>();
        var late = new AtomicInteger();
        var app = Axiom.create();
        app.get("/events", ctx -> Response.sse(events -> {
            var shutdown = new CountDownLatch(1);
            events.onShutdown(() -> { woken.incrementAndGet(); shutdown.countDown(); });
            events.onShutdown(() -> { throw new IllegalStateException("a broken action is ignored"); });
            events.send("hello");
            seenBefore.complete(events.shutdownRequested());
            shutdown.await();
            events.onShutdown(late::incrementAndGet); // Already begun: runs at once, here.
            events.send(com.jsgalactic.axiom.http.ServerSentEvent.named("bye", "closing").withRetry(Duration.ofSeconds(1)));
        }));
        try (var client = TestClient.start(app); var stream = client.stream(get("/events"))) {
            assertThat(stream.nextText().orElseThrow()).contains("hello");
            assertThat(seenBefore.get(30, TimeUnit.SECONDS)).isFalse();
            stream.beginShutdown();
            stream.beginShutdown(); // Idempotent.
            assertThat(stream.nextText().orElseThrow()).contains("event: bye");
            assertThat(stream.next()).isEmpty();
            stream.completion().get(30, TimeUnit.SECONDS);
            assertThat(woken).hasValue(1);
            assertThat(late).hasValue(1);
        }
    }

    @Test void aWriterOutsideAListenerNeverReportsShutdown() throws Exception {
        BodyWriter writer = new BodyWriter() {
            @Override public void write(byte[] bytes, int offset, int length) { }
            @Override public long bytesWritten() { return 0; }
        };
        assertThat(writer.shutdownRequested()).isFalse();
        var ran = new AtomicInteger();
        writer.onShutdown(ran::incrementAndGet);
        assertThat(ran).hasValue(0);
    }

    // --- Buffering ---

    @Test void bufferedStreamsLetTheHandlerRunAheadByTheWaterMarkAndSplitLargeWrites() throws Exception {
        var written = new Semaphore(0);
        var taking = new AtomicInteger();
        var takenWhenWritten = Collections.synchronizedList(new ArrayList<Integer>());
        var app = Axiom.create();
        app.get("/feed", ctx -> Response.stream(200, "text/plain", out -> {
            for (int i = 1; i <= 4; i++) {
                out.write(new byte[60]);
                takenWhenWritten.add(taking.get());
                written.release();
            }
        }));
        try (var client = TestClient.start(app, StreamBuffering.ofBytes(100)); var stream = client.stream(get("/feed"))) {
            // Two writes (120 bytes) fit before the channel reports itself unwritable: no read was needed.
            assertThat(written.tryAcquire(2, 30, TimeUnit.SECONDS)).isTrue();
            assertThat(takenWhenWritten).containsExactly(0, 0);
            for (int i = 0; i < 4; i++) {
                taking.incrementAndGet();
                assertThat(stream.next()).isPresent();
            }
            assertThat(stream.next()).isEmpty();
            // The third write needed the unread bytes below the low water mark (25), that is two reads.
            assertThat(takenWhenWritten.get(2)).isGreaterThanOrEqualTo(2);
            assertThat(takenWhenWritten.get(3)).isGreaterThanOrEqualTo(2);
        }
    }

    @Test void largeWritesArriveAsPiecesOfTheConfiguredSize() throws Exception {
        var app = Axiom.create();
        app.get("/blob", ctx -> Response.stream(200, "application/octet-stream", out -> out.write(new byte[3 * 16 * 1024 + 5])));
        try (var client = TestClient.start(app, StreamBuffering.listener()); var stream = client.stream(get("/blob"))) {
            var sizes = new ArrayList<Integer>();
            for (var chunk = stream.next(); chunk.isPresent(); chunk = stream.next()) { sizes.add(chunk.get().length); }
            assertThat(sizes).containsExactly(16 * 1024, 16 * 1024, 16 * 1024, 5);
        }
        var plain = Axiom.create();
        plain.get("/blob", ctx -> Response.stream(200, "application/octet-stream", out -> out.write(new byte[3 * 16 * 1024 + 5])));
        try (var client = TestClient.start(plain); var stream = client.stream(get("/blob"))) {
            assertThat(stream.next().orElseThrow()).hasSize(3 * 16 * 1024 + 5); // The default never splits.
        }
    }

    @Test void aReaderThatTakesNothingForTheStallBoundLosesTheStream() throws Exception {
        var reason = new CompletableFuture<Reason>();
        var app = Axiom.create();
        app.get("/feed", ctx -> Response.stream(200, "text/plain", out -> {
            try {
                out.write(new byte[10]); // Reaches the high water mark.
                out.write(new byte[10]); // Waits for a reader that never comes.
            } catch (StreamAbortedException aborted) { reason.complete(aborted.reason()); throw aborted; }
        }));
        var buffering = StreamBuffering.ofBytes(10).withStallTimeout(Duration.ofMillis(50));
        try (var client = TestClient.start(app, buffering); var stream = client.stream(get("/feed"))) {
            assertThat(stream.status()).isEqualTo(200);
            assertThat(reason.get(30, TimeUnit.SECONDS)).isEqualTo(Reason.CLIENT_DISCONNECTED);
        }
    }

    @Test void bufferingValidatesItsNumbers() {
        assertThatIllegalArgumentException().isThrownBy(() -> new StreamBuffering(0, 1, 1, null));
        assertThatIllegalArgumentException().isThrownBy(() -> new StreamBuffering(10, 0, 1, null));
        assertThatIllegalArgumentException().isThrownBy(() -> new StreamBuffering(10, 11, 1, null));
        assertThatIllegalArgumentException().isThrownBy(() -> new StreamBuffering(10, 5, 0, null));
        assertThatIllegalArgumentException().isThrownBy(() -> new StreamBuffering(10, 5, 1, Duration.ZERO));
        assertThatThrownBy(() -> TestClient.start(Axiom.create(), null)).isInstanceOf(NullPointerException.class);
        assertThat(StreamBuffering.listener()).isEqualTo(new StreamBuffering(131072, 32768, 16384, Duration.ofSeconds(30)));
        assertThat(StreamBuffering.HAND_OFF.toString()).contains("high=1");
        assertThat(StreamBuffering.ofBytes(1)).isEqualTo(new StreamBuffering(1, 1, 1, null));
    }

    // --- Metrics ---

    @Test void streamMetricsAreRecordedByRouteTemplateAndCollectedStreamsToo() throws Exception {
        var metrics = new Recorder();
        var app = Axiom.create().metrics(metrics);
        app.get("/files/:name", ctx -> Response.stream(200, "text/plain", out -> out.write(ctx.path("name"))));
        app.get("/broken", ctx -> Response.stream(200, "text/plain", out -> { out.write("p"); throw new IllegalStateException(); }));
        try (var client = TestClient.start(app)) {
            client.get("/files/alpha"); // Collected by execute.
            try (var stream = client.stream(get("/files/beta"))) {
                assertThat(stream.readAllText()).isEqualTo("beta");
                stream.completion().get(30, TimeUnit.SECONDS);
            }
            assertThatThrownBy(() -> client.get("/broken")).isInstanceOf(IllegalStateException.class);
        }
        assertThat(metrics.value("axiom.http.streams", "method", "GET", "route", "/files/:name", "outcome", "completed")).isEqualTo(2);
        assertThat(metrics.value("axiom.http.stream.bytes", "method", "GET", "route", "/files/:name")).isEqualTo(9);
        assertThat(metrics.value("axiom.http.streams", "method", "GET", "route", "/broken", "outcome", "failed")).isEqualTo(1);
        assertThat(metrics.value("axiom.http.stream.bytes", "method", "GET", "route", "/broken")).isEqualTo(1);
        assertThat(metrics.value("axiom.http.streams.active", "method", "GET", "route", "/files/:name")).isZero();
        assertThat(metrics.values.keySet()).noneMatch(key -> key.contains("alpha") || key.contains("beta"));
    }

    @Test void backpressureIsCountedPerRoute() throws Exception {
        var metrics = new Recorder();
        var app = Axiom.create().metrics(metrics);
        app.get("/feed", ctx -> Response.stream(200, "text/plain", out -> { out.write("a"); out.write("b"); }));
        try (var client = TestClient.start(app); var stream = client.stream(get("/feed"))) {
            // Nobody has read "a", so the write of "b" must wait: the metric is how the test learns it did.
            assertThat(metrics.backpressure.await(30, TimeUnit.SECONDS)).isTrue();
            assertThat(stream.nextText()).contains("a");
            assertThat(stream.nextText()).contains("b");
            assertThat(metrics.value("axiom.http.stream.backpressure", "method", "GET", "route", "/feed")).isEqualTo(1);
        }
    }
}
