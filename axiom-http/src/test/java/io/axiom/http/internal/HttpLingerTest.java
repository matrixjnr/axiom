package io.axiom.http.internal;

import static org.assertj.core.api.Assertions.assertThat;

import io.axiom.Axiom;
import io.axiom.application.Application;
import io.axiom.server.internal.execution.RequestDispatcher;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelPromise;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.channel.socket.DuplexChannel;
import io.netty.handler.codec.http.HttpResponseEncoder;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** After a listener error response the connection half-closes and discards input for a bounded time. */
class HttpLingerTest {
    private final RequestDispatcher executor = new RequestDispatcher(4);
    private final Application app = Axiom.create();

    @AfterEach void stop() throws Exception {
        app.close();
        executor.close();
        executor.termination().toCompletableFuture().get(5, TimeUnit.SECONDS);
    }

    /** An embedded channel that, like a socket, can shut down its output alone. */
    private static final class Socket extends EmbeddedChannel implements DuplexChannel {
        boolean outputShutdown;
        @Override public boolean isInputShutdown() { return !isActive(); }
        @Override public ChannelFuture shutdownInput() { return shutdownInput(newPromise()); }
        @Override public ChannelFuture shutdownInput(ChannelPromise promise) { return promise.setSuccess(); }
        @Override public boolean isOutputShutdown() { return outputShutdown || !isActive(); }
        @Override public ChannelFuture shutdownOutput() { return shutdownOutput(newPromise()); }
        @Override public ChannelFuture shutdownOutput(ChannelPromise promise) { outputShutdown = true; return promise.setSuccess(); }
        @Override public boolean isShutdown() { return isInputShutdown() && isOutputShutdown(); }
        @Override public ChannelFuture shutdown() { return shutdown(newPromise()); }
        @Override public ChannelFuture shutdown(ChannelPromise promise) { outputShutdown = true; return promise.setSuccess(); }
    }

    private Socket socket() {
        app.maxRequestBody(16);
        app.start();
        var channel = new Socket();
        channel.freezeTime();
        channel.pipeline().addLast(new RequestDecoder(NettyServer.decoderConfig()), new HttpResponseEncoder(),
                new HttpConnection(app, executor));
        return channel;
    }

    @Test void errorResponseHalfClosesAndDiscardsInputUntilTheLingerTimeout() {
        app.post("/", ctx -> { throw new AssertionError("must not execute"); });
        var channel = socket();
        try {
            channel.writeInbound(ascii("POST / HTTP/1.1\r\nHost: a\r\nContent-Length: 17\r\n\r\n0123456789"));
            assertThat(outbound(channel)).startsWith("HTTP/1.1 413 ").contains("connection: close");
            assertThat(channel.outputShutdown).isTrue();
            assertThat(channel.isActive()).isTrue();
            // The rest of the body, and anything after it, is released unread.
            var rest = ascii("0123456POST / HTTP/1.1\r\nHost: a\r\nContent-Length: 0\r\n\r\n");
            channel.writeInbound(rest);
            assertThat(rest.refCnt()).isZero();
            assertThat(channel.<Object>readOutbound()).isNull();
            channel.advanceTimeBy(HttpConnection.LINGER_TIMEOUT.toMillis() - 1, TimeUnit.MILLISECONDS);
            channel.runScheduledPendingTasks();
            assertThat(channel.isActive()).isTrue();
            channel.advanceTimeBy(1, TimeUnit.MILLISECONDS);
            channel.runScheduledPendingTasks();
            assertThat(channel.isActive()).isFalse();
        } finally { channel.finishAndReleaseAll(); }
    }

    @Test void lingeringStopsOnceTheDiscardLimitIsExceeded() {
        var channel = socket();
        try {
            channel.writeInbound(ascii("GARBAGE\r\n\r\n"));
            assertThat(outbound(channel)).startsWith("HTTP/1.1 400 ");
            var piece = new byte[1024 * 1024];
            for (int sent = 0; sent < HttpConnection.MAX_DISCARDED_INPUT; sent += piece.length) {
                channel.writeInbound(Unpooled.wrappedBuffer(piece));
                assertThat(channel.isActive()).isTrue();
            }
            channel.writeInbound(Unpooled.wrappedBuffer(new byte[1]));
            assertThat(channel.isActive()).isFalse();
        } finally { channel.finishAndReleaseAll(); }
    }

    @Test void inactivityEndsLingering() {
        var channel = socket();
        try {
            channel.writeInbound(ascii("GARBAGE\r\n\r\n"));
            assertThat(outbound(channel)).startsWith("HTTP/1.1 400 ");
            assertThat(channel.isActive()).isTrue();
            channel.pipeline().fireUserEventTriggered(io.netty.handler.timeout.IdleStateEvent.ALL_IDLE_STATE_EVENT);
            assertThat(channel.isActive()).isFalse();
        } finally { channel.finishAndReleaseAll(); }
    }

    @Test void pipelinedErrorLingersAfterTheEarlierResponses() throws Exception {
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        app.get("/slow", ctx -> { entered.countDown(); release.await(); return "slow"; });
        var channel = socket();
        try {
            channel.writeInbound(ascii("GET /slow HTTP/1.1\r\nHost: a\r\n\r\nGARBAGE\r\n\r\n"));
            assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
            channel.writeInbound(Unpooled.wrappedBuffer(new byte[1024 * 1024]));
            release.countDown();
            var text = new StringBuilder();
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (!channel.outputShutdown) {
                assertThat(System.nanoTime()).isLessThan(deadline);
                channel.runPendingTasks();
                text.append(outbound(channel));
                Thread.onSpinWait();
            }
            text.append(outbound(channel));
            assertThat(text.toString()).startsWith("HTTP/1.1 200 OK").contains("\r\n\r\nslowHTTP/1.1 400 ");
            assertThat(channel.isActive()).isTrue();
            channel.advanceTimeBy(HttpConnection.LINGER_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
            channel.runScheduledPendingTasks();
            assertThat(channel.isActive()).isFalse();
        } finally {
            release.countDown();
            channel.finishAndReleaseAll();
        }
    }

    @Test void responsesThatAreNotListenerErrorsCloseWithoutLingering() throws Exception {
        app.get("/", ctx -> "bye");
        var channel = socket();
        try {
            channel.writeInbound(ascii("GET / HTTP/1.1\r\nHost: a\r\nConnection: close\r\n\r\n"));
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (channel.isActive()) {
                assertThat(System.nanoTime()).isLessThan(deadline);
                channel.runPendingTasks();
                Thread.onSpinWait();
            }
            assertThat(outbound(channel)).startsWith("HTTP/1.1 200 OK").endsWith("bye");
            assertThat(channel.outputShutdown).isFalse();
        } finally { channel.finishAndReleaseAll(); }
    }

    private static ByteBuf ascii(String text) { return Unpooled.copiedBuffer(text, StandardCharsets.US_ASCII); }

    private static String outbound(EmbeddedChannel channel) {
        var text = new StringBuilder();
        for (ByteBuf buffer; (buffer = channel.readOutbound()) != null;) {
            text.append(buffer.toString(StandardCharsets.US_ASCII));
            buffer.release();
        }
        return text.toString();
    }
}
