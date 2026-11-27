package com.jsgalactic.axiom.http.internal;

import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.handler.codec.http2.DefaultHttp2GoAwayFrame;
import io.netty.handler.codec.http2.Http2Error;
import io.netty.handler.timeout.IdleStateEvent;

/**
 * Stops an HTTP/2 connection from accepting new streams when it is idle or when its listener
 * closes. A GOAWAY lets a client finish the exchanges it already started; the connection closes
 * when the last of those streams ends, and the listener's grace period still bounds the wait.
 */
final class Http2GracefulCloseHandler extends ChannelInboundHandlerAdapter {
    private final Http2Streams streams;
    private ChannelHandlerContext context;
    private boolean gone;

    Http2GracefulCloseHandler(Http2Streams streams) { this.streams = streams; }

    @Override public void handlerAdded(ChannelHandlerContext ctx) { context = ctx; }

    @Override public void userEventTriggered(ChannelHandlerContext ctx, Object event) throws Exception {
        if (event instanceof IdleStateEvent && !streams.busy()) { goAway(); }
        else { super.userEventTriggered(ctx, event); }
    }

    /** Called from the listener's event loop when shutdown begins; idempotent. */
    void goAway() {
        if (gone || !context.channel().isActive()) { return; }
        gone = true;
        context.writeAndFlush(new DefaultHttp2GoAwayFrame(Http2Error.NO_ERROR)).addListener(written -> {
            if (!written.isSuccess()) { context.close(); }
        });
        streams.closeWhenDone();
    }
}
