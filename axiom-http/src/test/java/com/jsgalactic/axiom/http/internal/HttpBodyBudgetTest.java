package com.jsgalactic.axiom.http.internal;

import static org.assertj.core.api.Assertions.assertThat;

import com.jsgalactic.axiom.Axiom;
import com.jsgalactic.axiom.application.Application;
import com.jsgalactic.axiom.server.internal.execution.RequestDispatcher;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.http.HttpResponseEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The listener-wide budget of request body bytes: bodies are reserved when they start, refused with
 * 503 when they do not fit, and returned on every way a request can end. Connections share one
 * budget exactly as the connections of a listener do.
 */
class HttpBodyBudgetTest {
    private static final String HEAD = "POST /echo HTTP/1.1\r\nHost: a\r\n";
    private final CountDownLatch release = new CountDownLatch(1);
    private final CountDownLatch entered = new CountDownLatch(1);
    private final BodyBudget budget = new BodyBudget(100);
    private final RequestDispatcher executor = new RequestDispatcher(8);
    private final Application app = Axiom.create();
    private final List<EmbeddedChannel> channels = new ArrayList<>();

    @BeforeEach void start() {
        app.maxRequestBody(64);
        app.post("/echo", ctx -> {
            entered.countDown();
            release.await();
            return "len:" + ctx.request().body().length();
        });
        app.start();
    }

    @AfterEach void stop() throws Exception {
        release.countDown();
        channels.forEach(EmbeddedChannel::finishAndReleaseAll);
        app.close();
        executor.close();
        executor.termination().toCompletableFuture().get(30, TimeUnit.SECONDS);
    }

    private EmbeddedChannel connect() {
        return connect(executor);
    }

    private EmbeddedChannel connect(RequestDispatcher dispatcher) {
        var channel = new EmbeddedChannel();
        channel.pipeline().addLast(new RequestDecoder(NettyServer.decoderConfig()), new HttpResponseEncoder(),
                new HttpConnection(app, dispatcher, TransportSettings.DEFAULTS, null, () -> false, budget));
        channels.add(channel);
        return channel;
    }

    @Test void aDeclaredBodyIsReservedInFullAndOneThatDoesNotFitIsAnswered503() throws Exception {
        var first = connect();
        first.writeInbound(ascii(HEAD + "Content-Length: 60\r\n\r\n"));
        assertThat(budget.used()).as("reserved when the head arrives, before any body byte").isEqualTo(60);
        var second = connect();
        second.writeInbound(ascii(HEAD + "Content-Length: 50\r\n\r\n"));
        var refused = awaitClosed(second);
        assertThat(refused).startsWith("HTTP/1.1 503 ").contains("connection: close");
        assertThat(budget.used()).as("the refusal reserved nothing").isEqualTo(60);
        // The first upload completes, runs and finishes: its share comes back with the response.
        first.writeInbound(ascii("x".repeat(60)));
        assertThat(entered.await(30, TimeUnit.SECONDS)).isTrue();
        assertThat(budget.used()).as("held while the handler runs").isEqualTo(60);
        release.countDown();
        assertThat(awaitOutput(first, "len:60")).startsWith("HTTP/1.1 200 ");
        assertThat(budget.used()).isZero();
        // The released share is available again.
        var third = connect();
        third.writeInbound(ascii(HEAD + "Content-Length: 50\r\n\r\n"));
        assertThat(budget.used()).isEqualTo(50);
    }

    @Test void aChunkedBodyTakesItsShareAsItArrivesAndKeepsOnlyWhatItHolds() throws Exception {
        var first = connect();
        first.writeInbound(ascii(HEAD + "Transfer-Encoding: chunked\r\n\r\n5\r\nhello\r\n"));
        // The array is allocated up to the body limit (64), which is what is reserved.
        assertThat(budget.used()).isEqualTo(64);
        var second = connect();
        second.writeInbound(ascii(HEAD + "Transfer-Encoding: chunked\r\n\r\n5\r\nhello\r\n"));
        assertThat(awaitClosed(second)).startsWith("HTTP/1.1 503 ");
        assertThat(budget.used()).isEqualTo(64);
        first.writeInbound(ascii("0\r\n\r\n"));
        assertThat(entered.await(30, TimeUnit.SECONDS)).isTrue();
        assertThat(budget.used()).as("trimmed to the five bytes the request holds").isEqualTo(5);
        release.countDown();
        assertThat(awaitOutput(first, "len:5")).startsWith("HTTP/1.1 200 ");
        assertThat(budget.used()).isZero();
    }

    @Test void pipelinedBodiesHoldTheirShareUntilTheirOwnResponse() throws Exception {
        var first = connect();
        first.writeInbound(ascii(HEAD + "Content-Length: 30\r\n\r\n" + "a".repeat(30)
                + HEAD + "Content-Length: 30\r\n\r\n" + "b".repeat(30)));
        assertThat(entered.await(30, TimeUnit.SECONDS)).isTrue();
        assertThat(budget.used()).as("running body plus the buffered pipelined body").isEqualTo(60);
        var other = connect();
        other.writeInbound(ascii(HEAD + "Content-Length: 50\r\n\r\n"));
        assertThat(awaitClosed(other)).startsWith("HTTP/1.1 503 ");
        release.countDown();
        assertThat(awaitOutput(first, "len:30")).startsWith("HTTP/1.1 200 ");
        assertThat(await(first, () -> budget.used() == 0)).isTrue();
    }

    @Test void aConnectionThatEndsMidBodyReturnsItsShare() {
        var channel = connect();
        channel.writeInbound(ascii(HEAD + "Content-Length: 60\r\n\r\n" + "x".repeat(10)));
        assertThat(budget.used()).isEqualTo(60);
        channel.close();
        assertThat(budget.used()).isZero();
    }

    @Test void aRejectedOrMalformedRequestReturnsItsShare() {
        var tooLarge = connect();
        tooLarge.writeInbound(ascii(HEAD + "Content-Length: 65\r\n\r\n"));
        assertThat(budget.used()).as("rejected before any reservation").isZero();
        var overrun = connect();
        overrun.writeInbound(ascii(HEAD + "Transfer-Encoding: chunked\r\n\r\n41\r\n" + "x".repeat(65) + "\r\n"));
        assertThat(budget.used()).as("413 while the chunked body grew").isZero();
        var malformed = connect();
        malformed.writeInbound(ascii(HEAD + "Content-Length: 20\r\n\r\n" + "x".repeat(5) + "GARBAGE"));
        malformed.close();
        assertThat(budget.used()).isZero();
    }

    @Test void queuedRequestsAreReturnedWhenTheirConnectionClosesAndTheRunningOneWhenItIsCancelled() throws Exception {
        var channel = connect();
        channel.writeInbound(ascii(HEAD + "Content-Length: 10\r\n\r\n" + "a".repeat(10)
                + HEAD + "Content-Length: 10\r\n\r\n" + "b".repeat(10)));
        assertThat(entered.await(30, TimeUnit.SECONDS)).isTrue();
        assertThat(budget.used()).isEqualTo(20);
        channel.close();
        assertThat(await(channel, () -> budget.used() == 0)).isTrue();
    }

    @Test void aRequestTheDispatcherRefusesReturnsItsShare() throws Exception {
        try (var tiny = new RequestDispatcher(1)) {
            var running = connect(tiny);
            running.writeInbound(ascii(HEAD + "Content-Length: 10\r\n\r\n" + "a".repeat(10)));
            assertThat(entered.await(30, TimeUnit.SECONDS)).isTrue();
            var refused = connect(tiny);
            refused.writeInbound(ascii(HEAD + "Content-Length: 10\r\n\r\n" + "b".repeat(10)));
            assertThat(awaitClosed(refused)).startsWith("HTTP/1.1 503 ");
            assertThat(await(refused, () -> budget.used() == 10)).as("only the running request holds a share").isTrue();
            release.countDown();
            assertThat(await(running, () -> budget.used() == 0)).isTrue();
        }
    }

    @Test void budgetReservationsReturnExactlyOnce() {
        var reservation = budget.reservation();
        assertThat(reservation.grow(70)).isTrue();
        assertThat(reservation.grow(31)).as("over the limit changes nothing").isFalse();
        assertThat(budget.used()).isEqualTo(70);
        reservation.shrinkTo(80);
        assertThat(budget.used()).as("shrinking never grows").isEqualTo(70);
        reservation.shrinkTo(20);
        assertThat(budget.used()).isEqualTo(20);
        reservation.release();
        reservation.release();
        assertThat(budget.used()).isZero();
        assertThat(BodyBudget.unlimited().reservation().grow(Long.MAX_VALUE / 2)).isTrue();
    }

    /** Runs the channel's tasks until the condition holds; false when it never does within 30 seconds. */
    private static boolean await(EmbeddedChannel channel, BooleanSupplier condition) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline) { return false; }
            channel.runPendingTasks();
            Thread.onSpinWait();
        }
        return true;
    }

    /** Collects the output until it contains the text. */
    private static String awaitOutput(EmbeddedChannel channel, String expected) {
        var text = new StringBuilder();
        assertThat(await(channel, () -> { text.append(outbound(channel)); return text.toString().contains(expected); }))
                .as("output contains " + expected).isTrue();
        return text.toString();
    }

    /** Collects the output until the connection has closed. */
    private static String awaitClosed(EmbeddedChannel channel) {
        var text = new StringBuilder();
        assertThat(await(channel, () -> { text.append(outbound(channel)); return !channel.isActive(); })).isTrue();
        return text.append(outbound(channel)).toString();
    }

    private static ByteBuf ascii(String text) { return Unpooled.copiedBuffer(text, StandardCharsets.US_ASCII); }

    private static String outbound(EmbeddedChannel channel) {
        var text = new StringBuilder();
        for (ByteBuf buffer; (buffer = channel.readOutbound()) != null;) {
            text.append(buffer.toString(StandardCharsets.ISO_8859_1));
            buffer.release();
        }
        return text.toString();
    }
}
