package io.axiom.http.internal;

import io.axiom.application.Application;
import io.axiom.execution.ExecutionContext;
import io.axiom.http.Request;
import io.axiom.http.Response;
import io.axiom.server.internal.execution.RequestDispatcher.DeadlineExceededException;
import io.axiom.server.internal.execution.RequestDispatcher.DispatchRejectedException;
import io.axiom.server.internal.execution.RequestDispatcher.QueueTimeoutException;
import io.axiom.server.internal.execution.RequestDispatcher;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.HttpContent;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpObject;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpUtil;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.handler.codec.http.LastHttpContent;
import io.netty.handler.timeout.IdleStateEvent;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.RejectedExecutionException;

/** All mutable connection state belongs to the channel's event loop. */
final class HttpConnection extends SimpleChannelInboundHandler<HttpObject> {
    private static final System.Logger LOG = System.getLogger(HttpConnection.class.getName());
    private static final Object UNMATCHED = new Object();
    private static final int MAX_RESPONSE = 1024 * 1024;
    private static final Set<String> HOP_HEADERS = Set.of("connection", "keep-alive", "transfer-encoding",
            "content-length", "trailer", "upgrade", "proxy-authenticate", "proxy-authorization", "te");
    private final Application application;
    private final RequestDispatcher executor;
    private final ArrayDeque<Exchange> pending = new ArrayDeque<>();
    private Exchange receiving;
    private boolean busy;
    private boolean closing;
    private RequestDispatcher.Task<WireResponse> active;

    HttpConnection(Application application, RequestDispatcher executor) {
        this.application = application;
        this.executor = executor;
    }

    @Override protected void channelRead0(ChannelHandlerContext ctx, HttpObject message) {
        if (closing) { return; }
        if (!message.decoderResult().isSuccess()) { fail(ctx, 400); return; }
        if (message instanceof HttpRequest request) {
            if (receiving != null) { fail(ctx, 400); return; }
            if (!request.protocolVersion().equals(HttpVersion.HTTP_1_1)) { fail(ctx, 505); return; }
            if (!validHost(request)) { fail(ctx, 400); return; }
            if (request.headers().contains(HttpHeaderNames.EXPECT)) { fail(ctx, 417); return; }
            if (request.method().name().equals("CONNECT") || request.headers().contains(HttpHeaderNames.UPGRADE)) { fail(ctx, 501); return; }
            try {
                if (request.headers().contains(HttpHeaderNames.TRANSFER_ENCODING)
                        || HttpUtil.getContentLength(request, 0) != 0) {
                    fail(ctx, 501); return;
                }
                var target = request.uri();
                if (!target.startsWith("/") || target.indexOf('#') >= 0) { fail(ctx, 400); return; }
                var uri = URI.create("http://axiom.invalid" + target);
                receiving = new Exchange(new Request(request.method().name(), uri.getRawPath()),
                        HttpUtil.isKeepAlive(request), ExecutionContext.create(application.requestTimeout()));
            } catch (IllegalArgumentException invalid) { fail(ctx, 400); return; }
        }
        if (message instanceof HttpContent content) {
            if (receiving == null || content.content().isReadable()) { fail(ctx, 400); return; }
            if (message instanceof LastHttpContent) {
                if (pending.size() + (busy ? 1 : 0) >= 8) { abort(ctx); return; }
                pending.addLast(receiving);
                receiving = null;
                dispatch(ctx);
            }
        }
    }

    private static boolean validHost(HttpRequest request) {
        var hosts = request.headers().getAll(HttpHeaderNames.HOST);
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
        busy = true;
        ctx.channel().config().setAutoRead(false);
        var exchange = pending.removeFirst();
        if (exchange.execution().isExpired()) { send(ctx, exchange, error(504)); return; }
        try {
            var route = application.resolve(exchange.request());
            var policy = route.map(application::admissionPolicy).orElseGet(application::admissionPolicy);
            active = executor.submit(route.<Object>map(value -> value).orElse(UNMATCHED),
                    policy, exchange.execution(), () -> {
                try { return prepare(application.handle(exchange.request(), exchange.execution())); }
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
                        if (closing || !ctx.channel().isActive()) { return; }
                        if (failure instanceof Error) {
                            abort(ctx);
                        } else {
                            send(ctx, exchange, failure == null ? response
                                    : error(failure instanceof DeadlineExceededException ? 504
                                            : failure instanceof QueueTimeoutException
                                                    || failure instanceof DispatchRejectedException ? 503 : 500));
                        }
                    });
                } catch (RejectedExecutionException stopped) { /* Channel shutdown owns cleanup. */ }
            });
        } catch (RejectedExecutionException overloaded) {
            active = null;
            send(ctx, exchange, error(503));
        }
    }

    private static Throwable unwrap(Throwable failure) {
        while (failure instanceof java.util.concurrent.CompletionException && failure.getCause() != null) {
            failure = failure.getCause();
        }
        return failure;
    }

    private static WireResponse prepare(Response response) {
        var body = response.body();
        byte[] bytes;
        if (body == null) { bytes = new byte[0]; }
        else if (body instanceof byte[] value) { bytes = value; }
        else if (body instanceof String value && value.length() <= MAX_RESPONSE) {
            bytes = value.getBytes(StandardCharsets.UTF_8);
        } else { return error(500); }
        if (bytes.length > MAX_RESPONSE) { return error(500); }
        int headerSize = 0;
        for (var header : response.headers().entrySet()) {
            headerSize += header.getKey().length() + header.getValue().length() + 4;
            if (headerSize > 8192 || header.getValue().chars().anyMatch(c -> c > 255)) { return error(500); }
        }
        boolean close = java.util.Arrays.stream(response.headers().getOrDefault("Connection", "").split(","))
                .anyMatch(token -> token.trim().equalsIgnoreCase("close"));
        return new WireResponse(response.status(), response.headers(), bytes, close);
    }

    private void send(ChannelHandlerContext ctx, Exchange exchange, WireResponse response) {
        boolean head = exchange.request().method().equals("HEAD");
        var message = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1,
                HttpResponseStatus.valueOf(response.status()),
                Unpooled.wrappedBuffer(head ? new byte[0] : response.body()));
        try {
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
            boolean keepAlive = exchange.keepAlive() && !response.close();
            HttpUtil.setKeepAlive(message, keepAlive);
            if (!keepAlive) { closing = true; pending.clear(); }
            ctx.writeAndFlush(message).addListener(future -> {
                active = null;
                if (!future.isSuccess() || !keepAlive) { ctx.close(); return; }
                busy = false;
                if (pending.isEmpty()) { ctx.channel().config().setAutoRead(true); }
                else { dispatch(ctx); }
            });
        } catch (RuntimeException failure) {
            message.release();
            abort(ctx);
        }
    }

    private static WireResponse error(int status) {
        return new WireResponse(status, Map.of("Content-Type", "text/plain; charset=utf-8"),
                HttpResponseStatus.valueOf(status).reasonPhrase().getBytes(StandardCharsets.UTF_8), true);
    }

    private void fail(ChannelHandlerContext ctx, int status) {
        // Never send an error ahead of an earlier pipelined response.
        if (busy || !pending.isEmpty()) { abort(ctx); return; }
        send(ctx, new Exchange(Request.get("/"), false, ExecutionContext.create(application.requestTimeout())), error(status));
    }

    private void abort(ChannelHandlerContext ctx) { closing = true; ctx.close(); }

    @Override public void channelInactive(ChannelHandlerContext ctx) {
        closing = true;
        receiving = null;
        pending.clear();
        if (active != null) { active.cancel(); active = null; }
    }

    @Override public void userEventTriggered(ChannelHandlerContext ctx, Object event) throws Exception {
        if (event instanceof IdleStateEvent) { abort(ctx); }
        else { super.userEventTriggered(ctx, event); }
    }

    @Override public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) { abort(ctx); }

    private record Exchange(Request request, boolean keepAlive, ExecutionContext execution) { }
    private record WireResponse(int status, Map<String, String> headers, byte[] body, boolean close) { }
}
