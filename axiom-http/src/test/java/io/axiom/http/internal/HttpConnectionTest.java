package io.axiom.http.internal;

import static org.assertj.core.api.Assertions.assertThat;

import io.axiom.Axiom;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.http.DefaultHttpRequest;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.handler.codec.http.LastHttpContent;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class HttpConnectionTest {
    @Test void saturatedExecutorReturns503WithoutRunningHandler() throws Exception {
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var executor = executor();
        try (var app = Axiom.create()) {
            executor.execute(() -> {
                entered.countDown();
                try { release.await(); } catch (InterruptedException expected) { Thread.currentThread().interrupt(); }
            });
            assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
            executor.execute(() -> { }); // Fill the only queue slot.
            app.get("/", ctx -> { throw new AssertionError("Rejected request must not execute"); });
            app.start();
            var channel = new EmbeddedChannel(new HttpConnection(app, executor));
            try {
                request(channel);
                FullHttpResponse response = channel.readOutbound();
                try {
                    assertThat(response.status().code()).isEqualTo(503);
                    assertThat(response.headers().get("Connection")).isEqualTo("close");
                } finally { response.release(); }
                assertThat(channel.isActive()).isFalse();
            } finally { channel.finishAndReleaseAll(); }
        } finally {
            release.countDown();
            executor.shutdownNow();
            assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test void pipelineOverflowClosesAndInterruptsActiveHandlerWithoutOutOfOrderResponse() throws Exception {
        var entered = new CountDownLatch(1);
        var interrupted = new CountDownLatch(1);
        var executor = executor();
        try (var app = Axiom.create()) {
            app.get("/", ctx -> {
                entered.countDown();
                try { new CountDownLatch(1).await(); }
                catch (InterruptedException expected) { interrupted.countDown(); Thread.currentThread().interrupt(); }
                return "late";
            });
            app.start();
            var channel = new EmbeddedChannel(new HttpConnection(app, executor));
            try {
                request(channel);
                assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
                for (int i = 0; i < 8; i++) { request(channel); }
                assertThat(channel.isActive()).isFalse();
                assertThat(interrupted.await(5, TimeUnit.SECONDS)).isTrue();
                assertThat(channel.<Object>readOutbound()).isNull();
            } finally { channel.finishAndReleaseAll(); }
        } finally {
            executor.shutdownNow();
            assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test void malformedPipelineClosesInsteadOfOvertakingEarlierResponse() throws Exception {
        var entered = new CountDownLatch(1);
        var executor = executor();
        try (var app = Axiom.create()) {
            app.get("/", ctx -> { entered.countDown(); new CountDownLatch(1).await(); return "never"; });
            app.start();
            var channel = new EmbeddedChannel(new HttpConnection(app, executor));
            try {
                request(channel);
                assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
                channel.writeInbound(new DefaultHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET, "/"));
                assertThat(channel.isActive()).isFalse();
                assertThat(channel.<Object>readOutbound()).isNull();
            } finally { channel.finishAndReleaseAll(); }
        } finally {
            executor.shutdownNow();
            assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test void idleConnectionsClose() {
        var executor = executor();
        try (var app = Axiom.create()) {
            var channel = new EmbeddedChannel(new HttpConnection(app, executor));
            try {
                channel.pipeline().fireUserEventTriggered(io.netty.handler.timeout.IdleStateEvent.ALL_IDLE_STATE_EVENT);
                assertThat(channel.isActive()).isFalse();
            } finally { channel.finishAndReleaseAll(); }
        } finally { executor.shutdownNow(); }
    }
    private static void request(EmbeddedChannel channel) {
        var request = new DefaultHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET, "/");
        request.headers().set("Host", "localhost");
        channel.writeInbound(request, LastHttpContent.EMPTY_LAST_CONTENT);
    }

    private static ThreadPoolExecutor executor() {
        return new ThreadPoolExecutor(1, 1, 0, TimeUnit.SECONDS, new ArrayBlockingQueue<>(1));
    }
}
