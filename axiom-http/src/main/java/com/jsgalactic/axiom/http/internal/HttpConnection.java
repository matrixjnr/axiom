package com.jsgalactic.axiom.http.internal;

import com.jsgalactic.axiom.application.Application;
import com.jsgalactic.axiom.execution.ExecutionContext;
import com.jsgalactic.axiom.http.HttpStatus;
import com.jsgalactic.axiom.internal.OwnedBodies;
import com.jsgalactic.axiom.lifecycle.ListenerOptions;
import com.jsgalactic.axiom.http.Request;
import com.jsgalactic.axiom.http.Response;
import com.jsgalactic.axiom.http.StreamAbortedException;
import com.jsgalactic.axiom.server.internal.execution.RequestDispatcher.DeadlineExceededException;
import com.jsgalactic.axiom.server.internal.execution.RequestDispatcher.DispatchRejectedException;
import com.jsgalactic.axiom.server.internal.execution.RequestDispatcher.QueueTimeoutException;
import com.jsgalactic.axiom.server.internal.Problems;
import com.jsgalactic.axiom.server.internal.ResponseSerialization;
import com.jsgalactic.axiom.server.internal.execution.RequestDispatcher;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.channel.socket.DuplexChannel;
import io.netty.handler.codec.DateFormatter;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.DefaultHttpResponse;
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
import io.netty.handler.ssl.SslHandler;
import io.netty.handler.timeout.IdleStateEvent;
import io.netty.util.ReferenceCounted;
import java.net.InetSocketAddress;
import java.net.URI;
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
import java.util.function.BooleanSupplier;

/** All mutable connection state belongs to the channel's event loop. */
final class HttpConnection extends SimpleChannelInboundHandler<HttpObject> {
    private static final System.Logger LOG = System.getLogger(HttpConnection.class.getName());
    private static final Object UNMATCHED = new Object();
    /** Bound from a request's first byte until its head is complete; a slower client receives 408. */
    static final Duration REQUEST_HEAD_TIMEOUT = ListenerOptions.defaults().headTimeout();
    private static final java.util.regex.Pattern CONTENT_LENGTH = java.util.regex.Pattern.compile("[0-9]{1,18}");
    /**
     * Outstanding requests per connection, including the active one. A further request is not
     * executed: it is answered 503 after the earlier responses, and the connection closes.
     */
    static final int MAX_PIPELINED = ListenerOptions.defaults().maxPipelinedRequests();
    /**
     * Input read and dropped, never buffered, after a listener error (while earlier pipelined
     * requests finish, and again while lingering). Reading continues so that a client disconnect is
     * noticed and cancels the running handler. A client that sends more is treated as abusive: the
     * connection closes at once, cancelling any running handler.
     */
    static final int MAX_DISCARDED_INPUT = ListenerOptions.defaults().maxDiscardedInput();
    /**
     * After the last response on a connection the output is shut down and input is read and
     * discarded for at most this long before the connection closes, so a client still sending (for
     * example the rest of a rejected body, or pipelined requests) reads the whole response instead
     * of a reset.
     */
    static final Duration LINGER_TIMEOUT = ListenerOptions.defaults().lingerTimeout();
    /**
     * Lingering ends once no input has arrived for this long. Lingering exists for a client that is
     * still sending; one that has gone quiet has most likely finished, and closing a connection with
     * no unread input sends no reset. The period is longer than a typical TCP retransmission timeout
     * (200 ms minimum on Linux), so a single lost segment from a client still sending does not end
     * it; a client that pauses longer while still sending may get a reset, which can destroy
     * response bytes it has not read yet.
     */
    static final Duration LINGER_QUIET_TIMEOUT = ListenerOptions.defaults().lingerQuietTimeout();
    /** Total linger bound once the listener is closing, so lingering connections barely delay shutdown. */
    static final Duration SHUTDOWN_LINGER_TIMEOUT = ListenerOptions.defaults().shutdownLingerTimeout();
    /**
     * Bound on writing one response: from handing it to the socket until the operating system has
     * accepted its last byte. A client that reads too slowly to take a whole response (at most 1 MiB
     * of body) in this time loses the connection. The inactivity timeout alone would let a client
     * that reads a few bytes now and then hold the connection indefinitely.
     */
    static final Duration RESPONSE_TIMEOUT = ListenerOptions.defaults().responseTimeout();
    private static final Set<String> HOP_HEADERS = Set.of("connection", "keep-alive", "transfer-encoding",
            "content-length", "trailer", "upgrade", "proxy-authenticate", "proxy-authorization", "te");
    private final Application application;
    private final RequestDispatcher executor;
    private final long headTimeoutNanos;
    private final long lingerNanos;
    private final long lingerQuietNanos;
    private final long shutdownLingerNanos;
    private final long responseNanos;
    private final int maxPipelined;
    private final int maxDiscardedInput;
    /**
     * True once the listener has started closing. Read on every response, from the moment the
     * listener's close begins, so a response sent after that carries {@code Connection: close} even
     * before {@link #drain} has run on this connection.
     */
    private final BooleanSupplier listenerClosing;
    /** The listener's slot for this connection, or null outside a listener; released by the listener. */
    private final ConnectionSlots.Slot slot;
    private final ArrayDeque<Exchange> pending = new ArrayDeque<>();
    private Exchange receiving;
    private boolean busy;
    private boolean closing;
    private boolean draining;
    /**
     * Status of a listener error waiting for earlier pipelined responses; zero when none. Once set,
     * no further input is decoded or executed.
     */
    private int deferredStatus;
    /** The deferred error answers a HEAD request, so it must be sent without a body. */
    private boolean deferredHead;
    /**
     * The request whose head was accepted last and that is not yet complete is HEAD. A listener
     * error for it is sent without body bytes, like any response to HEAD.
     */
    private boolean receivingHead;
    /** Output is shut down after an error response; input is discarded until the connection closes. */
    private boolean lingering;
    private ScheduledFuture<?> lingerTimer;
    /** While lingering: when it ends at the latest, and when input last arrived (event loop ticker). */
    private long lingerDeadline;
    private long lastInput;
    /** Bounds the response write in progress; at most one response is written at a time. */
    private ScheduledFuture<?> responseTimer;
    private ChannelHandlerContext context;
    private RequestDispatcher.Task<WireResponse> active;
    /**
     * The streamed response whose head was sent and whose body the handler is still writing; null
     * otherwise. Set when the head is written and cleared when the exchange ends.
     */
    private ChannelBodyWriter stream;
    private boolean streamKeepAlive;
    private final StreamMetrics streamMetrics;
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

    /** A connection outside any listener, with the production bounds; for tests. */
    HttpConnection(Application application, RequestDispatcher executor) {
        this(application, executor, TransportSettings.DEFAULTS, null, () -> false);
    }

    /** Production uses {@link TransportSettings#DEFAULTS}; tests may vary single bounds. */
    HttpConnection(Application application, RequestDispatcher executor, TransportSettings settings,
            ConnectionSlots.Slot slot, BooleanSupplier listenerClosing) {
        this.slot = slot;
        this.listenerClosing = listenerClosing;
        this.application = application;
        this.executor = executor;
        this.headTimeoutNanos = settings.headTimeout().toNanos();
        this.lingerNanos = settings.linger().toNanos();
        this.lingerQuietNanos = settings.lingerQuiet().toNanos();
        this.shutdownLingerNanos = settings.shutdownLinger().toNanos();
        this.responseNanos = settings.responseTimeout().toNanos();
        this.maxPipelined = settings.options().maxPipelinedRequests();
        this.maxDiscardedInput = settings.options().maxDiscardedInput();
        this.streamMetrics = new StreamMetrics(application.metrics());
    }

    /**
     * Starts the head deadline once a request head has started arriving, including when its first
     * bytes came in the same read as the end of the previous request.
     */
    private void inputDecoded(ChannelHandlerContext ctx) {
        // Body bytes are bounded by the request deadline, not the head timeout.
        if (closing || deferredStatus != 0 || headTimer != null || receiving != null) { return; }
        var decoder = ctx.pipeline().get(RequestDecoder.class);
        if (decoder == null || !decoder.headStarted()) { return; }
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
        // A lingering connection only waits for its client; shutdown does not wait long for it.
        if (lingering && !draining) {
            draining = true;
            long now = now(context);
            lingerDeadline = Math.min(lingerDeadline, now + shutdownLingerNanos);
            scheduleLingerCheck(context, now);
            return;
        }
        if (closing || draining) { return; }
        draining = true;
        if (stream != null) {
            // A stream may never end by itself, so it is cancelled, not drained. The client sees the
            // body cut off, never a normal end, which is what a truncated download must look like.
            stream.abort(StreamAbortedException.Reason.SHUTDOWN);
            abort(context);
            return;
        }
        if (!busy) { abort(context); }
    }

    @Override protected void channelRead0(ChannelHandlerContext ctx, HttpObject message) {
        // After an error, requests already decoded from the same read are dropped unexecuted.
        if (closing || deferredStatus != 0) { return; }
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
        receivingHead = request.method().name().equals("HEAD");
        // The request beyond the bound is refused before its body is read; earlier ones still complete.
        if (pending.size() + (busy ? 1 : 0) >= maxPipelined) { fail(ctx, 503); return false; }
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
            var codings = headers.getAll(HttpHeaderNames.TRANSFER_ENCODING);
            // Repeated field lines are rejected outright rather than combined, so no intermediary
            // can read the framing differently (the decoder already rejects lists that do not end in
            // chunked or repeat it). A single list with another coding before chunked has
            // unambiguous framing and an unsupported coding, so RFC 9112 6.1 suggests 501.
            if (codings.size() > 1) { fail(ctx, 400); return false; }
            if (chunked && !codings.getFirst().trim().equalsIgnoreCase(HttpHeaderValues.CHUNKED.toString())) {
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
            // The peer is resolved by the socket; embedded test channels have no IP peer.
            var peer = ctx.channel().remoteAddress() instanceof InetSocketAddress address && !address.isUnresolved()
                    ? address : null;
            receiving = new Exchange(Request.fromTarget(request.method().name(), request.uri()).withHeaders(fields)
                    .withRemoteAddress(peer).withTls(ctx.pipeline().get(SslHandler.class) != null),
                    HttpUtil.isKeepAlive(request), http10, ExecutionContext.create(application.requestTimeout()));
        } catch (IllegalArgumentException invalid) { fail(ctx, 400); return false; }
        if (chunked || length > 0) {
            bodyChunked = chunked;
            declaredLength = length;
            // Declared bodies are reserved in full against the per-connection bound up front.
            if (!chunked && queuedBodyBytes + length > 2L * bodyLimit) { fail(ctx, 503); return false; }
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
        if (queuedBodyBytes + total > 2L * bodyLimit) { fail(ctx, 503); return false; }
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
        receivingHead = false;
        dispatch(ctx);
    }

    /**
     * Writes {@code 100 Continue} for the request being received once every earlier response
     * has been written, so the interim response never overtakes a pipelined final response.
     */
    private void sendContinue(ChannelHandlerContext ctx) {
        if (!continuePending || busy || !pending.isEmpty() || closing || deferredStatus != 0) { return; }
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
        if (busy || closing) { return; }
        if (pending.isEmpty()) {
            // Every earlier response has been written; a deferred error is the last response.
            if (deferredStatus != 0) { sendError(ctx, deferredStatus, deferredHead); }
            return;
        }
        // Reads stay enabled so a client disconnect cancels the running handler; the
        // pipeline bound checked in accept keeps buffered requests finite.
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
                try {
                    var response = application.handle(exchange.request(), exchange.execution());
                    return response.isStreaming() ? stream(ctx, exchange, response) : prepare(response, exchange);
                } catch (Exception | Error failure) {
                    logFailure(exchange, failure);
                    throw failure;
                }
            }, WireResponse::status);
            active.result().whenComplete((response, thrown) -> {
                var failure = unwrap(thrown);
                try {
                    ctx.executor().execute(() -> {
                        active = null; // The outcome is final; only the response write remains.
                        // The head of a stream is out, so there is no response left to choose: it ends
                        // with a final chunk when the body completed, and by closing the connection otherwise.
                        if (stream != null) { endStream(ctx, failure); return; }
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

    /** Applies the shared serialization rules; a response they refuse becomes a framework 500. */
    private static WireResponse prepare(Response response, Exchange exchange) {
        var bytes = ResponseSerialization.bodyBytes(response.body());
        if (bytes == null || !ResponseSerialization.headersSendable(response.headers())) { return error(500, exchange); }
        return new WireResponse(response.status(), response.headers(), bytes, closeRequested(response.headers()), false);
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
            } else if (head) {
                // No body bytes follow; the length is the GET representation's, when known.
                var length = headLength(response);
                if (length != null) { message.headers().set(HttpHeaderNames.CONTENT_LENGTH, length); }
            }
            message.headers().set("X-Request-ID", exchange.execution().requestId());
            message.headers().set(HttpHeaderNames.DATE, httpDate());
            boolean ending = draining || listenerClosing.getAsBoolean();
            boolean keepAlive = exchange.keepAlive() && !response.close() && !ending;
            HttpUtil.setKeepAlive(message, keepAlive);
            // HTTP/1.0 clients assume close unless persistence is acknowledged explicitly.
            if (keepAlive && exchange.http10()) { message.headers().set(HttpHeaderNames.CONNECTION, "keep-alive"); }
            if (!keepAlive) { closing = true; pending.clear(); queuedBodyBytes = 0; releaseBody(); }
            owned = null;
            finishWrite(ctx, ctx.writeAndFlush(message), keepAlive && !ending);
        } catch (RuntimeException failure) {
            if (owned != null) { owned.release(); }
            abort(ctx);
        }
    }

    /**
     * Bounds the write of the last bytes of a response and, once the socket has taken them, ends
     * the exchange: the connection lingers when it is not persistent, otherwise it serves the next
     * request.
     */
    private void finishWrite(ChannelHandlerContext ctx, ChannelFuture written, boolean persistent) {
        // Most responses are taken by the socket at once; only a pending write needs a bound.
        if (!written.isDone()) {
            responseTimer = ctx.executor().schedule(() -> { responseTimer = null; abort(ctx); },
                    responseNanos, TimeUnit.NANOSECONDS);
        }
        written.addListener(future -> {
            if (responseTimer != null) { responseTimer.cancel(false); responseTimer = null; }
            active = null;
            if (!future.isSuccess()) { ctx.close(); return; }
            if (!persistent) { linger(ctx); return; }
            busy = false;
            dispatch(ctx);
            sendContinue(ctx);
        });
    }

    private static boolean closeRequested(Map<String, String> headers) {
        return Arrays.stream(headers.getOrDefault("Connection", "").split(","))
                .anyMatch(token -> token.trim().equalsIgnoreCase("close"));
    }

    private static void logFailure(Exchange exchange, Throwable failure) {
        if (failure instanceof StreamAbortedException aborted) {
            // Expected outcomes of a stream: a client leaving or shutdown is not an error. The cap is
            // the application's mistake, so it is worth a warning.
            var level = aborted.reason() == StreamAbortedException.Reason.LIMIT_EXCEEDED
                    ? System.Logger.Level.WARNING : System.Logger.Level.DEBUG;
            LOG.log(level, "HTTP request " + exchange.execution().requestId() + " stream aborted: " + aborted.reason());
        } else if (!exchange.execution().isExpired() && !Thread.currentThread().isInterrupted()) {
            LOG.log(System.Logger.Level.ERROR, "HTTP request " + exchange.execution().requestId() + " failed", failure);
        }
    }

    /**
     * Sends the head of a streamed response, then runs its body on the calling handler thread, so
     * the admission slot and the deadline cover the whole stream. Returns once the body has
     * finished; the final chunk is written by {@link #endStream} on the event loop. A body that
     * ends in failure, or whose writer was aborted even if the body swallowed that, fails the task:
     * the head is out, so the only honest ending is closing the connection.
     */
    private WireResponse stream(ChannelHandlerContext ctx, Exchange exchange, Response response) throws Exception {
        if (!ResponseSerialization.headersSendable(response.headers())) { return error(500, exchange); }
        boolean close = closeRequested(response.headers());
        var writer = new ChannelBodyWriter(ctx.channel(), exchange.execution(), response.streamLimit(),
                responseNanos, streamMetrics);
        boolean started;
        try {
            started = ctx.executor().submit(() -> beginStream(ctx, exchange, response, close, writer)).get();
        } catch (java.util.concurrent.ExecutionException | RejectedExecutionException unavailable) {
            started = false;
        }
        // Nothing was sent yet, so a refusal can still be an ordinary response.
        if (!started) { return error(503, exchange); }
        streamMetrics.started();
        Throwable failure = null;
        try {
            response.streamBody().writeTo(writer);
        } catch (Exception | Error thrown) {
            failure = thrown;
            throw thrown;
        } finally {
            writer.end();
            var reason = writer.aborted();
            streamMetrics.finished(reason != null ? StreamMetrics.Outcome.of(reason)
                    : failure != null ? (exchange.execution().isExpired() ? StreamMetrics.Outcome.TIMEOUT
                            : StreamMetrics.Outcome.FAILED) : StreamMetrics.Outcome.COMPLETED, writer.bytesWritten());
        }
        var reason = writer.aborted();
        if (reason != null) { throw new StreamAbortedException(reason); }
        return new WireResponse(response.status(), Map.of(), new byte[0], close, true);
    }

    /** Event loop: writes the head of a streamed response; false when the connection can no longer take it. */
    private boolean beginStream(ChannelHandlerContext ctx, Exchange exchange, Response response, boolean close,
            ChannelBodyWriter writer) {
        if (closing || draining || listenerClosing.getAsBoolean() || !ctx.channel().isActive()
                || exchange.execution().isExpired()) {
            return false;
        }
        var message = new DefaultHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.valueOf(response.status()));
        var omitted = new HashSet<>(HOP_HEADERS);
        for (var token : response.headers().getOrDefault("Connection", "").split(",")) {
            omitted.add(token.trim().toLowerCase(Locale.ROOT));
        }
        response.headers().forEach((name, value) -> {
            if (!omitted.contains(name.toLowerCase(Locale.ROOT))) { message.headers().set(name, value); }
        });
        // HTTP/1.0 has no chunked coding: the body is delimited by closing the connection.
        boolean keepAlive = exchange.keepAlive() && !close && !exchange.http10();
        if (!exchange.http10()) { message.headers().set(HttpHeaderNames.TRANSFER_ENCODING, HttpHeaderValues.CHUNKED); }
        message.headers().set("X-Request-ID", exchange.execution().requestId());
        message.headers().set(HttpHeaderNames.DATE, httpDate());
        HttpUtil.setKeepAlive(message, keepAlive);
        stream = writer;
        streamKeepAlive = keepAlive;
        ctx.writeAndFlush(message).addListener(future -> {
            if (!future.isSuccess()) { writer.abort(StreamAbortedException.Reason.CLIENT_DISCONNECTED); ctx.close(); }
        });
        return true;
    }

    /** Event loop: the body task has ended; finish the response or drop the connection. */
    private void endStream(ChannelHandlerContext ctx, Throwable failure) {
        var finished = stream;
        stream = null;
        if (closing || !ctx.channel().isActive()) { return; }
        if (failure != null || finished.aborted() != null) { abort(ctx); return; }
        boolean keepAlive = streamKeepAlive;
        if (!keepAlive) { closing = true; pending.clear(); queuedBodyBytes = 0; releaseBody(); }
        finishWrite(ctx, ctx.writeAndFlush(LastHttpContent.EMPTY_LAST_CONTENT), keepAlive);
    }

    /**
     * The length a successful HEAD response advertises: the representation length the application
     * computed (see {@code Application.handle}), for 2xx other than 204 and 205. Errors, bodiless
     * statuses and anything that is not a plain decimal length send none.
     */
    private static String headLength(WireResponse response) {
        int status = response.status();
        if (status < 200 || status > 299 || status == 204 || status == 205) { return null; }
        var value = response.headers().get("Content-Length");
        return value != null && CONTENT_LENGTH.matcher(value).matches() ? value : null;
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
                Problems.body(status, HttpStatus.defaultCode(status), exchange.execution().requestId(), List.of()), true,
                false);
    }

    /**
     * Rejects the request being received (or the input that could not be parsed). Nothing after it
     * is decoded or executed: input is discarded from now on, because after a framing error its
     * boundaries cannot be trusted. Earlier pipelined requests, running or queued, still complete
     * and are answered in order; the error follows them and closes the connection.
     */
    private void fail(ChannelHandlerContext ctx, int status) {
        if (closing || deferredStatus != 0) { return; }
        boolean head = receivingHead;
        receivingHead = false;
        headComplete();
        releaseBody();
        receiving = null;
        var decoder = ctx.pipeline().get(RequestDecoder.class);
        if (decoder != null) { decoder.discard(maxDiscardedInput); }
        if (busy || !pending.isEmpty()) { deferredStatus = status; deferredHead = head; return; }
        sendError(ctx, status, head);
    }

    /**
     * Half-closes after the last response, whatever ended the connection (an error, {@code Connection:
     * close}, HTTP/1.0, a drain): the client reads the response and end of stream, and input it is
     * still sending is discarded rather than left unread, which would make the operating system reset
     * the connection and could destroy response bytes not yet delivered. The connection closes when
     * the client closes its side, once no input has arrived for {@link #LINGER_QUIET_TIMEOUT}, after
     * {@link #LINGER_TIMEOUT} in total ({@link #SHUTDOWN_LINGER_TIMEOUT} while the listener closes),
     * after {@link #MAX_DISCARDED_INPUT} bytes, or on inactivity, whichever comes first.
     */
    private void linger(ChannelHandlerContext ctx) {
        var decoder = ctx.pipeline().get(RequestDecoder.class);
        if (!(ctx.channel() instanceof DuplexChannel duplex) || decoder == null || !ctx.channel().isActive()) {
            ctx.close();
            return;
        }
        lingering = true;
        // Only discarding remains, bounded in time and bytes, so the connection stops keeping
        // new clients out (when the listener's separate lingering bound allows).
        if (slot != null) { slot.linger(); }
        decoder.discard(maxDiscardedInput);
        long now = now(ctx);
        lastInput = now;
        boolean shuttingDown = draining || listenerClosing.getAsBoolean();
        lingerDeadline = now + (shuttingDown ? Math.min(lingerNanos, shutdownLingerNanos) : lingerNanos);
        scheduleLingerCheck(ctx, now);
        // A TLS connection says goodbye with close_notify before the TCP half-close.
        var ssl = ctx.pipeline().get(SslHandler.class);
        if (ssl == null) {
            duplex.shutdownOutput().addListener(done -> { if (!done.isSuccess()) { ctx.close(); } });
        } else {
            ssl.closeOutbound().addListener(sent -> {
                if (!sent.isSuccess() || !ctx.channel().isActive()) { ctx.close(); return; }
                duplex.shutdownOutput().addListener(done -> { if (!done.isSuccess()) { ctx.close(); } });
            });
        }
    }

    /** Arms the single linger timer for whichever comes first: the deadline or the quiet period. */
    private void scheduleLingerCheck(ChannelHandlerContext ctx, long now) {
        if (lingerTimer != null) { lingerTimer.cancel(false); }
        long next = Math.min(lingerDeadline - now, lastInput + lingerQuietNanos - now);
        lingerTimer = ctx.executor().schedule(() -> {
            lingerTimer = null;
            long at = now(ctx);
            if (at - lingerDeadline >= 0 || at - lastInput >= lingerQuietNanos) { ctx.close(); }
            else { scheduleLingerCheck(ctx, at); }
        }, Math.max(0, next), TimeUnit.NANOSECONDS);
    }

    /** The event loop's clock, which tests can freeze and advance. */
    private static long now(ChannelHandlerContext ctx) { return ctx.executor().ticker().nanoTime(); }

    /** Sends a listener error; for a HEAD request the problem body is omitted as for any HEAD response. */
    private void sendError(ChannelHandlerContext ctx, int status, boolean head) {
        var exchange = new Exchange(new Request(head ? "HEAD" : "GET", "/"), false, false,
                ExecutionContext.create(application.requestTimeout()));
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
        if (lingerTimer != null) { lingerTimer.cancel(false); lingerTimer = null; }
        if (responseTimer != null) { responseTimer.cancel(false); responseTimer = null; }
        headComplete();
        releaseBody();
        receiving = null;
        pending.clear();
        queuedBodyBytes = 0;
        // The stream learns why before the cancellation interrupts its thread, so the reason is the disconnect.
        if (stream != null) { stream.abort(StreamAbortedException.Reason.CLIENT_DISCONNECTED); stream = null; }
        if (active != null) { active.cancel(); active = null; }
    }

    @Override public void channelWritabilityChanged(ChannelHandlerContext ctx) throws Exception {
        if (stream != null) { stream.signal(); }
        super.channelWritabilityChanged(ctx);
    }

    @Override public void userEventTriggered(ChannelHandlerContext ctx, Object event) throws Exception {
        // A running handler is bounded by its own deadline, so inactivity is ignored only while it
        // executes; idle connections and stalled response writes still close.
        if (event == RequestDecoder.DECODED) { inputDecoded(ctx); }
        // Input keeps a lingering connection open, up to the linger deadline.
        else if (event == RequestDecoder.DISCARDED) { if (lingering) { lastInput = now(ctx); } }
        // Closing keeps a client that keeps sending after an error from occupying the event loop.
        else if (event == RequestDecoder.DISCARD_LIMIT) { abort(ctx); }
        else if (event instanceof IdleStateEvent) { if (active == null) { abort(ctx); } }
        else { super.userEventTriggered(ctx, event); }
    }

    @Override public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) { abort(ctx); }

    private record CachedDate(long second, String value) { }
    private static volatile CachedDate date = new CachedDate(Long.MIN_VALUE, "");
    private record Exchange(Request request, boolean keepAlive, boolean http10, ExecutionContext execution) { }
    /** A prepared response; {@code streamed} marks a stream that was already written, with no body here. */
    private record WireResponse(int status, Map<String, String> headers, byte[] body, boolean close, boolean streamed) { }
}
