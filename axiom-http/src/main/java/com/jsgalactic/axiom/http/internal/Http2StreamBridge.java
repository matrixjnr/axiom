package com.jsgalactic.axiom.http.internal;

import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.handler.codec.http.HttpRequest;

/**
 * Starts a stream's request-head deadline when Netty's HTTP/2-to-HTTP/1 adapter emits the head.
 * The adapter converts HEADERS frames to {@link HttpRequest} objects; the connection handler that
 * follows applies the same limits as on HTTP/1.1 without depending on the HTTP/1 request decoder.
 */
final class Http2StreamBridge extends ChannelInboundHandlerAdapter {
    @Override public void channelRead(ChannelHandlerContext ctx, Object message) throws Exception {
        if (message instanceof HttpRequest) {
            var connection = ctx.pipeline().get(HttpConnection.class);
            if (connection != null) { connection.headStarted(ctx); }
        }
        ctx.fireChannelRead(message);
    }
}
