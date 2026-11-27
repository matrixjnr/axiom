package com.jsgalactic.axiom.http.internal;

import io.netty.bootstrap.Bootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.MultiThreadIoEventLoopGroup;
import io.netty.channel.nio.NioIoHandler;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.codec.http2.DefaultHttp2DataFrame;
import io.netty.handler.codec.http2.DefaultHttp2Headers;
import io.netty.handler.codec.http2.DefaultHttp2HeadersFrame;
import io.netty.handler.codec.http2.Http2DataFrame;
import io.netty.handler.codec.http2.Http2FrameCodecBuilder;
import io.netty.handler.codec.http2.Http2GoAwayFrame;
import io.netty.handler.codec.http2.Http2Headers;
import io.netty.handler.codec.http2.Http2HeadersFrame;
import io.netty.handler.codec.http2.Http2MultiplexHandler;
import io.netty.handler.codec.http2.Http2Settings;
import io.netty.handler.codec.http2.Http2SettingsFrame;
import io.netty.handler.codec.http2.Http2StreamChannel;
import io.netty.handler.codec.http2.Http2StreamChannelBootstrap;
import io.netty.handler.ssl.SslHandler;
import io.netty.util.ReferenceCountUtil;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLEngine;

/**
 * A real-socket HTTP/2 client for the listener tests. It uses Netty's own frame codec, so the
 * tests exercise HPACK, SETTINGS and flow control on the wire; every wait is on a future or a
 * latch completed by an event, never on a fixed delay. Requests, response bodies and trailers are
 * exposed as plain values. Without an SSL context the client uses h2c prior knowledge; with one it
 * requires ALPN {@code h2}.
 */
final class H2Client implements AutoCloseable {
    private final MultiThreadIoEventLoopGroup group = new MultiThreadIoEventLoopGroup(
            1, Thread.ofPlatform().name("axiom-h2-client-", 0).factory(), NioIoHandler.newFactory());
    private final Channel channel;
    private final SslHandler ssl;
    private final CompletableFuture<Integer> goAway = new CompletableFuture<>();
    private final CompletableFuture<Void> closed = new CompletableFuture<>();
    private final CompletableFuture<Http2Settings> settings = new CompletableFuture<>();

    H2Client(InetSocketAddress address, SSLContext context) throws Exception {
        var handshake = new SslHandler[1];
        var bootstrap = new Bootstrap().group(group).channel(NioSocketChannel.class)
                .handler(new ChannelInitializer<SocketChannel>() {
                    @Override protected void initChannel(SocketChannel socket) {
                        if (context != null) {
                            SSLEngine engine = context.createSSLEngine(address.getHostString(), address.getPort());
                            engine.setUseClientMode(true);
                            var parameters = engine.getSSLParameters();
                            parameters.setApplicationProtocols(new String[] {"h2"});
                            engine.setSSLParameters(parameters);
                            handshake[0] = new SslHandler(engine);
                            socket.pipeline().addLast(handshake[0]);
                        }
                        socket.pipeline().addLast(Http2FrameCodecBuilder.forClient().build(),
                                new Http2MultiplexHandler(new ChannelInitializer<Channel>() {
                                    @Override protected void initChannel(Channel stream) { }
                                }), new ConnectionHandler());
                    }
                });
        channel = bootstrap.connect(address).sync().channel();
        ssl = handshake[0];
        if (ssl != null) { ssl.handshakeFuture().sync(); }
    }

    /** The ALPN protocol this client negotiated, or null for a cleartext connection. */
    String protocol() { return ssl == null ? null : ssl.engine().getApplicationProtocol(); }

    /** Creates a stream whose response can be awaited. */
    Stream stream() throws Exception {
        var result = new StreamResult();
        var stream = new Http2StreamChannelBootstrap(channel).handler(new ChannelInitializer<Channel>() {
            @Override protected void initChannel(Channel child) { child.pipeline().addLast(result); }
        }).open().sync().get();
        return new Stream(stream, result);
    }

    /** A GOAWAY frame's error code, completed by the connection's event loop. */
    CompletableFuture<Integer> goAway() { return goAway; }

    /** Completion of the connection's close; used for shutdown tests. */
    CompletableFuture<Void> closed() { return closed; }

    /** The server's SETTINGS frame, for verifying the listener's advertised stream and header limits. */
    CompletableFuture<Http2Settings> settings() { return settings; }

    @Override public void close() {
        channel.close();
        group.shutdownGracefully(0, 1, TimeUnit.SECONDS).syncUninterruptibly();
    }

    private final class ConnectionHandler extends ChannelInboundHandlerAdapter {
        @Override public void channelRead(ChannelHandlerContext ctx, Object message) {
            if (message instanceof Http2GoAwayFrame frame) { goAway.complete((int) frame.errorCode()); }
            else if (message instanceof Http2SettingsFrame frame) { settings.complete(frame.settings()); }
            ReferenceCountUtil.release(message);
        }
        @Override public void channelInactive(ChannelHandlerContext ctx) { closed.complete(null); }
        @Override public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
            goAway.completeExceptionally(cause);
            closed.completeExceptionally(cause);
        }
    }

    /** The result of one HTTP/2 stream. */
    static final class Reply {
        final int status;
        final Http2Headers headers;
        final byte[] body;
        final Http2Headers trailers;

        Reply(int status, Http2Headers headers, byte[] body, Http2Headers trailers) {
            this.status = status;
            this.headers = headers;
            this.body = body;
            this.trailers = trailers;
        }

        int status() { return status; }

        String text() { return new String(body, StandardCharsets.UTF_8); }

        String header(String name) {
            var value = headers.get(name);
            return value == null ? null : value.toString();
        }

        String trailer(String name) {
            var value = trailers == null ? null : trailers.get(name);
            return value == null ? null : value.toString();
        }
    }

    /** One request/response exchange on a stream. */
    static final class Stream {
        private final Http2StreamChannel channel;
        private final StreamResult result;

        Stream(Http2StreamChannel channel, StreamResult result) {
            this.channel = channel;
            this.result = result;
        }

        /** Sends a request head and a body; an empty body ends the stream with the HEADERS frame. */
        Stream send(Http2Headers headers, byte[] body) {
            head(headers, body.length == 0);
            if (body.length > 0) { channel.writeAndFlush(new DefaultHttp2DataFrame(Unpooled.wrappedBuffer(body), true)); }
            return this;
        }

        /** Sends a request head that is followed by data frames. */
        Stream head(Http2Headers headers) { return head(headers, false); }

        private Stream head(Http2Headers headers, boolean end) {
            channel.writeAndFlush(new DefaultHttp2HeadersFrame(headers, end));
            return this;
        }

        /** Sends request data without END_STREAM, for a stream that continues with trailers. */
        Stream data(byte[] body) {
            channel.writeAndFlush(new DefaultHttp2DataFrame(Unpooled.wrappedBuffer(body), false));
            return this;
        }

        /** Ends a stream with trailing headers. */
        void trailers(Http2Headers headers) {
            channel.writeAndFlush(new DefaultHttp2HeadersFrame(headers, true));
        }

        /** Waits for the final response frame; fails if the stream ends without one. */
        Reply await() throws Exception { return result.done.get(30, TimeUnit.SECONDS); }

        /** Waits for the response head without waiting for its body. */
        Reply awaitHead() throws Exception { return result.head.get(30, TimeUnit.SECONDS); }

        /** The underlying stream channel, for flow-control and reset tests. */
        Channel channel() { return channel; }

        /** Waits until the stream channel has closed. */
        void awaitClose() throws Exception { channel.closeFuture().await(30_000); }
    }

    private static final class StreamResult extends ChannelInboundHandlerAdapter {
        private final CompletableFuture<Reply> head = new CompletableFuture<>();
        private final CompletableFuture<Reply> done = new CompletableFuture<>();
        private final ByteArrayOutputStream body = new ByteArrayOutputStream();
        private Http2Headers headers;

        @Override public void channelRead(ChannelHandlerContext ctx, Object message) throws Exception {
            if (message instanceof Http2HeadersFrame frame) {
                if (headers == null) {
                    headers = frame.headers();
                    head.complete(new Reply(status(), headers, new byte[0], null));
                }
                if (frame.isEndStream()) { finish(frame.headers()); }
            } else if (message instanceof Http2DataFrame frame) {
                ByteBuf content = frame.content();
                content.readBytes(body, content.readableBytes());
                if (frame.isEndStream()) { finish(null); }
            }
            ReferenceCountUtil.release(message);
        }

        private void finish(Http2Headers trailers) {
            var reply = new Reply(status(), headers, body.toByteArray(), trailers);
            head.complete(reply);
            done.complete(reply);
        }

        private int status() {
            var value = headers == null ? null : headers.status();
            return value == null ? 0 : Integer.parseInt(value.toString());
        }

        @Override public void channelInactive(ChannelHandlerContext ctx) {
            var failure = new IOException("Stream ended without a complete response");
            head.completeExceptionally(failure);
            done.completeExceptionally(failure);
        }

        @Override public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
            head.completeExceptionally(cause);
            done.completeExceptionally(cause);
        }
    }

    /** A request head with the mandatory HTTP/2 pseudo-headers. */
    static Http2Headers headers(String method, String path) {
        var headers = new DefaultHttp2Headers();
        headers.method(method);
        headers.scheme("http");
        headers.authority("localhost");
        headers.path(path);
        return headers;
    }
}
