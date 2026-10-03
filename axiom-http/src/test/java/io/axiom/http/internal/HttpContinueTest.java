package io.axiom.http.internal;

import static org.assertj.core.api.Assertions.assertThat;

import io.axiom.Axiom;
import io.axiom.application.Application;
import io.axiom.server.internal.execution.RequestDispatcher;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.http.HttpResponseEncoder;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** {@code Expect: 100-continue} on a request pipelined behind a response that is still outstanding. */
class HttpContinueTest {
    private static final String SLOW = "GET /slow HTTP/1.1\r\nHost: a\r\n\r\n";
    private final CountDownLatch entered = new CountDownLatch(1);
    private final CountDownLatch release = new CountDownLatch(1);
    private final AtomicBoolean echoed = new AtomicBoolean();
    private final RequestDispatcher executor = new RequestDispatcher(4);
    private final Application app = Axiom.create();
    private EmbeddedChannel channel;

    @BeforeEach void start() {
        app.maxRequestBody(16);
        app.get("/slow", ctx -> { entered.countDown(); release.await(); return "slow"; });
        app.post("/echo", ctx -> {
            echoed.set(true);
            return "echo:" + new String(ctx.request().body().bytes(), StandardCharsets.UTF_8);
        });
        app.start();
        channel = new EmbeddedChannel();
        channel.pipeline().addLast(new RequestDecoder(NettyServer.decoderConfig()), new HttpResponseEncoder(),
                new HttpConnection(app, executor));
    }

    @AfterEach void stop() throws Exception {
        release.countDown();
        channel.finishAndReleaseAll();
        app.close();
        executor.close();
        executor.termination().toCompletableFuture().get(30, TimeUnit.SECONDS);
    }

    @Test void interimResponseWaitsForTheEarlierResponse() throws Exception {
        channel.writeInbound(ascii(SLOW + "POST /echo HTTP/1.1\r\nHost: a\r\nExpect: 100-continue\r\n"
                + "Content-Length: 5\r\nConnection: close\r\n\r\n"));
        assertThat(entered.await(30, TimeUnit.SECONDS)).isTrue();
        channel.runPendingTasks();
        // No 100 Continue ahead of the earlier final response.
        assertThat(channel.<Object>readOutbound()).isNull();
        release.countDown();
        var text = new StringBuilder();
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        while (!text.toString().contains("HTTP/1.1 100 Continue\r\n\r\n")) {
            assertThat(System.nanoTime()).isLessThan(deadline);
            channel.runPendingTasks();
            text.append(outbound(channel));
            Thread.onSpinWait();
        }
        assertThat(echoed).isFalse();
        channel.writeInbound(ascii("hello"));
        var replies = HttpPipelineErrorTest.parse(text + outboundUntilClosed());
        assertThat(replies).extracting(Reply::status).containsExactly(200, 100, 200);
        assertThat(replies.get(0).text()).isEqualTo("slow");
        assertThat(replies.get(2).text()).isEqualTo("echo:hello");
    }

    @Test void bodySentWithoutWaitingCancelsTheDeferredInterimResponse() throws Exception {
        channel.writeInbound(ascii(SLOW + "POST /echo HTTP/1.1\r\nHost: a\r\nExpect: 100-continue\r\n"
                + "Content-Length: 5\r\nConnection: close\r\n\r\nhello"));
        assertThat(entered.await(30, TimeUnit.SECONDS)).isTrue();
        release.countDown();
        var replies = HttpPipelineErrorTest.parse(outboundUntilClosed());
        assertThat(replies).extracting(Reply::status).containsExactly(200, 200);
        assertThat(replies.get(1).text()).isEqualTo("echo:hello");
    }

    @Test void oversizedDeclarationIsRejectedAfterTheEarlierResponseWithoutAnInterimResponse() throws Exception {
        assertRejectedAfterEarlierResponse("POST /echo HTTP/1.1\r\nHost: a\r\nExpect: 100-continue\r\nContent-Length: 17\r\n\r\n", 413);
    }

    @Test void unsupportedExpectationIsRejectedAfterTheEarlierResponse() throws Exception {
        assertRejectedAfterEarlierResponse("POST /echo HTTP/1.1\r\nHost: a\r\nExpect: 100-continue, x\r\nContent-Length: 5\r\n\r\n", 417);
    }

    private void assertRejectedAfterEarlierResponse(String request, int status) throws Exception {
        channel.writeInbound(ascii(SLOW + request));
        assertThat(entered.await(30, TimeUnit.SECONDS)).isTrue();
        channel.runPendingTasks();
        assertThat(channel.<Object>readOutbound()).isNull();
        release.countDown();
        var replies = HttpPipelineErrorTest.parse(outboundUntilClosed());
        assertThat(replies).extracting(Reply::status).containsExactly(200, status);
        assertThat(replies.get(1).headers()).containsEntry("connection", "close");
        assertThat(echoed).isFalse();
    }

    private String outboundUntilClosed() {
        var text = new StringBuilder();
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        while (channel.isActive()) {
            assertThat(System.nanoTime()).isLessThan(deadline);
            channel.runPendingTasks();
            text.append(outbound(channel));
            Thread.onSpinWait();
        }
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
