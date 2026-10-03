package com.jsgalactic.axiom.http.internal;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPromise;

/**
 * First handler of a connection's pipeline: counts the bytes that cross the socket in each
 * direction, before any TLS decryption and after any encryption, and passes everything on unchanged.
 */
final class ByteCounter extends ChannelDuplexHandler {
    private final ListenerMetrics metrics;

    ByteCounter(ListenerMetrics metrics) { this.metrics = metrics; }

    @Override public void channelRead(ChannelHandlerContext ctx, Object message) throws Exception {
        if (message instanceof ByteBuf buffer) { metrics.bytesIn(buffer.readableBytes()); }
        super.channelRead(ctx, message);
    }

    @Override public void write(ChannelHandlerContext ctx, Object message, ChannelPromise promise) throws Exception {
        if (message instanceof ByteBuf buffer) { metrics.bytesOut(buffer.readableBytes()); }
        super.write(ctx, message, promise);
    }
}
