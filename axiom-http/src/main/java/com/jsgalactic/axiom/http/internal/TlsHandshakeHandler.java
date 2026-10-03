package com.jsgalactic.axiom.http.internal;

import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.handler.codec.DecoderException;
import io.netty.handler.ssl.NotSslRecordException;
import io.netty.handler.ssl.SslHandshakeCompletionEvent;
import io.netty.handler.ssl.SslHandshakeTimeoutException;
import java.nio.channels.ClosedChannelException;

/**
 * Sits behind the {@code SslHandler}, counts how each handshake ended and closes the connection
 * when it failed, so that a slow, garbled or plain-text client frees its connection slot at once.
 * Exactly one outcome is counted per connection. After the handshake it only passes events on.
 */
final class TlsHandshakeHandler extends ChannelInboundHandlerAdapter {
    private final TlsMetrics metrics;
    private boolean finished;

    TlsHandshakeHandler(TlsMetrics metrics) { this.metrics = metrics; }

    @Override public void userEventTriggered(ChannelHandlerContext ctx, Object event) throws Exception {
        if (event instanceof SslHandshakeCompletionEvent handshake && !finished) {
            if (handshake.isSuccess()) {
                finished = true;
                metrics.handshake(TlsMetrics.Handshake.COMPLETED);
            } else {
                // The SslHandler sends its alert and closes the connection itself; closing here could
                // cut the alert off, and the client would see a bare end of stream.
                finished = true;
                metrics.handshake(classify(handshake.cause()));
                ctx.fireUserEventTriggered(event);
                return;
            }
        }
        super.userEventTriggered(ctx, event);
    }

    @Override public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) throws Exception {
        if (finished) {
            super.exceptionCaught(ctx, cause);
        } else {
            failed(ctx, cause);
        }
    }

    @Override public void channelInactive(ChannelHandlerContext ctx) throws Exception {
        if (!finished) {
            finished = true;
            metrics.handshake(TlsMetrics.Handshake.CLOSED);
        }
        super.channelInactive(ctx);
    }

    private void failed(ChannelHandlerContext ctx, Throwable cause) {
        if (!finished) {
            finished = true;
            metrics.handshake(classify(cause));
        }
        ctx.close();
    }

    private static TlsMetrics.Handshake classify(Throwable cause) {
        for (var next = cause; next != null; next = next.getCause() == next ? null : next.getCause()) {
            if (next instanceof SslHandshakeTimeoutException) { return TlsMetrics.Handshake.TIMEOUT; }
            if (next instanceof ClosedChannelException) { return TlsMetrics.Handshake.CLOSED; }
            if (next instanceof NotSslRecordException) { return TlsMetrics.Handshake.PLAINTEXT; }
            if (!(next instanceof DecoderException)) { break; }
        }
        return TlsMetrics.Handshake.FAILED;
    }
}
