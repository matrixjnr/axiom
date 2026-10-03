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
            assertThat(entered.await(30, TimeUnit.SECONDS)).isTrue();

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
            executor.termination().toCompletableFuture().get(30, TimeUnit.SECONDS);
        }
    }

    @Test void pipelineOverflowIsAnswered503AfterEarlierResponsesWithoutInterruptingTheHandler() throws Exception {
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var interrupted = new java.util.concurrent.atomic.AtomicBoolean();
        var executor = executor();
        try (var app = Axiom.create()) {
            app.get("/", ctx -> {
                if (entered.getCount() == 0) { return "queued"; }
                entered.countDown();
                try { release.await(); }
                catch (InterruptedException cancelled) { interrupted.set(true); throw cancelled; }
                return "first";
            });
            app.start();
            var channel = new EmbeddedChannel(new HttpConnection(app, executor));
            try {
                request(channel);
                assertThat(entered.await(30, TimeUnit.SECONDS)).isTrue();
                for (int i = 0; i < HttpConnection.MAX_PIPELINED; i++) { request(channel); }
                assertThat(channel.isActive()).isTrue();
                assertThat(channel.<Object>readOutbound()).isNull();
                release.countDown();
                for (int i = 0; i < HttpConnection.MAX_PIPELINED; i++) {
                    FullHttpResponse response = awaitResponse(channel);
                    try {
                        assertThat(response.status().code()).isEqualTo(200);
                        assertThat(response.content().toString(java.nio.charset.StandardCharsets.UTF_8))
                                .isEqualTo(i == 0 ? "first" : "queued");
                    } finally { response.release(); }
                }
                FullHttpResponse refused = awaitResponse(channel);
                try {
                    assertThat(refused.status().code()).isEqualTo(503);
                    assertThat(refused.headers().get("Connection")).isEqualTo("close");
                } finally { refused.release(); }
                assertThat(channel.isActive()).isFalse();
                assertThat(interrupted).isFalse();
            } finally { channel.finishAndReleaseAll(); }
        } finally {
            release.countDown();
            executor.close();
            executor.termination().toCompletableFuture().get(30, TimeUnit.SECONDS);
        }
    }

    @Test void malformedPipelinedRequestIsAnsweredAfterTheEarlierResponse() throws Exception {
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var executor = executor();
        try (var app = Axiom.create()) {
            app.get("/", ctx -> { entered.countDown(); release.await(); return "first"; });
            app.start();
            var channel = new EmbeddedChannel(new HttpConnection(app, executor));
            try {
                request(channel);
                assertThat(entered.await(30, TimeUnit.SECONDS)).isTrue();
                // No Host header: rejected, but only after the earlier response.
                channel.writeInbound(new DefaultHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET, "/"));
                assertThat(channel.isActive()).isTrue();
                assertThat(channel.<Object>readOutbound()).isNull();
                release.countDown();
                assertResponses(channel, 200, 400);
            } finally { channel.finishAndReleaseAll(); }
        } finally {
            release.countDown();
            executor.close();
            executor.termination().toCompletableFuture().get(30, TimeUnit.SECONDS);
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
            executor.termination().toCompletableFuture().get(30, TimeUnit.SECONDS);
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
                assertThat(entered.await(30, TimeUnit.SECONDS)).isTrue();
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
            executor.termination().toCompletableFuture().get(30, TimeUnit.SECONDS);
        }
    }

    @Test void idleEventDoesNotAbortRequestWaitingForAdmission() throws Exception {
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var policy = new io.axiom.execution.AdmissionPolicy(1, 1, Duration.ofSeconds(30));
        var executor = new RequestDispatcher(policy);
        try (var app = Axiom.create()) {
            executor.submit(ExecutionContext.create(Duration.ofSeconds(10)), () -> {
                entered.countDown();
                try { release.await(); } catch (InterruptedException expected) { Thread.currentThread().interrupt(); }
                return null;
            });
            assertThat(entered.await(30, TimeUnit.SECONDS)).isTrue();
            app.admissionPolicy(policy);
            app.get("/", ctx -> "promoted");
            app.start();
            var channel = new EmbeddedChannel(new HttpConnection(app, executor));
            try {
                request(channel);
                assertThat(executor.snapshot().queued()).isEqualTo(1);
                channel.pipeline().fireUserEventTriggered(io.netty.handler.timeout.IdleStateEvent.ALL_IDLE_STATE_EVENT);
                assertThat(channel.isActive()).isTrue();
                release.countDown();
                FullHttpResponse response = awaitResponse(channel);
                try { assertThat(response.status().code()).isEqualTo(200); }
                finally { response.release(); }
            } finally { channel.finishAndReleaseAll(); }
        } finally {
            release.countDown();
            executor.close();
            executor.termination().toCompletableFuture().get(30, TimeUnit.SECONDS);
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

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {
            "GET / HTTP/1.1\r\nHost: a\r\n\r\nGET / HT",
            "POST / HTTP/1.1\r\nHost: a\r\nContent-Length: 3\r\n\r\nabcGET / HT",
            "POST / HTTP/1.1\r\nHost: a\r\nContent-Length: 3\r\n\r\nabcGET / HTTP/1.1\r\nHost: a\r\n",
            "POST / HTTP/1.1\r\nHost: a\r\nTransfer-Encoding: chunked\r\n\r\n3\r\nabc\r\n0\r\n\r\nG"})
    void partialHeadInTheSameReadAsTheEndOfTheRequestBeforeItTimesOut(String bytes) throws Exception {
        var executor = executor();
        try (var app = Axiom.create()) {
            app.requestTimeout(Duration.ofMinutes(5));
            app.get("/", ctx -> "ok");
            app.post("/", ctx -> "ok");
            app.start();
            var channel = wireChannel(app, executor, Duration.ofSeconds(10));
            try {
                channel.writeInbound(ascii(bytes));
                assertThat(awaitOutbound(channel)).startsWith("HTTP/1.1 200 OK");
                channel.advanceTimeBy(9, TimeUnit.SECONDS);
                channel.runScheduledPendingTasks();
                assertThat(channel.isActive()).isTrue();
                channel.advanceTimeBy(1, TimeUnit.SECONDS);
                channel.runScheduledPendingTasks();
                assertThat(outbound(channel)).startsWith("HTTP/1.1 408 Request Timeout");
                assertThat(channel.isActive()).isFalse();
            } finally { channel.finishAndReleaseAll(); }
        } finally {
            executor.close();
            executor.termination().toCompletableFuture().get(30, TimeUnit.SECONDS);
        }
    }

    @Test void lineBreaksAfterABodyDoNotStartTheHeadTimeout() throws Exception {
        var executor = executor();
        try (var app = Axiom.create()) {
            app.post("/", ctx -> "ok");
            app.start();
            var channel = wireChannel(app, executor, Duration.ofSeconds(10));
            try {
                // RFC 9112 section 2.2: empty lines before a request line are ignored.
                channel.writeInbound(ascii("POST / HTTP/1.1\r\nHost: a\r\nContent-Length: 3\r\n\r\nabc\r\n"));
                assertThat(awaitOutbound(channel)).startsWith("HTTP/1.1 200 OK");
                channel.advanceTimeBy(1, TimeUnit.MINUTES);
                channel.runScheduledPendingTasks();
                assertThat(channel.isActive()).isTrue();
                assertThat(channel.<Object>readOutbound()).isNull();
                channel.writeInbound(ascii("\r\nPOST / HTTP/1.1\r\nHost: a\r\nContent-Length: 0\r\n\r\n"));
                assertThat(awaitOutbound(channel)).startsWith("HTTP/1.1 200 OK");
            } finally { channel.finishAndReleaseAll(); }
        } finally {
            executor.close();
            executor.termination().toCompletableFuture().get(30, TimeUnit.SECONDS);
        }
    }

    private static EmbeddedChannel wireChannel(io.axiom.application.Application app, RequestDispatcher executor,
            Duration headTimeout) {
        var channel = new EmbeddedChannel();
        channel.freezeTime();
        channel.pipeline().addLast(new RequestDecoder(new io.netty.handler.codec.http.HttpDecoderConfig()),
                new io.netty.handler.codec.http.HttpResponseEncoder(),
                new HttpConnection(app, executor, TransportSettings.DEFAULTS.withHeadTimeout(headTimeout), null, () -> false));
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
                assertThat(entered.await(30, TimeUnit.SECONDS)).isTrue();
                app.close();
                release.countDown();
                assertResponses(channel, 200, 503);
            } finally { channel.finishAndReleaseAll(); }
        } finally {
            app.close();
            release.countDown();
            executor.close();
            executor.termination().toCompletableFuture().get(30, TimeUnit.SECONDS);
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
                assertThat(entered.await(30, TimeUnit.SECONDS)).isTrue();
                failing.set(true);
                release.countDown();
                assertResponses(channel, 200, 503);
            } finally { channel.finishAndReleaseAll(); }
        } finally {
            release.countDown();
            executor.close();
            executor.termination().toCompletableFuture().get(30, TimeUnit.SECONDS);
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

    @Test void idleEventClosesConnectionWhoseResponseWriteIsStalled() throws Exception {
        var executor = executor();
        try (var app = Axiom.create()) {
            app.get("/", ctx -> "unread");
            app.start();
            var written = new java.util.concurrent.atomic.AtomicReference<Object>();
            // Holds writes without completing them, like a client that stopped reading.
            var stalled = new io.netty.channel.ChannelOutboundHandlerAdapter() {
                @Override public void write(io.netty.channel.ChannelHandlerContext ctx, Object message,
                        io.netty.channel.ChannelPromise promise) { written.set(message); }
            };
            var channel = new EmbeddedChannel(stalled, new HttpConnection(app, executor));
            try {
                request(channel);
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
                while (written.get() == null && System.nanoTime() < deadline) {
                    channel.runPendingTasks();
                    Thread.onSpinWait();
                }
                assertThat(written.get()).isNotNull();
                channel.pipeline().fireUserEventTriggered(io.netty.handler.timeout.IdleStateEvent.ALL_IDLE_STATE_EVENT);
                assertThat(channel.isActive()).isFalse();
            } finally {
                io.netty.util.ReferenceCountUtil.release(written.get());
                channel.finishAndReleaseAll();
            }
        } finally {
            executor.close();
            executor.termination().toCompletableFuture().get(30, TimeUnit.SECONDS);
        }
    }

    @Test void responseWriteThatDoesNotFinishWithinTheResponseTimeoutClosesTheConnection() throws Exception {
        var executor = executor();
        try (var app = Axiom.create()) {
            app.get("/", ctx -> "unread");
            app.start();
            var written = new java.util.concurrent.atomic.AtomicReference<Object>();
            // Holds writes without completing them, like a client that reads too slowly. Unlike the
            // inactivity timeout, the bound holds even if the write makes some progress meanwhile.
            var stalled = new io.netty.channel.ChannelOutboundHandlerAdapter() {
                @Override public void write(io.netty.channel.ChannelHandlerContext ctx, Object message,
                        io.netty.channel.ChannelPromise pending) { written.set(message); }
            };
            var channel = new EmbeddedChannel(stalled, new HttpConnection(app, executor));
            channel.freezeTime();
            try {
                request(channel);
                awaitWritten(channel, written);
                channel.advanceTimeBy(HttpConnection.RESPONSE_TIMEOUT.toMillis() - 1, TimeUnit.MILLISECONDS);
                channel.runScheduledPendingTasks();
                assertThat(channel.isActive()).isTrue();
                channel.advanceTimeBy(1, TimeUnit.MILLISECONDS);
                channel.runScheduledPendingTasks();
                assertThat(channel.isActive()).isFalse();
            } finally {
                io.netty.util.ReferenceCountUtil.release(written.get());
                channel.finishAndReleaseAll();
            }
        } finally {
            executor.close();
            executor.termination().toCompletableFuture().get(30, TimeUnit.SECONDS);
        }
    }

    @Test void theResponseTimeoutBoundsEachResponseWriteNotTheConnection() throws Exception {
        var executor = executor();
        try (var app = Axiom.create()) {
            app.get("/", ctx -> "read");
            app.start();
            var written = new java.util.concurrent.atomic.AtomicReference<Object>();
            var promise = new java.util.concurrent.atomic.AtomicReference<io.netty.channel.ChannelPromise>();
            var held = new io.netty.channel.ChannelOutboundHandlerAdapter() {
                @Override public void write(io.netty.channel.ChannelHandlerContext ctx, Object message,
                        io.netty.channel.ChannelPromise pending) { written.set(message); promise.set(pending); }
            };
            var channel = new EmbeddedChannel(held, new HttpConnection(app, executor));
            channel.freezeTime();
            try {
                request(channel);
                awaitWritten(channel, written);
                // The client takes the response just before the bound.
                channel.advanceTimeBy(HttpConnection.RESPONSE_TIMEOUT.toMillis() - 1, TimeUnit.MILLISECONDS);
                channel.runScheduledPendingTasks();
                io.netty.util.ReferenceCountUtil.release(written.getAndSet(null));
                promise.get().setSuccess();
                // A completed write leaves no deadline behind on the keep-alive connection.
                channel.advanceTimeBy(HttpConnection.RESPONSE_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
                channel.runScheduledPendingTasks();
                assertThat(channel.isActive()).isTrue();
            } finally {
                io.netty.util.ReferenceCountUtil.release(written.get());
                channel.finishAndReleaseAll();
            }
        } finally {
            executor.close();
            executor.termination().toCompletableFuture().get(30, TimeUnit.SECONDS);
        }
    }

    private static void awaitWritten(EmbeddedChannel channel, java.util.concurrent.atomic.AtomicReference<Object> written) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        while (written.get() == null) {
            assertThat(System.nanoTime()).as("response written").isLessThan(deadline);
            channel.runPendingTasks();
            Thread.onSpinWait();
        }
    }

    /** Runs tasks posted from handler threads until a response is written; bounded by the handler's progress. */
    private static <T> T awaitResponse(EmbeddedChannel channel) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
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

    @Test void disconnectMidBodyLeavesNoReceivedChunkRetained() {
        var executor = executor();
        try (var app = Axiom.create()) {
            app.post("/", ctx -> { throw new AssertionError("incomplete body must not execute"); });
            app.start();
            var channel = new EmbeddedChannel(new HttpConnection(app, executor));
            var chunk = io.netty.buffer.Unpooled.copiedBuffer("partial", java.nio.charset.StandardCharsets.US_ASCII);
            try {
                var request = new DefaultHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.POST, "/");
                request.headers().set("Host", "localhost").set("Content-Length", "100");
                chunk.retain(); // Observe the count after the handler's own reference is released.
                channel.writeInbound(request, new io.netty.handler.codec.http.DefaultHttpContent(chunk));
                // Bytes are copied on arrival, so the connection holds no reference mid-body.
                assertThat(chunk.refCnt()).isEqualTo(1);
                channel.close();
                assertThat(channel.isActive()).isFalse();
                assertThat(chunk.refCnt()).isEqualTo(1);
            } finally {
                chunk.release();
                channel.finishAndReleaseAll();
            }
        } finally { executor.close(); }
    }

    @Test void bodyStillArrivingWhenTheDeadlineExpiresIsAnswered408() {
        var executor = executor();
        try (var app = Axiom.create()) {
            app.requestTimeout(Duration.ofSeconds(5));
            app.post("/", ctx -> { throw new AssertionError("must not execute"); });
            app.start();
            var channel = wireChannel(app, executor, Duration.ofSeconds(10));
            try {
                channel.writeInbound(ascii("POST / HTTP/1.1\r\nHost: a\r\nContent-Length: 10\r\n\r\nab"));
                for (int i = 0; i < 4; i++) {
                    channel.advanceTimeBy(1, TimeUnit.SECONDS);
                    channel.runScheduledPendingTasks();
                    channel.writeInbound(ascii("c"));
                    assertThat(channel.isActive()).isTrue();
                }
                channel.advanceTimeBy(1, TimeUnit.SECONDS);
                channel.runScheduledPendingTasks();
                assertThat(outbound(channel)).startsWith("HTTP/1.1 408 Request Timeout");
                assertThat(channel.isActive()).isFalse();
            } finally { channel.finishAndReleaseAll(); }
        } finally { executor.close(); }
    }

    @Test void bodyBytesDoNotStartTheRequestHeadTimeout() throws Exception {
        var executor = executor();
        try (var app = Axiom.create()) {
            app.requestTimeout(Duration.ofMinutes(5));
            app.post("/", ctx -> "received " + ctx.request().body().length());
            app.start();
            var channel = wireChannel(app, executor, Duration.ofSeconds(10));
            try {
                channel.writeInbound(ascii("POST / HTTP/1.1\r\nHost: a\r\nContent-Length: 3\r\n\r\na"));
                channel.advanceTimeBy(30, TimeUnit.SECONDS);
                channel.runScheduledPendingTasks();
                channel.writeInbound(ascii("b"));
                channel.advanceTimeBy(30, TimeUnit.SECONDS);
                channel.runScheduledPendingTasks();
                assertThat(channel.isActive()).isTrue();
                channel.writeInbound(ascii("c"));
                assertThat(awaitOutbound(channel)).startsWith("HTTP/1.1 200 OK").endsWith("received 3");
            } finally { channel.finishAndReleaseAll(); }
        } finally {
            executor.close();
            executor.termination().toCompletableFuture().get(30, TimeUnit.SECONDS);
        }
    }

    /** Counts every buffer the connection allocates, including composite consolidation. */
    private static final class CountingAllocator extends io.netty.buffer.AbstractByteBufAllocator {
        final java.util.concurrent.atomic.AtomicInteger allocations = new java.util.concurrent.atomic.AtomicInteger();
        CountingAllocator() { super(false); }
        @Override public boolean isDirectBufferPooled() { return false; }
        @Override protected io.netty.buffer.ByteBuf newHeapBuffer(int initial, int max) {
            allocations.incrementAndGet();
            return new io.netty.buffer.UnpooledHeapByteBuf(this, initial, max);
        }
        @Override protected io.netty.buffer.ByteBuf newDirectBuffer(int initial, int max) {
            allocations.incrementAndGet();
            return new io.netty.buffer.UnpooledDirectByteBuf(this, initial, max);
        }
        @Override public io.netty.buffer.CompositeByteBuf compositeHeapBuffer(int maxComponents) {
            allocations.incrementAndGet();
            return super.compositeHeapBuffer(maxComponents);
        }
        @Override public io.netty.buffer.CompositeByteBuf compositeDirectBuffer(int maxComponents) {
            allocations.incrementAndGet();
            return super.compositeDirectBuffer(maxComponents);
        }
    }

    /** Heap bytes allocated by the calling thread, which runs every embedded channel task. */
    private static long allocatedByThisThread() {
        return ((com.sun.management.ThreadMXBean) java.lang.management.ManagementFactory.getThreadMXBean())
                .getCurrentThreadAllocatedBytes();
    }

    @Test void stalledDeclaredBodyAllocatesOnlyWhatArrived() {
        var executor = executor();
        try (var app = Axiom.create()) {
            app.maxRequestBody(1024 * 1024);
            app.post("/", ctx -> { throw new AssertionError("incomplete body must not execute"); });
            app.start();
            var channel = new EmbeddedChannel(new HttpConnection(app, executor));
            try {
                var request = new DefaultHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.POST, "/");
                request.headers().set("Host", "localhost").set("Content-Length", String.valueOf(1024 * 1024));
                var first = new io.netty.handler.codec.http.DefaultHttpContent(io.netty.buffer.Unpooled.wrappedBuffer(new byte[] {1}));
                // Warm up the code path so class loading does not count.
                channel.writeInbound(request);
                long before = allocatedByThisThread();
                channel.writeInbound(first);
                long allocated = allocatedByThisThread() - before;
                // A one-byte arrival must not reserve the declared megabyte in heap.
                assertThat(allocated).isLessThan(64 * 1024);
                assertThat(channel.isActive()).isTrue();
            } finally { channel.finishAndReleaseAll(); }
        } finally { executor.close(); }
    }

    @Test void declaredBodyArrivingInUnevenPiecesIsDeliveredIntact() throws Exception {
        var executor = executor();
        int length = 300_001;
        var expected = new byte[length];
        new java.util.Random(11).nextBytes(expected);
        try (var app = Axiom.create()) {
            app.maxRequestBody(length);
            app.post("/", ctx -> java.util.Arrays.equals(ctx.request().body().bytes(), expected) ? "intact" : "corrupt");
            app.start();
            var channel = new EmbeddedChannel(new HttpConnection(app, executor));
            try {
                var request = new DefaultHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.POST, "/");
                request.headers().set("Host", "localhost").set("Content-Length", String.valueOf(length));
                channel.writeInbound(request);
                int offset = 0;
                for (int size = 1; offset < length; size = size * 3 + 1) {
                    int piece = Math.min(size, length - offset);
                    var content = io.netty.buffer.Unpooled.copiedBuffer(expected, offset, piece);
                    offset += piece;
                    channel.writeInbound(offset == length ? new io.netty.handler.codec.http.DefaultLastHttpContent(content)
                            : new io.netty.handler.codec.http.DefaultHttpContent(content));
                }
                FullHttpResponse response = awaitResponse(channel);
                try {
                    assertThat(response.content().toString(java.nio.charset.StandardCharsets.UTF_8)).isEqualTo("intact");
                } finally { response.release(); }
            } finally { channel.finishAndReleaseAll(); }
        } finally {
            executor.close();
            executor.termination().toCompletableFuture().get(30, TimeUnit.SECONDS);
        }
    }

    @Test void tinyChunksAreCopiedOnArrivalWithoutNettyAccumulation() throws Exception {
        var executor = executor();
        try (var app = Axiom.create()) {
            app.maxRequestBody(20_000);
            app.post("/", ctx -> {
                var bytes = ctx.request().body().bytes();
                for (int i = 0; i < bytes.length; i++) {
                    if (bytes[i] != (byte) ('a' + i % 26)) { return "corrupt at " + i; }
                }
                return "received " + bytes.length;
            });
            app.start();
            var allocator = new CountingAllocator();
            var channel = new EmbeddedChannel(new HttpConnection(app, executor));
            channel.config().setAllocator(allocator);
            try {
                var request = new DefaultHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.POST, "/");
                request.headers().set("Host", "localhost").set("Transfer-Encoding", "chunked");
                channel.writeInbound(request);
                for (int i = 0; i < 20_000; i++) {
                    var piece = io.netty.buffer.Unpooled.buffer(1).writeByte('a' + i % 26);
                    channel.writeInbound(new io.netty.handler.codec.http.DefaultHttpContent(piece));
                    assertThat(piece.refCnt()).isZero();
                }
                channel.writeInbound(LastHttpContent.EMPTY_LAST_CONTENT);
                FullHttpResponse response = awaitResponse(channel);
                try {
                    assertThat(response.content().toString(java.nio.charset.StandardCharsets.UTF_8)).isEqualTo("received 20000");
                } finally { response.release(); }
                // Only the response body is allocated from the channel allocator.
                assertThat(allocator.allocations).hasValueLessThanOrEqualTo(1);
            } finally { channel.finishAndReleaseAll(); }
        } finally {
            executor.close();
            executor.termination().toCompletableFuture().get(30, TimeUnit.SECONDS);
        }
    }
}
