package io.axiom.http.internal;

import io.axiom.application.Application;
import io.axiom.execution.ExecutionContext;
import io.axiom.http.HttpStatus;
import io.axiom.internal.OwnedBodies;
import io.axiom.http.Request;
import io.axiom.http.Response;
import io.axiom.server.internal.execution.RequestDispatcher.DeadlineExceededException;
import io.axiom.server.internal.execution.RequestDispatcher.DispatchRejectedException;
import io.axiom.server.internal.execution.RequestDispatcher.QueueTimeoutException;
import io.axiom.server.internal.Problems;
import io.axiom.server.internal.execution.RequestDispatcher;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.codec.DateFormatter;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.HttpContent;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpObject;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpUtil;
import io.netty.handler.codec.http.HttpHeaderValues;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.handler.codec.http.LastHttpContent;
import io.netty.handler.codec.http.TooLongHttpHeaderException;
import io.netty.handler.codec.http.TooLongHttpLineException;
import io.netty.handler.timeout.IdleStateEvent;
import io.netty.util.ReferenceCounted;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.RejectedExecutionException;

/** All mutable connection state belongs to the channel's event loop. */
final class HttpConnection extends SimpleChannelInboundHandler<HttpObject> {
    private static final System.Logger LOG = System.getLogger(HttpConnection.class.getName());
    private static final Object UNMATCHED = new Object();
    /** Bound from a request's first byte until its head is complete; a slower client receives 408. */
    static final Duration REQUEST_HEAD_TIMEOUT = Duration.ofSeconds(10);
    private static final int MAX_RESPONSE = 1024 * 1024;
    private static final java.util.regex.Pattern CONTENT_LENGTH = java.util.regex.Pattern.compile("[0-9]{1,18}");
    /** Outstanding requests per connection, including the active one; more closes the connection. */
    static final int MAX_PIPELINED = 8;
    private static final Set<String> HOP_HEADERS = Set.of("connection", "keep-alive", "transfer-encoding",
            "content-length", "trailer", "upgrade", "proxy-authenticate", "proxy-authorization", "te");
    private final Application application;
    private final RequestDispatcher executor;
    private final long headTimeoutNanos;
    private final ArrayDeque<Exchange> pending = new ArrayDeque<>();
    private Exchange receiving;
    private boolean busy;
    private boolean closing;
    private boolean draining;
    private ChannelHandlerContext context;
    private RequestDispatcher.Task<WireResponse> active;
    private ScheduledFuture<?> headTimer;
    // Body of the request being received, copied out of each Netty buffer as it arrives. The array
    // grows by doubling as bytes arrive, capped at the declared Content-Length or, for chunked
    // bodies, at the limit, so a stalled sender holds little memory. Dropped on every exit path
    // (see releaseBody).
    private byte[] bodyBytes;
    private boolean bodyChunked;
    private long declaredLength;
    private int bodyLength;
    private int bodyLimit;
    /** Body bytes of requests buffered in {@code pending}, bounded by twice the body limit. */
    private long queuedBodyBytes;
    /** An accepted {@code Expect: 100-continue} whose interim response is not yet written. */
    private boolean continuePending;
    private ScheduledFuture<?> bodyTimer;
    /** First allocation for a body; doubled as needed up to its declared length or the body limit. */
    private static final int INITIAL_BODY_CAPACITY = 8192;

    HttpConnection(Application application, RequestDispatcher executor) {
        this(application, executor, REQUEST_HEAD_TIMEOUT);
    }

    HttpConnection(Application application, RequestDispatcher executor, Duration headTimeout) {
        this.application = application;
        this.executor = executor;
        this.headTimeoutNanos = headTimeout.toNanos();
    }

    /**
     * Placed before the HTTP codec; signals raw request bytes so a request's read deadline starts at
     * its first byte rather than when its headers are complete.
     */
    static final class RequestBytes extends ChannelInboundHandlerAdapter {
        static final Object EVENT = new Object();
        @Override public void channelRead(ChannelHandlerContext ctx, Object message) {
            if (message instanceof ByteBuf buffer && buffer.isReadable()) { ctx.fireUserEventTriggered(EVENT); }
            ctx.fireChannelRead(message);
        }
    }

    private void requestBytes(ChannelHandlerContext ctx) {
        // Body bytes are bounded by the request deadline, not the head timeout.
        if (closing || headTimer != null || receiving != null) { return; }
        headTimer = ctx.executor().schedule(() -> {
            headTimer = null;
            if (!closing) { fail(ctx, 408); }
        }, headTimeoutNanos, TimeUnit.NANOSECONDS);
    }

    private void headComplete() {
        if (headTimer != null) { headTimer.cancel(false); headTimer = null; }
    }

    /** Releases a partially received body and its deadline timer; idempotent. */
    private void releaseBody() {
        bodyBytes = null;
        if (bodyTimer != null) { bodyTimer.cancel(false); bodyTimer = null; }
        bodyLength = 0;
        continuePending = false;
    }

    @Override public void handlerAdded(ChannelHandlerContext ctx) { context = ctx; }

    /**
     * Starts graceful shutdown on the channel's event loop: an idle connection closes now, and a
     * connection with a running exchange closes after that response. Queued requests are dropped.
     */
    void drain() {
        if (closing || draining) { return; }
        draining = true;
        if (!busy) { abort(context); }
    }

    @Override protected void channelRead0(ChannelHandlerContext ctx, HttpObject message) {
        if (closing) { return; }
        if (!message.decoderResult().isSuccess()) { fail(ctx, decoderFailureStatus(message.decoderResult().cause())); return; }
        if (message instanceof HttpRequest request) {
            if (receiving != null) { fail(ctx, 400); return; }
            headComplete();
            if (!accept(ctx, request)) { return; }
        }
        if (message instanceof HttpContent content) {
            if (receiving == null) { fail(ctx, 400); return; }
            if (content.content().isReadable() && !receiveBody(ctx, content.content())) { return; }
            if (message instanceof LastHttpContent) { completeRequest(ctx); }
        }
    }

    /** Validates a request head and starts receiving it; false when a response or close has been issued. */
    private boolean accept(ChannelHandlerContext ctx, HttpRequest request) {
        boolean http10 = request.protocolVersion().equals(HttpVersion.HTTP_1_0);
        if (!http10 && !request.protocolVersion().equals(HttpVersion.HTTP_1_1)) { fail(ctx, 505); return false; }
        if (!validHost(request, http10)) { fail(ctx, 400); return false; }
        if (request.method().name().equals("CONNECT") || request.headers().contains(HttpHeaderNames.UPGRADE)) {
            fail(ctx, 501); return false;
        }
        var headers = request.headers();
        boolean chunked = headers.contains(HttpHeaderNames.TRANSFER_ENCODING);
        long length;
        try {
            if (chunked && headers.contains(HttpHeaderNames.CONTENT_LENGTH)) { fail(ctx, 400); return false; }
            // Transfer codings do not exist in HTTP/1.0 (RFC 9112 6.1), so the framing is ambiguous.
            if (chunked && http10) { fail(ctx, 400); return false; }
            if (chunked && !headers.get(HttpHeaderNames.TRANSFER_ENCODING).trim()
                    .equalsIgnoreCase(HttpHeaderValues.CHUNKED.toString())) {
                fail(ctx, 501); return false;
            }
            var declared = headers.getAll(HttpHeaderNames.CONTENT_LENGTH);
            // The decoder already rejects signs, empty values and non-digits; this keeps the rule
            // explicit: exactly one value of 1 to 18 digits (surrounding whitespace is not part of it).
            if (declared.size() > 1 || (declared.size() == 1 && !CONTENT_LENGTH.matcher(declared.getFirst().trim()).matches())) {
                fail(ctx, 400); return false;
            }
            // RFC 9112 6.3: without Content-Length or Transfer-Encoding a request has no body.
            length = chunked || declared.isEmpty() ? (chunked ? -1 : 0) : Long.parseLong(declared.getFirst().trim());
        } catch (NumberFormatException invalid) { fail(ctx, 400); return false; }
        bodyLimit = application.maxRequestBody();
        // Rejected before any body byte is read; the connection closes after the response.
        if (length > bodyLimit) { fail(ctx, 413); return false; }
        var expect = headers.getAll(HttpHeaderNames.EXPECT);
        if (!expect.isEmpty() && (expect.size() != 1 || !expect.getFirst().trim().equalsIgnoreCase("100-continue"))) {
            fail(ctx, 417); return false;
        }
        var fields = new TreeMap<String, String>(String.CASE_INSENSITIVE_ORDER);
        for (var header : headers) { fields.merge(header.getKey(), header.getValue(), (first, next) -> first + ", " + next); }
        try {
            // Validates the path and query in one pass; any rejection is a 400.
            receiving = new Exchange(Request.fromTarget(request.method().name(), request.uri()).withHeaders(fields),
                    HttpUtil.isKeepAlive(request), http10, ExecutionContext.create(application.requestTimeout()));
        } catch (IllegalArgumentException invalid) { fail(ctx, 400); return false; }
        if (chunked || length > 0) {
            bodyChunked = chunked;
            declaredLength = length;
            // Declared bodies are reserved in full against the per-connection bound up front.
            if (!chunked && queuedBodyBytes + length > 2L * bodyLimit) { abort(ctx); return false; }
            var exchange = receiving;
            // A slow body counts against the request deadline that started with the head.
            bodyTimer = ctx.executor().schedule(() -> {
                bodyTimer = null;
                if (!closing && receiving == exchange) { fail(ctx, 408); }
            }, exchange.execution().remainingTime().toNanos(), TimeUnit.NANOSECONDS);
            continuePending = !expect.isEmpty() && !http10; // HTTP/1.0 clients do not expect 100.
            sendContinue(ctx);
        }
        return true;
    }

    /**
     * Copies body bytes out of the Netty buffer, which the inbound handler releases on return.
     * Returns false when the limit was exceeded and the connection is closing.
     */
    private boolean receiveBody(ChannelHandlerContext ctx, ByteBuf content) {
        continuePending = false; // The client sent the body without waiting; no interim response is needed.
        int readable = content.readableBytes();
        long total = (long) bodyLength + readable;
        if (total > bodyLimit) { fail(ctx, 413); return false; }
        if (queuedBodyBytes + total > 2L * bodyLimit) { abort(ctx); return false; }
        // Growth is capped at the declared length, so a complete declared body fills its array exactly.
        long cap = bodyChunked ? bodyLimit : declaredLength;
        if (total > cap) { fail(ctx, 400); return false; } // More than declared; the decoder prevents this.
        if (bodyBytes == null) {
            bodyBytes = new byte[(int) Math.min(cap, Math.max(INITIAL_BODY_CAPACITY, total))];
        } else if (total > bodyBytes.length) {
            // Doubling keeps the copying linear in the body size.
            bodyBytes = Arrays.copyOf(bodyBytes, (int) Math.min(cap, Math.max(total, 2L * bodyBytes.length)));
        }
        content.readBytes(bodyBytes, bodyLength, readable);
        bodyLength = (int) total;
        return true;
    }

    private void completeRequest(ChannelHandlerContext ctx) {
        if (pending.size() + (busy ? 1 : 0) >= MAX_PIPELINED) { abort(ctx); return; }
        var exchange = receiving;
        if (bodyBytes != null) {
            if (bodyLength != declaredLength && !bodyChunked) { fail(ctx, 400); return; }
            try {
                // The array is handed over, not copied again unless it is larger than the body.
                var bytes = bodyLength == bodyBytes.length ? bodyBytes : Arrays.copyOf(bodyBytes, bodyLength);
                bodyBytes = null;
                var body = OwnedBodies.adopt(exchange.request().header("Content-Type").orElse(null), bytes);
                exchange = new Exchange(exchange.request().withBody(body), exchange.keepAlive(),
                        exchange.http10(), exchange.execution());
            } catch (IllegalArgumentException invalid) { fail(ctx, 400); return; }
            queuedBodyBytes += bodyLength;
        }
        releaseBody();
        pending.addLast(exchange);
        receiving = null;
        dispatch(ctx);
    }

    /**
     * Writes {@code 100 Continue} for the request being received once every earlier response
     * has been written, so the interim response never overtakes a pipelined final response.
     */
    private void sendContinue(ChannelHandlerContext ctx) {
        if (!continuePending || busy || !pending.isEmpty() || closing) { return; }
        continuePending = false;
        ctx.writeAndFlush(new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.CONTINUE,
                Unpooled.EMPTY_BUFFER)).addListener(future -> { if (!future.isSuccess()) { abort(ctx); } });
    }

    /** HTTP/1.1 requires exactly one Host; HTTP/1.0 may omit it but must not send an invalid one. */
    private static boolean validHost(HttpRequest request, boolean http10) {
        var hosts = request.headers().getAll(HttpHeaderNames.HOST);
        if (http10 && hosts.isEmpty()) { return true; }
        if (hosts.size() != 1 || hosts.getFirst().isEmpty()) { return false; }
        try {
            var host = URI.create("http://" + hosts.getFirst());
            return host.getHost() != null && host.getRawUserInfo() == null
                    && host.getRawPath().isEmpty() && host.getRawQuery() == null
                    && host.getRawFragment() == null && host.getPort() <= 65535
                    && !hosts.getFirst().endsWith(":");
        } catch (IllegalArgumentException invalid) { return false; }
    }

    private void dispatch(ChannelHandlerContext ctx) {
        if (busy || closing || pending.isEmpty()) { return; }
        // Reads stay enabled so a client disconnect cancels the running handler; the
        // pipeline bound below keeps buffered requests finite.
        busy = true;
        var exchange = pending.removeFirst();
        queuedBodyBytes -= exchange.request().body().length();
        if (exchange.execution().isExpired()) { send(ctx, exchange, error(504, exchange)); return; }
        try {
            // A closed application or dispatcher cannot run the request; answer instead of
            // leaving the connection busy. This also runs from a write listener, where an
            // escaping exception would be swallowed.
            if (application.state() != Application.State.RUNNING) { throw new RejectedExecutionException("Application closed"); }
            var route = application.resolve(exchange.request());
            var policy = route.map(application::admissionPolicy).orElseGet(application::admissionPolicy);
            active = executor.submit(route.<Object>map(value -> value).orElse(UNMATCHED),
                    policy, exchange.execution(), () -> {
                try { return prepare(application.handle(exchange.request(), exchange.execution()), exchange); }
                catch (Exception | Error failure) {
                    if (!exchange.execution().isExpired() && !Thread.currentThread().isInterrupted()) {
                        LOG.log(System.Logger.Level.ERROR,
                                "HTTP request " + exchange.execution().requestId() + " failed", failure);
                    }
                    throw failure;
                }
            });
            active.result().whenComplete((response, thrown) -> {
                var failure = unwrap(thrown);
                try {
                    ctx.executor().execute(() -> {
                        active = null; // The outcome is final; only the response write remains.
                        if (closing || !ctx.channel().isActive()) { return; }
                        if (failure instanceof Error) {
                            abort(ctx);
                        } else {
                            send(ctx, exchange, failure == null ? response
                                    : error(failure instanceof DeadlineExceededException ? 504
                                            : failure instanceof QueueTimeoutException
                                                    || failure instanceof DispatchRejectedException ? 503 : 500, exchange));
                        }
                    });
                } catch (RejectedExecutionException stopped) { /* Channel shutdown owns cleanup. */ }
            });
        } catch (RuntimeException unavailable) {
            if (!(unavailable instanceof RejectedExecutionException)) {
                LOG.log(System.Logger.Level.WARNING,
                        "HTTP request " + exchange.execution().requestId() + " could not be dispatched", unavailable);
            }
            if (active != null) { active.cancel(); active = null; }
            send(ctx, exchange, error(503, exchange));
        }
    }

    private static Throwable unwrap(Throwable failure) {
        while (failure instanceof java.util.concurrent.CompletionException && failure.getCause() != null) {
            failure = failure.getCause();
        }
        return failure;
    }

    private static WireResponse prepare(Response response, Exchange exchange) {
        var body = response.body();
        byte[] bytes;
        if (body == null) { bytes = new byte[0]; }
        else if (body instanceof byte[] value) { bytes = value; }
        else if (body instanceof String value && value.length() <= MAX_RESPONSE) {
            bytes = value.getBytes(StandardCharsets.UTF_8);
        } else { return error(500, exchange); }
        if (bytes.length > MAX_RESPONSE) { return error(500, exchange); }
        int headerSize = 0;
        for (var header : response.headers().entrySet()) {
            headerSize += header.getKey().length() + header.getValue().length() + 4;
            if (headerSize > 8192 || header.getValue().chars().anyMatch(c -> c > 255)) { return error(500, exchange); }
        }
        boolean close = java.util.Arrays.stream(response.headers().getOrDefault("Connection", "").split(","))
                .anyMatch(token -> token.trim().equalsIgnoreCase("close"));
        return new WireResponse(response.status(), response.headers(), bytes, close);
    }

    private void send(ChannelHandlerContext ctx, Exchange exchange, WireResponse response) {
        boolean head = exchange.request().method().equals("HEAD");
        // Exactly one owner releases the body: this method until the write takes the message.
        ReferenceCounted owned = null;
        try {
            var bytes = head ? new byte[0] : response.body();
            var content = bytes.length == 0 ? Unpooled.EMPTY_BUFFER : ctx.alloc().buffer(bytes.length);
            owned = content;
            content.writeBytes(bytes);
            var message = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1,
                    HttpResponseStatus.valueOf(response.status()), content);
            owned = message;
            var omitted = new HashSet<>(HOP_HEADERS);
            for (var token : response.headers().getOrDefault("Connection", "").split(",")) {
                omitted.add(token.trim().toLowerCase(Locale.ROOT));
            }
            response.headers().forEach((name, value) -> {
                if (!omitted.contains(name.toLowerCase(Locale.ROOT))) { message.headers().set(name, value); }
            });
            if (!head && response.status() != 204 && response.status() != 304) {
                HttpUtil.setContentLength(message, response.body().length);
            }
            message.headers().set("X-Request-ID", exchange.execution().requestId());
            message.headers().set(HttpHeaderNames.DATE, httpDate());
            boolean keepAlive = exchange.keepAlive() && !response.close() && !draining;
            HttpUtil.setKeepAlive(message, keepAlive);
            // HTTP/1.0 clients assume close unless persistence is acknowledged explicitly.
            if (keepAlive && exchange.http10()) { message.headers().set(HttpHeaderNames.CONNECTION, "keep-alive"); }
            if (!keepAlive) { closing = true; pending.clear(); queuedBodyBytes = 0; releaseBody(); }
            owned = null;
            ctx.writeAndFlush(message).addListener(future -> {
                active = null;
                if (!future.isSuccess() || !keepAlive || draining) { ctx.close(); return; }
                busy = false;
                dispatch(ctx);
                sendContinue(ctx);
            });
        } catch (RuntimeException failure) {
            if (owned != null) { owned.release(); }
            abort(ctx);
        }
    }

    /** IMF-fixdate for the current second, formatted at most once per second across connections. */
    static String httpDate() {
        long second = System.currentTimeMillis() / 1000;
        var cached = date;
        if (cached.second() != second) {
            cached = new CachedDate(second, DateFormatter.format(new Date(second * 1000)));
            date = cached;
        }
        return cached.value();
    }

    /**
     * A framework error: a problem+json body with only status, default code and request ID, and
     * a connection close because the request's framing or the connection state may be unusable.
     */
    private static WireResponse error(int status, Exchange exchange) {
        return new WireResponse(status, Map.of("Content-Type", Problems.MEDIA_TYPE),
                Problems.body(status, HttpStatus.defaultCode(status), exchange.execution().requestId(), List.of()), true);
    }

    private void fail(ChannelHandlerContext ctx, int status) {
        // Never send an error ahead of an earlier pipelined response.
        if (busy || !pending.isEmpty()) { abort(ctx); return; }
        var exchange = new Exchange(Request.get("/"), false, false, ExecutionContext.create(application.requestTimeout()));
        send(ctx, exchange, error(status, exchange));
    }

    /** Maps a decoder failure: an overlong request line is 414, an overlong header section 431. */
    private static int decoderFailureStatus(Throwable cause) {
        if (cause instanceof TooLongHttpLineException) { return 414; }
        if (cause instanceof TooLongHttpHeaderException) { return 431; }
        return 400;
    }

    private void abort(ChannelHandlerContext ctx) { closing = true; releaseBody(); ctx.close(); }

    @Override public void channelInactive(ChannelHandlerContext ctx) {
        closing = true;
        headComplete();
        releaseBody();
        receiving = null;
        pending.clear();
        queuedBodyBytes = 0;
        if (active != null) { active.cancel(); active = null; }
    }

    @Override public void userEventTriggered(ChannelHandlerContext ctx, Object event) throws Exception {
        // A running handler is bounded by its own deadline, so inactivity is ignored only while it
        // executes; idle connections and stalled response writes still close.
        if (event == RequestBytes.EVENT) { requestBytes(ctx); }
        else if (event instanceof IdleStateEvent) { if (active == null) { abort(ctx); } }
        else { super.userEventTriggered(ctx, event); }
    }

    @Override public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) { abort(ctx); }

    private record CachedDate(long second, String value) { }
    private static volatile CachedDate date = new CachedDate(Long.MIN_VALUE, "");
    private record Exchange(Request request, boolean keepAlive, boolean http10, ExecutionContext execution) { }
    private record WireResponse(int status, Map<String, String> headers, byte[] body, boolean close) { }
}
