package com.jsgalactic.axiom.http.internal;

import static org.assertj.core.api.Assertions.assertThat;

import com.jsgalactic.axiom.Axiom;
import com.jsgalactic.axiom.application.Application;
import com.jsgalactic.axiom.http.Response;
import com.jsgalactic.axiom.server.internal.execution.RequestDispatcher;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.http.HttpResponseEncoder;
import io.netty.handler.timeout.IdleStateEvent;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * A listener error on a later pipelined request must not abort earlier work: earlier requests
 * complete and are answered in order, then the error is written and the connection closes.
 */
class HttpPipelineErrorTest {
    private static final String SLOW = "POST /slow HTTP/1.1\r\nHost: a\r\nContent-Length: 4\r\n\r\nbody";
    private static final String NEXT = "GET /next HTTP/1.1\r\nHost: a\r\n\r\n";
    private static final String AFTER = "GET /after HTTP/1.1\r\nHost: a\r\n\r\n";

    private final CountDownLatch entered = new CountDownLatch(1);
    private final CountDownLatch release = new CountDownLatch(1);
    private final AtomicBoolean interrupted = new AtomicBoolean();
    private final AtomicBoolean after = new AtomicBoolean();
    private final RequestDispatcher executor = new RequestDispatcher(4);
    private final Application app = Axiom.create();
    private HttpConnection connection;

    @BeforeEach void routes() {
        app.maxRequestBody(16);
        app.requestTimeout(Duration.ofSeconds(30));
        app.post("/slow", ctx -> {
            entered.countDown();
            try { release.await(); }
            catch (InterruptedException cancelled) { interrupted.set(true); throw cancelled; }
            return "slow:" + ctx.request().body().length();
        });
        app.get("/next", ctx -> "next");
        app.get("/after", ctx -> { after.set(true); return "after"; });
        app.post("/after", ctx -> { after.set(true); return "after"; });
    }

    @AfterEach void stop() throws Exception {
        release.countDown();
        app.close();
        executor.close();
        executor.termination().toCompletableFuture().get(30, TimeUnit.SECONDS);
    }

    static List<Arguments> errors() {
        return List.of(
                Arguments.of("rejected path", "GET /bad%zz HTTP/1.1\r\nHost: a\r\n\r\n", 400),
                Arguments.of("missing host", "GET / HTTP/1.1\r\n\r\n", 400),
                Arguments.of("unparseable request line", "GARBAGE\r\n\r\n", 400),
                Arguments.of("bad chunk size", "POST /after HTTP/1.1\r\nHost: a\r\nTransfer-Encoding: chunked\r\n\r\nzz\r\n", 400),
                Arguments.of("length and chunked", "POST /after HTTP/1.1\r\nHost: a\r\nTransfer-Encoding: chunked\r\n"
                        + "Content-Length: 3\r\n\r\n3\r\nabc\r\n0\r\n\r\n", 400),
                Arguments.of("two lengths", "POST /after HTTP/1.1\r\nHost: a\r\nContent-Length: 1\r\nContent-Length: 2\r\n\r\nab", 400),
                Arguments.of("declared length over limit", "POST /after HTTP/1.1\r\nHost: a\r\nContent-Length: 17\r\n\r\n"
                        + "x".repeat(17), 413),
                Arguments.of("chunked body over limit", "POST /after HTTP/1.1\r\nHost: a\r\nTransfer-Encoding: chunked\r\n\r\n"
                        + "11\r\n" + "x".repeat(17) + "\r\n0\r\n\r\n", 413),
                Arguments.of("unknown expectation", "POST /after HTTP/1.1\r\nHost: a\r\nExpect: nope\r\nContent-Length: 1\r\n\r\nx", 417),
                Arguments.of("long request line", "GET /" + "a".repeat(5000) + " HTTP/1.1\r\nHost: a\r\n\r\n", 414),
                Arguments.of("large header section", "GET / HTTP/1.1\r\nHost: a\r\nX-Large: " + "x".repeat(9000) + "\r\n\r\n", 431),
                Arguments.of("unknown version", "GET / HTTP/1.2\r\nHost: a\r\n\r\n", 505),
                Arguments.of("upgrade", "GET / HTTP/1.1\r\nHost: a\r\nUpgrade: websocket\r\n\r\n", 501));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("errors")
    void errorBehindRunningAndQueuedRequestsIsAnsweredAfterThem(String name, String rejected, int status) throws Exception {
        var channel = wireChannel();
        try {
            channel.writeInbound(ascii(SLOW + NEXT + rejected + AFTER));
            assertThat(entered.await(30, TimeUnit.SECONDS)).isTrue();
            // Nothing is written ahead of the running request, and the connection stays open.
            channel.runPendingTasks();
            assertThat(channel.<Object>readOutbound()).isNull();
            assertThat(channel.isActive()).isTrue();
            release.countDown();
            var replies = repliesUntilClosed(channel);
            assertThat(replies).extracting(Reply::status).containsExactly(200, 200, status);
            assertThat(replies.get(0).text()).isEqualTo("slow:4");
            assertThat(replies.get(1).text()).isEqualTo("next");
            assertThat(replies.get(0).headers()).doesNotContainKey("connection");
            HttpStatusMappingTest.assertProblem(replies.get(2), status, com.jsgalactic.axiom.http.HttpStatus.defaultCode(status));
            assertThat(replies.get(2).headers()).containsEntry("connection", "close");
            assertThat(interrupted).isFalse();
            assertThat(after).isFalse();
        } finally { channel.finishAndReleaseAll(); }
    }

    @ParameterizedTest(name = "{0}")
    @org.junit.jupiter.params.provider.ValueSource(strings = {
            "Transfer-Encoding: chunked\r\nTransfer-Encoding: gzip",
            "Transfer-Encoding: gzip\r\nTransfer-Encoding: chunked",
            "Transfer-Encoding: chunked\r\nTransfer-Encoding: chunked",
            "Transfer-Encoding: chunked, chunked",
            "Transfer-Encoding: chunked\r\nTransfer-Encoding: identity"})
    void ambiguousTransferEncodingIsRejectedAndNothingAfterItRuns(String fields) throws Exception {
        var channel = wireChannel();
        try {
            // The body framing is ambiguous, so the "body" bytes must never be read as a request.
            channel.writeInbound(ascii("POST /after HTTP/1.1\r\nHost: a\r\n" + fields + "\r\n\r\n"
                    + "3\r\nabc\r\n0\r\n\r\n" + AFTER));
            var replies = repliesUntilClosed(channel);
            assertThat(replies).extracting(Reply::status).containsExactly(400);
            assertThat(replies.getFirst().headers()).containsEntry("connection", "close");
            assertThat(after).isFalse();
        } finally { channel.finishAndReleaseAll(); }
    }

    /**
     * One Transfer-Encoding line whose final coding is chunked but which applies another coding
     * first: the framing is unambiguous, the coding is not supported, so RFC 9112 section 6.1 asks
     * for 501. Nothing after it is read as a request.
     */
    @ParameterizedTest(name = "{0}")
    @org.junit.jupiter.params.provider.ValueSource(strings = {
            "Transfer-Encoding: gzip, chunked",
            "Transfer-Encoding: identity, chunked",
            "Transfer-Encoding: x-custom ,chunked",
            "Transfer-Encoding: deflate, gzip, chunked"})
    void unsupportedCodingBeforeChunkedIsNotImplementedAndNothingAfterItRuns(String field) throws Exception {
        var channel = wireChannel();
        try {
            channel.writeInbound(ascii("POST /after HTTP/1.1\r\nHost: a\r\n" + field + "\r\n\r\n"
                    + "3\r\nabc\r\n0\r\n\r\n" + AFTER));
            var replies = repliesUntilClosed(channel);
            assertThat(replies).extracting(Reply::status).containsExactly(501);
            HttpStatusMappingTest.assertProblem(replies.getFirst(), 501, "not_implemented");
            assertThat(replies.getFirst().headers()).containsEntry("connection", "close");
            assertThat(after).isFalse();
        } finally { channel.finishAndReleaseAll(); }
    }

    @ParameterizedTest(name = "{0}")
    @org.junit.jupiter.params.provider.ValueSource(strings = {
            "Transfer-Encoding: gzip, chunked",
            "Transfer-Encoding: identity, chunked"})
    void unsupportedCodingBehindARunningRequestIsAnsweredAfterIt(String field) throws Exception {
        var channel = wireChannel();
        try {
            channel.writeInbound(ascii(SLOW + "POST /after HTTP/1.1\r\nHost: a\r\n" + field + "\r\n\r\n"
                    + "3\r\nabc\r\n0\r\n\r\n" + AFTER));
            assertThat(entered.await(30, TimeUnit.SECONDS)).isTrue();
            release.countDown();
            assertThat(repliesUntilClosed(channel)).extracting(Reply::status).containsExactly(200, 501);
            assertThat(after).isFalse();
        } finally { channel.finishAndReleaseAll(); }
    }

    @ParameterizedTest(name = "{0}")
    @org.junit.jupiter.params.provider.ValueSource(strings = {
            "Transfer-Encoding: chunked\r\nTransfer-Encoding: gzip",
            "Transfer-Encoding: gzip\r\nTransfer-Encoding: chunked",
            "Transfer-Encoding: chunked, chunked"})
    void ambiguousTransferEncodingBehindARunningRequestIsAnsweredAfterIt(String fields) throws Exception {
        var channel = wireChannel();
        try {
            channel.writeInbound(ascii(SLOW + "POST /after HTTP/1.1\r\nHost: a\r\n" + fields + "\r\n\r\n"
                    + "3\r\nabc\r\n0\r\n\r\n" + AFTER));
            assertThat(entered.await(30, TimeUnit.SECONDS)).isTrue();
            release.countDown();
            assertThat(repliesUntilClosed(channel)).extracting(Reply::status).containsExactly(200, 400);
            assertThat(after).isFalse();
        } finally { channel.finishAndReleaseAll(); }
    }

    @Test void requestBeyondThePipelineBoundIsAnswered503AfterTheEarlierOnes() throws Exception {
        var channel = wireChannel();
        try {
            int queued = HttpConnection.MAX_PIPELINED - 1;
            channel.writeInbound(ascii(SLOW + NEXT.repeat(queued) + AFTER + AFTER));
            assertThat(entered.await(30, TimeUnit.SECONDS)).isTrue();
            channel.runPendingTasks();
            assertThat(channel.isActive()).isTrue();
            release.countDown();
            var replies = repliesUntilClosed(channel);
            assertThat(replies).hasSize(HttpConnection.MAX_PIPELINED + 1);
            assertThat(replies.getFirst().text()).isEqualTo("slow:4");
            for (int i = 1; i <= queued; i++) { assertThat(replies.get(i).text()).isEqualTo("next"); }
            var refused = replies.getLast();
            HttpStatusMappingTest.assertProblem(refused, 503, "service_unavailable");
            assertThat(refused.headers()).containsEntry("connection", "close");
            assertThat(interrupted).isFalse();
            assertThat(after).isFalse();
        } finally { channel.finishAndReleaseAll(); }
    }

    @Test void queuedBodiesBeyondTheConnectionShareAreAnswered503AfterTheEarlierOnes() throws Exception {
        app.post("/full", ctx -> "full");
        var channel = wireChannel();
        String full = "POST /after HTTP/1.1\r\nHost: a\r\nContent-Length: 16\r\n\r\n" + "x".repeat(16);
        try {
            String queuedFull = full.replace("/after", "/full");
            // Two queued 16-byte bodies fill the 2 x limit share; the third declaration exceeds it.
            channel.writeInbound(ascii(SLOW + queuedFull + queuedFull + full));
            assertThat(entered.await(30, TimeUnit.SECONDS)).isTrue();
            release.countDown();
            var replies = repliesUntilClosed(channel);
            assertThat(replies).extracting(Reply::status).containsExactly(200, 200, 200, 503);
            assertThat(replies.get(1).text()).isEqualTo("full");
            assertThat(after).isFalse();
            assertThat(interrupted).isFalse();
        } finally { channel.finishAndReleaseAll(); }
    }

    @Test void headTimeoutBehindARunningRequestIsAnsweredAfterIt() throws Exception {
        var channel = wireChannel();
        try {
            channel.writeInbound(ascii(SLOW + "GET /after HT"));
            assertThat(entered.await(30, TimeUnit.SECONDS)).isTrue();
            channel.advanceTimeBy(HttpConnection.REQUEST_HEAD_TIMEOUT.toSeconds(), TimeUnit.SECONDS);
            channel.runScheduledPendingTasks();
            assertThat(channel.isActive()).isTrue();
            // Inactivity does not end the connection while the earlier handler runs.
            channel.pipeline().fireUserEventTriggered(IdleStateEvent.ALL_IDLE_STATE_EVENT);
            assertThat(channel.isActive()).isTrue();
            // Input after the error is discarded, never executed.
            channel.writeInbound(ascii("TP/1.1\r\nHost: a\r\n\r\n" + AFTER));
            release.countDown();
            var replies = repliesUntilClosed(channel);
            assertThat(replies).extracting(Reply::status).containsExactly(200, 408);
            assertThat(replies.getLast().headers()).containsEntry("connection", "close");
            assertThat(interrupted).isFalse();
            assertThat(after).isFalse();
        } finally { channel.finishAndReleaseAll(); }
    }

    @Test void bodyTimeoutBehindARunningRequestIsAnsweredAfterIt() throws Exception {
        var channel = wireChannel();
        try {
            channel.writeInbound(ascii(SLOW + "POST /after HTTP/1.1\r\nHost: a\r\nContent-Length: 10\r\n\r\nab"));
            assertThat(entered.await(30, TimeUnit.SECONDS)).isTrue();
            channel.advanceTimeBy(app.requestTimeout().toSeconds(), TimeUnit.SECONDS);
            channel.runScheduledPendingTasks();
            assertThat(channel.isActive()).isTrue();
            release.countDown();
            var replies = repliesUntilClosed(channel);
            assertThat(replies).extracting(Reply::status).containsExactly(200, 408);
            assertThat(interrupted).isFalse();
            assertThat(after).isFalse();
        } finally { channel.finishAndReleaseAll(); }
    }

    @Test void disconnectWhileAnErrorWaitsStillInterruptsTheRunningHandler() throws Exception {
        var channel = wireChannel();
        try {
            channel.writeInbound(ascii(SLOW + "GARBAGE\r\n\r\n"));
            assertThat(entered.await(30, TimeUnit.SECONDS)).isTrue();
            // Input is still read (and discarded) so that a disconnect is noticed.
            assertThat(channel.config().isAutoRead()).isTrue();
            channel.close();
            awaitInterrupted();
            channel.runPendingTasks();
            assertThat(channel.<Object>readOutbound()).isNull();
        } finally { channel.finishAndReleaseAll(); }
    }

    @Test void inputBeyondTheDiscardLimitClosesAndCancelsTheRunningHandler() throws Exception {
        var channel = wireChannel();
        try {
            channel.writeInbound(ascii(SLOW + "GARBAGE\r\n\r\n"));
            assertThat(entered.await(30, TimeUnit.SECONDS)).isTrue();
            var junk = new byte[1024 * 1024];
            // Up to the limit, input keeps being read and dropped so that a disconnect is noticed.
            for (int sent = 0; sent < HttpConnection.MAX_DISCARDED_INPUT; sent += junk.length) {
                channel.writeInbound(Unpooled.wrappedBuffer(junk));
                assertThat(channel.config().isAutoRead()).isTrue();
                assertThat(channel.isActive()).isTrue();
            }
            // A client that keeps sending beyond it is treated as abusive: the connection closes.
            channel.writeInbound(Unpooled.wrappedBuffer(new byte[1]));
            assertThat(channel.isActive()).isFalse();
            awaitInterrupted();
        } finally { channel.finishAndReleaseAll(); }
    }

    @Test void drainSendsTheRunningResponseWithCloseAndDropsTheWaitingError() throws Exception {
        var channel = wireChannel();
        try {
            channel.writeInbound(ascii(SLOW + NEXT + "GARBAGE\r\n\r\n"));
            assertThat(entered.await(30, TimeUnit.SECONDS)).isTrue();
            connection.drain();
            assertThat(channel.isActive()).isTrue();
            release.countDown();
            var replies = repliesUntilClosed(channel);
            assertThat(replies).extracting(Reply::status).containsExactly(200);
            assertThat(replies.getFirst().headers()).containsEntry("connection", "close");
            assertThat(interrupted).isFalse();
        } finally { channel.finishAndReleaseAll(); }
    }

    @Test void anEarlierResponseThatClosesTheConnectionDropsTheWaitingError() throws Exception {
        app.get("/bye", ctx -> { entered.countDown(); release.await(); return Response.of(200, "bye").withHeader("Connection", "close"); });
        var channel = wireChannel();
        try {
            channel.writeInbound(ascii("GET /bye HTTP/1.1\r\nHost: a\r\n\r\nGARBAGE\r\n\r\n"));
            assertThat(entered.await(30, TimeUnit.SECONDS)).isTrue();
            release.countDown();
            var replies = repliesUntilClosed(channel);
            assertThat(replies).extracting(Reply::status).containsExactly(200);
            assertThat(replies.getFirst().text()).isEqualTo("bye");
        } finally { channel.finishAndReleaseAll(); }
    }

    private void awaitInterrupted() {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        while (!interrupted.get()) {
            assertThat(System.nanoTime()).isLessThan(deadline);
            Thread.onSpinWait();
        }
    }

    private EmbeddedChannel wireChannel() {
        app.start();
        var channel = new EmbeddedChannel();
        channel.freezeTime();
        connection = new HttpConnection(app, executor);
        channel.pipeline().addLast(new RequestDecoder(NettyServer.decoderConfig()), new HttpResponseEncoder(), connection);
        return channel;
    }

    private static ByteBuf ascii(String text) { return Unpooled.copiedBuffer(text, StandardCharsets.US_ASCII); }

    /** Runs tasks posted by handler threads and collects the output until the channel closes. */
    static List<Reply> repliesUntilClosed(EmbeddedChannel channel) {
        var bytes = new java.io.ByteArrayOutputStream();
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        while (true) {
            channel.runPendingTasks();
            for (ByteBuf buffer; (buffer = channel.readOutbound()) != null;) {
                var chunk = new byte[buffer.readableBytes()];
                buffer.readBytes(chunk);
                buffer.release();
                bytes.writeBytes(chunk);
            }
            if (!channel.isActive()) { break; }
            assertThat(System.nanoTime()).as("connection still open").isLessThan(deadline);
            Thread.onSpinWait();
        }
        return parse(bytes.toString(StandardCharsets.ISO_8859_1));
    }

    static List<Reply> parse(String text) {
        var replies = new ArrayList<Reply>();
        int at = 0;
        while (at < text.length()) {
            int end = text.indexOf("\r\n\r\n", at);
            var lines = text.substring(at, end).split("\r\n");
            Map<String, String> headers = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
            for (int i = 1; i < lines.length; i++) {
                int colon = lines[i].indexOf(':');
                headers.put(lines[i].substring(0, colon), lines[i].substring(colon + 1).trim());
            }
            int length = Integer.parseInt(headers.getOrDefault("Content-Length", "0"));
            var body = text.substring(end + 4, end + 4 + length).getBytes(StandardCharsets.ISO_8859_1);
            replies.add(new Reply(Integer.parseInt(lines[0].split(" ")[1]), headers, body));
            at = end + 4 + length;
        }
        return replies;
    }
}
