package com.jsgalactic.axiom.http.internal;

import static org.assertj.core.api.Assertions.assertThat;

import com.jsgalactic.axiom.execution.AdmissionPolicy;
import com.jsgalactic.axiom.http.Response;
import com.jsgalactic.axiom.http.ServerSentEvent;
import com.jsgalactic.axiom.http.StreamAbortedException;
import com.jsgalactic.axiom.http.StreamAbortedException.Reason;
import com.jsgalactic.axiom.http.StreamOutcome;
import com.jsgalactic.axiom.server.internal.execution.StreamMetrics;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Streamed responses over a real socket. Waiting is always for an event (a metric, a future, the
 * client's read) and bounded, never for a fixed time.
 */
@Tag("integration")
class HttpStreamTest {
    private static final String ACTIVE = "axiom.admission.active";
    private static final String STREAM_ACTIVE = "axiom.http.streams.active";

    private static String text(byte[] bytes) { return new String(bytes, StandardCharsets.UTF_8); }

    private static NettyServer serve(Fixture fixture, TransportSettings settings) throws IOException {
        fixture.app.start();
        var server = NettyServer.bind(fixture.app, new InetSocketAddress("127.0.0.1", 0), settings);
        fixture.servers.add(server);
        return server;
    }

    /** Small fixed buffers, so that a client that does not read stops the handler after little data. */
    private static TransportSettings small() { return TransportSettings.DEFAULTS.withSocketBuffers(16 * 1024, 16 * 1024); }

    @Test void sendsChunksAsWrittenWithChunkedFramingAndKeepsTheConnection() throws Exception {
        try (var fixture = new Fixture()) {
            fixture.app.get("/feed", ctx -> Response.stream(200, "text/plain", out -> {
                out.write("one");
                out.write("two");
                out.write(new byte[0]); // Nothing to send: a zero-length chunk would end the body early.
                out.write("three");
            }));
            fixture.app.get("/next", ctx -> "next");
            var wire = fixture.connect();
            wire.write("GET /feed HTTP/1.1\r\nHost: a\r\n\r\n");
            var head = wire.head();
            assertThat(head.status()).isEqualTo(200);
            assertThat(head.headers()).containsEntry("Transfer-Encoding", "chunked")
                    .containsEntry("Content-Type", "text/plain").containsKey("X-Request-ID").containsKey("Date")
                    .doesNotContainKey("Content-Length");
            assertThat(head.headers().get("Connection")).isNotEqualToIgnoringCase("close");
            assertThat(text(wire.chunk())).isEqualTo("one");
            assertThat(text(wire.chunk())).isEqualTo("two");
            assertThat(text(wire.chunk())).isEqualTo("three");
            assertThat(wire.chunk()).isNull();
            // The same connection serves the next request: the final chunk ended the response exactly.
            assertThat(wire.get("/next").text()).isEqualTo("next");
        }
    }

    @Test void largeWritesAreSplitIntoBoundedChunks() throws Exception {
        try (var fixture = new Fixture()) {
            var data = new byte[3 * ChannelBodyWriter.PIECE + 5];
            for (int i = 0; i < data.length; i++) { data[i] = (byte) i; }
            fixture.app.get("/blob", ctx -> Response.stream(200, "application/octet-stream", out -> out.write(data)));
            var wire = fixture.connect();
            wire.write("GET /blob HTTP/1.1\r\nHost: a\r\n\r\n");
            wire.head();
            var all = new java.io.ByteArrayOutputStream();
            int chunks = 0;
            for (byte[] next; (next = wire.chunk()) != null; chunks++) {
                assertThat(next.length).isLessThanOrEqualTo(ChannelBodyWriter.PIECE);
                all.write(next);
            }
            assertThat(chunks).isEqualTo(4);
            assertThat(all.toByteArray()).isEqualTo(data);
        }
    }

    @Test void applicationFramingHeadersAreIgnoredAndConnectionCloseIsHonored() throws Exception {
        try (var fixture = new Fixture()) {
            fixture.app.get("/feed", ctx -> Response.stream(200, "text/plain", out -> out.write("abc"))
                    .withHeader("Content-Length", "1").withHeader("Connection", "close"));
            var wire = fixture.connect();
            wire.write("GET /feed HTTP/1.1\r\nHost: a\r\n\r\n");
            var head = wire.head();
            assertThat(head.headers()).containsEntry("Transfer-Encoding", "chunked").containsEntry("Connection", "close")
                    .doesNotContainKey("Content-Length");
            assertThat(text(wire.chunkedBody())).isEqualTo("abc");
            assertThat(wire.socket.getInputStream().read()).isEqualTo(-1);
        }
    }

    @Test void http10ClientsGetAnUnframedBodyDelimitedByTheClose() throws Exception {
        try (var fixture = new Fixture()) {
            fixture.app.get("/feed", ctx -> Response.stream(200, "text/plain", out -> { out.write("a"); out.write("b"); }));
            var wire = fixture.connect();
            wire.write("GET /feed HTTP/1.0\r\nHost: a\r\n\r\n");
            var head = wire.head();
            assertThat(head.headers()).containsEntry("Connection", "close").doesNotContainKey("Transfer-Encoding")
                    .doesNotContainKey("Content-Length");
            assertThat(text(wire.socket.getInputStream().readAllBytes())).isEqualTo("ab");
        }
    }

    @Test void headGetsTheHeadOnlyAndNeverRunsTheBody() throws Exception {
        var runs = new AtomicInteger();
        try (var fixture = new Fixture()) {
            fixture.app.get("/feed", ctx -> Response.stream(200, "text/event-stream", out -> {
                runs.incrementAndGet();
                out.write("data");
            }));
            fixture.app.get("/next", ctx -> "next");
            var wire = fixture.connect();
            wire.write("HEAD /feed HTTP/1.1\r\nHost: a\r\n\r\n");
            var head = wire.read(true);
            assertThat(head.status()).isEqualTo(200);
            assertThat(head.headers()).containsEntry("Content-Type", "text/event-stream")
                    .doesNotContainKey("Content-Length").doesNotContainKey("Transfer-Encoding");
            // No body or chunk framing follows: the next response starts right after the head.
            assertThat(wire.get("/next").text()).isEqualTo("next");
            assertThat(runs).hasValue(0);
        }
    }

    @Test void headersTheTransportCannotSendAreAnOrdinary500AndTheBodyNeverRuns() throws Exception {
        var runs = new AtomicInteger();
        try (var fixture = new Fixture()) {
            fixture.app.get("/feed", ctx -> Response.stream(200, "text/plain", out -> runs.incrementAndGet())
                    .withHeader("X-Big", "a".repeat(9000)));
            assertThat(fixture.connect().get("/feed").status()).isEqualTo(500);
            assertThat(runs).hasValue(0);
        }
    }

    @Test void aWriteThatWouldCrossTheByteCapSendsNothingAndEndsTheResponseByClosing() throws Exception {
        var failure = new CompletableFuture<Reason>();
        var again = new CompletableFuture<Reason>();
        var probe = new Probe();
        try (var fixture = new Fixture()) {
            fixture.app.metrics(probe);
            fixture.app.get("/capped", ctx -> Response.stream(200, "text/plain", 10, out -> {
                out.write("12345678"); // 8 of 10 bytes.
                try { out.write("abc"); } // Would be 11.
                catch (StreamAbortedException refused) { failure.complete(refused.reason()); }
                // Swallowing the exception does not revive the stream.
                try { out.write("a"); } catch (StreamAbortedException refused) { again.complete(refused.reason()); }
            }));
            fixture.app.get("/exact", ctx -> Response.stream(200, "text/plain", 3, out -> out.write("abc")));
            var server = fixture.listen();
            try (var wire = new Wire(server)) {
                wire.write("GET /capped HTTP/1.1\r\nHost: a\r\n\r\n");
                wire.head();
                assertThat(text(wire.chunk())).isEqualTo("12345678");
                // Cut off: no final chunk, no second response, then the end of the connection.
                assertThat(wire.socket.getInputStream().read()).isEqualTo(-1);
                assertThat(failure.get(30, TimeUnit.SECONDS)).isEqualTo(Reason.LIMIT_EXCEEDED);
                assertThat(again.get(30, TimeUnit.SECONDS)).isEqualTo(Reason.LIMIT_EXCEEDED);
            }
            probe.await(v -> v == 1, StreamMetrics.STREAMS, "method", "GET", "route", "/capped", "outcome", "limit_exceeded");
            assertThat(probe.value(StreamMetrics.BYTES, "method", "GET", "route", "/capped")).isEqualTo(8);
            // A body of exactly the cap is complete.
            try (var wire = new Wire(server)) {
                wire.write("GET /exact HTTP/1.1\r\nHost: a\r\n\r\n");
                wire.head();
                assertThat(text(wire.chunkedBody())).isEqualTo("abc");
            }
        }
    }

    @Test void aBodyThatFailsAfterTheHeadWasSentClosesTheConnectionWithoutASecondResponse() throws Exception {
        var probe = new Probe();
        try (var fixture = new Fixture()) {
            fixture.app.metrics(probe);
            fixture.app.get("/broken", ctx -> Response.stream(200, "text/plain", out -> {
                out.write("partial");
                throw new IllegalStateException("handler bug");
            }));
            fixture.app.get("/next", ctx -> "next");
            var server = fixture.listen();
            try (var wire = new Wire(server)) {
                wire.write("GET /broken HTTP/1.1\r\nHost: a\r\n\r\n");
                var head = wire.head();
                assertThat(head.status()).isEqualTo(200);
                assertThat(text(wire.chunk())).isEqualTo("partial");
                // Neither a final chunk nor a 500: the response is abandoned by closing.
                assertThat(wire.socket.getInputStream().readAllBytes()).isEmpty();
            }
            // The failure did not poison the listener, and the admission slot was released.
            probe.await(v -> v == 1, StreamMetrics.STREAMS, "method", "GET", "route", "/broken", "outcome", "failed");
            probe.await(v -> v == 0, ACTIVE);
            try (var wire = new Wire(server)) {
                assertThat(wire.get("/next").text()).isEqualTo("next");
            }
        }
    }

    @Test void aClientThatDisconnectsMidStreamAbortsTheWriterAndReleasesTheSlot() throws Exception {
        var reason = new CompletableFuture<Reason>();
        var probe = new Probe();
        try (var fixture = new Fixture()) {
            fixture.app.metrics(probe);
            fixture.app.get("/endless", ctx -> Response.stream(200, "text/plain", out -> {
                try {
                    out.write("hello");
                    for (;;) { out.write(new byte[1024]); }
                } catch (StreamAbortedException aborted) {
                    reason.complete(aborted.reason());
                    throw aborted;
                }
            }));
            var server = serve(fixture, small());
            try (var wire = new Wire(server, 4096)) {
                wire.write("GET /endless HTTP/1.1\r\nHost: a\r\n\r\n");
                wire.head();
                assertThat(text(wire.chunk())).isEqualTo("hello");
                wire.socket.setSoLinger(true, 0); // Closing resets the connection at once.
            }
            assertThat(reason.get(30, TimeUnit.SECONDS)).isEqualTo(Reason.CLIENT_DISCONNECTED);
            probe.await(v -> v == 1, StreamMetrics.STREAMS, "method", "GET", "route", "/endless", "outcome", "client_disconnected");
            probe.await(v -> v == 0, ACTIVE);
            probe.await(v -> v == 0, STREAM_ACTIVE, "method", "GET", "route", "/endless");
        }
    }

    @Test void aSlowClientHoldsTheHandlerBackInsteadOfBufferingTheBody() throws Exception {
        int total = 16 * 1024 * 1024;
        int piece = 8 * 1024;
        var accepted = new AtomicLong();
        var probe = new Probe();
        try (var fixture = new Fixture()) {
            fixture.app.metrics(probe);
            fixture.app.get("/big", ctx -> Response.stream(200, "application/octet-stream", out -> {
                var data = new byte[piece];
                for (long sent = 0; sent < total; sent += piece) {
                    data[0] = (byte) (sent / piece);
                    out.write(data);
                    accepted.set(out.bytesWritten());
                }
            }));
            var server = serve(fixture, small());
            try (var wire = new Wire(server, 4096)) {
                wire.write("GET /big HTTP/1.1\r\nHost: a\r\n\r\n");
                wire.head();
                // The client reads nothing more until the handler has had to wait for it.
                probe.await(v -> v >= 1, StreamMetrics.BACKPRESSURE, "method", "GET", "route", "/big");
                long buffered = accepted.get();
                assertThat(buffered).isPositive().isLessThan(2L * 1024 * 1024);
                assertThat(probe.value(StreamMetrics.STREAMS, "method", "GET", "route", "/big", "outcome", "completed")).isZero();
                // Reading resumes the handler, and the whole body arrives in order.
                var body = wire.chunkedBody();
                assertThat(body).hasSize(total);
                for (int at = 0; at < total; at += piece) { assertThat(body[at]).isEqualTo((byte) (at / piece)); }
            }
            probe.await(v -> v == 1, StreamMetrics.STREAMS, "method", "GET", "route", "/big", "outcome", "completed");
            assertThat(probe.value(StreamMetrics.BYTES, "method", "GET", "route", "/big")).isEqualTo(total);
            probe.await(v -> v == 0, STREAM_ACTIVE, "method", "GET", "route", "/big");
        }
    }

    @Test void aClientThatStopsReadingIsDroppedAfterTheStallBound() throws Exception {
        var reason = new CompletableFuture<Reason>();
        try (var fixture = new Fixture()) {
            fixture.app.get("/endless", ctx -> Response.stream(200, "text/plain", out -> {
                try { for (;;) { out.write(new byte[8192]); } }
                catch (StreamAbortedException aborted) { reason.complete(aborted.reason()); throw aborted; }
            }));
            var server = serve(fixture, small().withResponseTimeout(Duration.ofMillis(200)));
            try (var wire = new Wire(server, 4096)) {
                wire.write("GET /endless HTTP/1.1\r\nHost: a\r\n\r\n");
                wire.head();
                assertThat(reason.get(30, TimeUnit.SECONDS)).isEqualTo(Reason.CLIENT_DISCONNECTED);
            }
        }
    }

    @Test void theRequestDeadlineEndsAStreamByClosingTheConnection() throws Exception {
        var probe = new Probe();
        try (var fixture = new Fixture()) {
            fixture.app.metrics(probe);
            fixture.app.requestTimeout(Duration.ofMillis(300));
            fixture.app.get("/stuck", ctx -> Response.stream(200, "text/plain", out -> {
                out.write("x");
                new CountDownLatch(1).await(); // Interrupted by the deadline.
            }));
            var server = fixture.listen();
            try (var wire = new Wire(server)) {
                wire.write("GET /stuck HTTP/1.1\r\nHost: a\r\n\r\n");
                wire.head();
                assertThat(text(wire.chunk())).isEqualTo("x");
                // No 504 follows a response that is already under way.
                assertThat(wire.socket.getInputStream().readAllBytes()).isEmpty();
            }
            probe.await(v -> v == 1, StreamMetrics.STREAMS, "method", "GET", "route", "/stuck", "outcome", "timeout");
            probe.await(v -> v == 0, ACTIVE);
        }
    }

    @Test void aStreamHoldsItsAdmissionSlotUntilItEnds() throws Exception {
        var started = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        try (var fixture = new Fixture()) {
            fixture.app.admissionPolicy(AdmissionPolicy.reject(1));
            fixture.app.get("/held", ctx -> Response.stream(200, "text/plain", out -> {
                out.write("first");
                started.countDown();
                release.await();
                out.write("last");
            }));
            fixture.app.get("/other", ctx -> "other");
            var server = fixture.listen();
            try (var held = new Wire(server)) {
                held.write("GET /held HTTP/1.1\r\nHost: a\r\n\r\n");
                held.head();
                assertThat(text(held.chunk())).isEqualTo("first");
                started.await();
                try (var other = new Wire(server)) {
                    assertThat(other.get("/other").status()).isEqualTo(503);
                }
                release.countDown();
                assertThat(text(held.chunkedBody())).isEqualTo("last");
                assertThat(held.get("/other").text()).isEqualTo("other");
            }
        }
    }

    @Test void shutdownCutsAStreamThatIgnoresItOnceTheGracePeriodEnds() throws Exception {
        var reason = new CompletableFuture<Reason>();
        var signalled = new CountDownLatch(1);
        try (var fixture = new Fixture()) {
            fixture.app.get("/endless", ctx -> Response.stream(200, "text/plain", out -> {
                out.onShutdown(signalled::countDown);
                try {
                    out.write("first");
                    for (;;) { out.write(new byte[8192]); }
                } catch (StreamAbortedException aborted) {
                    reason.complete(aborted.reason());
                    throw aborted;
                }
            }));
            var server = serve(fixture, small().withShutdownGrace(Duration.ofMillis(300)));
            try (var wire = new Wire(server, 4096)) {
                wire.write("GET /endless HTTP/1.1\r\nHost: a\r\n\r\n");
                wire.head();
                assertThat(text(wire.chunk())).isEqualTo("first");
                server.close();
                assertThat(signalled.await(30, TimeUnit.SECONDS)).isTrue();
                assertThat(reason.get(30, TimeUnit.SECONDS)).isEqualTo(Reason.SHUTDOWN);
                server.termination().toCompletableFuture().get(30, TimeUnit.SECONDS);
                // The body was cut off, not completed: the bytes the client got never end in a final chunk.
                var rest = text(wire.socket.getInputStream().readAllBytes());
                assertThat(rest).doesNotEndWith("\r\n0\r\n\r\n");
            }
        }
    }

    @Test void aStreamThatSeesShutdownEndsCleanlyWithAFinalChunkWithoutWaitingOutTheGracePeriod() throws Exception {
        var outcome = new CompletableFuture<StreamOutcome>();
        var probe = new Probe();
        try (var fixture = new Fixture()) {
            fixture.app.metrics(probe);
            fixture.app.get("/events", ctx -> Response.sse(events -> {
                var shutdown = new CountDownLatch(1);
                events.onShutdown(shutdown::countDown);
                events.send("first");
                shutdown.await();
                events.send(ServerSentEvent.named("bye", "closing"));
            }).onStreamEnd(outcome::complete));
            // A long grace period: the stream must end by itself, long before it.
            var server = serve(fixture, small().withShutdownGrace(Duration.ofSeconds(120)));
            try (var wire = new Wire(server)) {
                wire.write("GET /events HTTP/1.1\r\nHost: a\r\n\r\n");
                wire.head();
                assertThat(text(wire.chunk())).isEqualTo("data: first\n\n");
                server.close();
                assertThat(text(wire.chunk())).isEqualTo("event: bye\ndata: closing\n\n");
                assertThat(wire.chunk()).isNull(); // The final chunk: a clean end of the body.
                assertThat(wire.socket.getInputStream().read()).isEqualTo(-1); // Then the connection closes.
                server.termination().toCompletableFuture().get(30, TimeUnit.SECONDS);
            }
            assertThat(outcome.get(30, TimeUnit.SECONDS).kind()).isEqualTo(StreamOutcome.Kind.COMPLETED);
            probe.await(v -> v == 1, StreamMetrics.STREAMS, "method", "GET", "route", "/events", "outcome", "completed");
        }
    }

    @Test void aFiniteDownloadFinishesDuringTheGracePeriodAfterClose() throws Exception {
        var started = new CountDownLatch(1);
        var closed = new CountDownLatch(1);
        try (var fixture = new Fixture()) {
            fixture.app.get("/download", ctx -> Response.stream(200, "text/plain", out -> {
                out.write("part one;");
                started.countDown();
                closed.await();
                out.write("part two");
            }));
            var server = serve(fixture, TransportSettings.DEFAULTS.withShutdownGrace(Duration.ofSeconds(120)));
            try (var wire = new Wire(server)) {
                wire.write("GET /download HTTP/1.1\r\nHost: a\r\n\r\n");
                wire.head();
                assertThat(text(wire.chunk())).isEqualTo("part one;");
                assertThat(started.await(30, TimeUnit.SECONDS)).isTrue();
                server.close();
                closed.countDown();
                assertThat(text(wire.chunkedBody())).isEqualTo("part two");
                server.termination().toCompletableFuture().get(30, TimeUnit.SECONDS);
            }
        }
    }

    @Test void aStreamGetsItsOwnLifetimeLongerThanTheRequestDeadline() throws Exception {
        var remaining = new CompletableFuture<Duration>();
        var passed = new CountDownLatch(1);
        try (var fixture = new Fixture()) {
            fixture.app.requestTimeout(Duration.ofMillis(150));
            fixture.app.get("/long", ctx -> Response.stream(200, "text/plain", out -> {
                out.write("a");
                remaining.complete(ctx.execution().remainingTime());
                // Outlive the original deadline: the dispatcher's timer has to wait for the new one.
                passed.await(400, TimeUnit.MILLISECONDS);
                out.write("b");
            }).withStreamLifetime(Duration.ofMinutes(5)));
            fixture.app.get("/short", ctx -> Response.stream(200, "text/plain", out -> {
                out.write("x");
                new CountDownLatch(1).await();
            }));
            var server = fixture.listen();
            try (var wire = new Wire(server)) {
                wire.write("GET /long HTTP/1.1\r\nHost: a\r\n\r\n");
                wire.head();
                assertThat(text(wire.chunkedBody())).isEqualTo("ab");
                assertThat(remaining.get(30, TimeUnit.SECONDS)).isGreaterThan(Duration.ofMinutes(4));
            }
            try (var wire = new Wire(server)) {
                wire.write("GET /short HTTP/1.1\r\nHost: a\r\n\r\n");
                wire.head();
                assertThat(text(wire.chunk())).isEqualTo("x");
                assertThat(wire.socket.getInputStream().readAllBytes()).isEmpty(); // Cut at the ordinary deadline.
            }
        }
    }

    @Test void serverSentEventsGoOutAsOneChunkEachWithoutBufferingOrCachingHeaders() throws Exception {
        try (var fixture = new Fixture()) {
            fixture.app.get("/events", ctx -> Response.sse(events -> {
                events.send(ServerSentEvent.named("tick", "1").withId("7"));
                events.keepAlive();
                // Line breaks in client-influenced text can start no field of their own.
                events.send("x\r\nevent: forged\n\nid: 9");
            }));
            var wire = fixture.connect();
            wire.write("GET /events HTTP/1.1\r\nHost: a\r\nAccept: text/event-stream\r\n\r\n");
            var head = wire.head();
            assertThat(head.status()).isEqualTo(200);
            assertThat(head.headers()).containsEntry("Content-Type", "text/event-stream")
                    .containsEntry("Cache-Control", "no-store, no-transform").containsEntry("X-Accel-Buffering", "no")
                    .containsEntry("Transfer-Encoding", "chunked").doesNotContainKey("Content-Encoding")
                    .doesNotContainKey("Content-Length");
            assertThat(text(wire.chunk())).isEqualTo("event: tick\nid: 7\ndata: 1\n\n");
            assertThat(text(wire.chunk())).isEqualTo(": keep-alive\n\n");
            assertThat(text(wire.chunk())).isEqualTo("data: x\ndata: event: forged\ndata: \ndata: id: 9\n\n");
            assertThat(wire.chunk()).isNull();
        }
    }

    @Test void streamMetricsCarryTheRouteTemplateAndAFixedOutcomeTagOnly() throws Exception {
        var probe = new Probe();
        try (var fixture = new Fixture()) {
            fixture.app.metrics(probe);
            fixture.app.get("/files/:name", ctx -> Response.stream(200, "text/plain", out -> out.write(ctx.path("name"))));
            fixture.app.get("/logs/:name", ctx -> Response.stream(200, "text/plain", out -> out.write("log")));
            var server = fixture.listen();
            try (var wire = new Wire(server)) {
                for (var name : new String[] {"alpha", "beta", "gamma"}) {
                    wire.write("GET /files/" + name + " HTTP/1.1\r\nHost: a\r\n\r\n");
                    wire.head();
                    assertThat(text(wire.chunkedBody())).isEqualTo(name);
                }
                wire.write("GET /logs/one HTTP/1.1\r\nHost: a\r\n\r\n");
                wire.head();
                assertThat(text(wire.chunkedBody())).isEqualTo("log");
            }
            var files = new String[] {"method", "GET", "route", "/files/:name"};
            probe.await(v -> v == 3, StreamMetrics.STREAMS, "method", "GET", "route", "/files/:name", "outcome", "completed");
            probe.await(v -> v == 1, StreamMetrics.STREAMS, "method", "GET", "route", "/logs/:name", "outcome", "completed");
            assertThat(probe.value(StreamMetrics.BYTES, files)).isEqualTo("alpha".length() + "beta".length() + "gamma".length());
            assertThat(probe.value(StreamMetrics.BYTES, "method", "GET", "route", "/logs/:name")).isEqualTo(3);
            // The request counter still carries the route template, never the concrete path.
            probe.await(v -> v == 3, "axiom.http.requests", "method", "GET", "route", "/files/:name", "status_class", "2xx");
            probe.await(v -> v == 0, STREAM_ACTIVE, files);
            var streamTags = probe.tagKeys().stream().filter(key -> key.startsWith("axiom.http.stream")).toList();
            assertThat(streamTags).containsExactlyInAnyOrder("axiom.http.streams:method", "axiom.http.streams:route",
                    "axiom.http.streams:outcome", "axiom.http.stream.bytes:method", "axiom.http.stream.bytes:route",
                    "axiom.http.streams.active:method", "axiom.http.streams.active:route");
            assertThat(probe.tagValues().stream().filter(value -> value.startsWith("axiom.http.streams:outcome=")))
                    .isSubsetOf(Set.of("axiom.http.streams:outcome=completed", "axiom.http.streams:outcome=failed",
                            "axiom.http.streams:outcome=client_disconnected", "axiom.http.streams:outcome=limit_exceeded",
                            "axiom.http.streams:outcome=timeout", "axiom.http.streams:outcome=shutdown"));
            assertThat(probe.tagValues().stream().filter(value -> value.contains(":route=")))
                    .noneMatch(value -> value.contains("alpha") || value.contains("beta") || value.contains("one"));
        }
    }
}
