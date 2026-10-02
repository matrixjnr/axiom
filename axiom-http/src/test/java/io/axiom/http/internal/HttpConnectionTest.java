package io.axiom.http.internal;

import static org.assertj.core.api.Assertions.assertThat;

import io.axiom.Axiom;
import io.axiom.execution.ExecutionContext;
import io.axiom.server.internal.execution.RequestDispatcher;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.http.DefaultHttpRequest;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.handler.codec.http.LastHttpContent;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class HttpConnectionTest {
    @Test void saturatedExecutorReturns503WithoutRunningHandler() throws Exception {
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var executor = executor();
        try (var app = Axiom.create()) {
            executor.submit(ExecutionContext.create(Duration.ofSeconds(10)), () -> {
                entered.countDown();
                try { release.await(); } catch (InterruptedException expected) { Thread.currentThread().interrupt(); }
                return null;
            });
            assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();

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
            executor.close();
            executor.termination().toCompletableFuture().get(5, TimeUnit.SECONDS);
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
            executor.close();
            executor.termination().toCompletableFuture().get(5, TimeUnit.SECONDS);
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
            executor.close();
            executor.termination().toCompletableFuture().get(5, TimeUnit.SECONDS);
        }
    }

    @Test void responseBodiesUseTheChannelAllocator() throws Exception {
        var executor = executor();
        try (var app = Axiom.create()) {
            app.get("/", ctx -> "body");
            app.start();
            var channel = new EmbeddedChannel(new HttpConnection(app, executor));
            var allocator = new io.netty.buffer.UnpooledByteBufAllocator(false);
            channel.config().setAllocator(allocator);
            try {
                request(channel);
                FullHttpResponse response = awaitResponse(channel);
                try {
                    assertThat(response.content().toString(java.nio.charset.StandardCharsets.UTF_8)).isEqualTo("body");
                    assertThat(response.content().alloc()).isSameAs(allocator);
                } finally { response.release(); }
            } finally { channel.finishAndReleaseAll(); }
        } finally {
            executor.close();
            executor.termination().toCompletableFuture().get(5, TimeUnit.SECONDS);
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
        } finally { executor.close(); }
    }
    @Test void idleEventDoesNotAbortRunningHandler() throws Exception {
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var executor = executor();
        try (var app = Axiom.create()) {
            app.get("/", ctx -> { entered.countDown(); release.await(); return "done"; });
            app.start();
            var channel = new EmbeddedChannel(new HttpConnection(app, executor));
            try {
                request(channel);
                assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
                channel.pipeline().fireUserEventTriggered(io.netty.handler.timeout.IdleStateEvent.ALL_IDLE_STATE_EVENT);
                assertThat(channel.isActive()).isTrue();
                release.countDown();
                FullHttpResponse response = awaitResponse(channel);
                try { assertThat(response.status().code()).isEqualTo(200); }
                finally { response.release(); }
                channel.pipeline().fireUserEventTriggered(io.netty.handler.timeout.IdleStateEvent.ALL_IDLE_STATE_EVENT);
                assertThat(channel.isActive()).isFalse();
            } finally { channel.finishAndReleaseAll(); }
        } finally {
            release.countDown();
            executor.close();
            executor.termination().toCompletableFuture().get(5, TimeUnit.SECONDS);
        }
    }

    @Test void incompleteRequestHeadTimesOutWith408EvenWhenBytesTrickle() {
        var executor = executor();
        try (var app = Axiom.create()) {
            app.get("/", ctx -> "ok");
            app.start();
            var timeout = Duration.ofSeconds(10);
            var channel = wireChannel(app, executor, timeout);
            try {
                channel.writeInbound(ascii("GET / HTTP/1.1\r\n"));
                for (int i = 0; i < 9; i++) {
                    channel.advanceTimeBy(1, TimeUnit.SECONDS);
                    channel.runScheduledPendingTasks();
                    channel.writeInbound(ascii("X"));
                    assertThat(channel.isActive()).isTrue();
                }
                channel.advanceTimeBy(1, TimeUnit.SECONDS);
                channel.runScheduledPendingTasks();
                assertThat(outbound(channel)).startsWith("HTTP/1.1 408 Request Timeout");
                assertThat(channel.isActive()).isFalse();
            } finally { channel.finishAndReleaseAll(); }
        } finally { executor.close(); }
    }

    @Test void completedRequestHeadCancelsTheReadDeadline() throws Exception {
        var executor = executor();
        try (var app = Axiom.create()) {
            app.get("/", ctx -> "ok");
            app.start();
            var channel = wireChannel(app, executor, Duration.ofSeconds(10));
            try {
                channel.writeInbound(ascii("GET / HTTP/1.1\r\nHost: a\r\n\r\n"));
                assertThat(awaitOutbound(channel)).startsWith("HTTP/1.1 200 OK");
                channel.advanceTimeBy(1, TimeUnit.MINUTES);
                channel.runScheduledPendingTasks();
                assertThat(channel.isActive()).isTrue();
                assertThat(channel.<Object>readOutbound()).isNull();
            } finally { channel.finishAndReleaseAll(); }
        } finally { executor.close(); }
    }

    private static EmbeddedChannel wireChannel(io.axiom.application.Application app, RequestDispatcher executor,
            Duration headTimeout) {
        var channel = new EmbeddedChannel();
        channel.freezeTime();
        channel.pipeline().addLast(new HttpConnection.RequestBytes(),
                new io.netty.handler.codec.http.HttpServerCodec(),
                new HttpConnection(app, executor, headTimeout));
        return channel;
    }

    private static io.netty.buffer.ByteBuf ascii(String text) {
        return io.netty.buffer.Unpooled.copiedBuffer(text, java.nio.charset.StandardCharsets.US_ASCII);
    }

    private static String outbound(EmbeddedChannel channel) {
        var text = new StringBuilder();
        for (io.netty.buffer.ByteBuf buffer; (buffer = channel.readOutbound()) != null;) {
            text.append(buffer.toString(java.nio.charset.StandardCharsets.US_ASCII));
            buffer.release();
        }
        return text.toString();
    }

    private static String awaitOutbound(EmbeddedChannel channel) throws InterruptedException {
        io.netty.buffer.ByteBuf first = awaitResponse(channel);
        try { return first.toString(java.nio.charset.StandardCharsets.US_ASCII) + outbound(channel); }
        finally { first.release(); }
    }

    @Test void pipelinedRequestAfterApplicationCloseAnswers503() throws Exception {
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var executor = executor();
        var app = Axiom.create();
        try {
            app.get("/", ctx -> { entered.countDown(); release.await(); return "first"; });
            app.start();
            var channel = new EmbeddedChannel(new HttpConnection(app, executor));
            try {
                request(channel);
                request(channel);
                assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
                app.close();
                release.countDown();
                assertResponses(channel, 200, 503);
            } finally { channel.finishAndReleaseAll(); }
        } finally {
            app.close();
            release.countDown();
            executor.close();
            executor.termination().toCompletableFuture().get(5, TimeUnit.SECONDS);
        }
    }

    @Test void dispatchFailureFromWriteListenerAnswers503() throws Exception {
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var failing = new java.util.concurrent.atomic.AtomicBoolean();
        var executor = executor();
        try (var real = Axiom.create()) {
            real.get("/", ctx -> { entered.countDown(); release.await(); return "first"; });
            real.start();
            var app = failingAfter(real, failing);
            var channel = new EmbeddedChannel(new HttpConnection(app, executor));
            try {
                request(channel);
                request(channel);
                assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
                failing.set(true);
                release.countDown();
                assertResponses(channel, 200, 503);
            } finally { channel.finishAndReleaseAll(); }
        } finally {
            release.countDown();
            executor.close();
            executor.termination().toCompletableFuture().get(5, TimeUnit.SECONDS);
        }
    }

    private static void assertResponses(EmbeddedChannel channel, int first, int second) throws InterruptedException {
        FullHttpResponse response = awaitResponse(channel);
        try { assertThat(response.status().code()).isEqualTo(first); } finally { response.release(); }
        response = awaitResponse(channel);
        try {
            assertThat(response.status().code()).isEqualTo(second);
            assertThat(response.headers().get("Connection")).isEqualTo("close");
        } finally { response.release(); }
        assertThat(channel.isActive()).isFalse();
    }

    /** Delegates to a running application, but every lifecycle query fails once {@code failing} is set. */
    private static io.axiom.application.Application failingAfter(io.axiom.application.Application app,
            java.util.concurrent.atomic.AtomicBoolean failing) {
        return (io.axiom.application.Application) java.lang.reflect.Proxy.newProxyInstance(
                HttpConnectionTest.class.getClassLoader(), new Class<?>[] {io.axiom.application.Application.class},
                (proxy, method, args) -> {
                    if (failing.get() && !method.getName().equals("requestTimeout")) {
                        throw new IllegalStateException("broken application");
                    }
                    try { return method.invoke(app, args); }
                    catch (java.lang.reflect.InvocationTargetException failure) { throw failure.getCause(); }
                });
    }

    /** Runs tasks posted from handler threads until a response is written; bounded by the handler's progress. */
    private static <T> T awaitResponse(EmbeddedChannel channel) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < deadline) {
            channel.runPendingTasks();
            T message = channel.readOutbound();
            if (message != null) { return message; }
            Thread.onSpinWait();
        }
        throw new AssertionError("No response written");
    }

    private static void request(EmbeddedChannel channel) {
        var request = new DefaultHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET, "/");
        request.headers().set("Host", "localhost");
        channel.writeInbound(request, LastHttpContent.EMPTY_LAST_CONTENT);
    }

    private static RequestDispatcher executor() {
        return new RequestDispatcher(1);
    }
}
