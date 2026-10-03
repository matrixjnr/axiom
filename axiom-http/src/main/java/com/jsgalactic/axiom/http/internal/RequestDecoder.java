package com.jsgalactic.axiom.http.internal;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http.HttpDecoderConfig;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpRequestDecoder;
import io.netty.handler.codec.http.LastHttpContent;
import io.netty.util.ByteProcessor;
import java.util.List;

/**
 * The request decoder, which also reports whether a request head has started arriving. After the
 * bytes of each read are decoded it fires {@link #DECODED}, so the connection can start a head
 * deadline for a request whose first bytes arrived in the same read as the end of the previous one.
 * After {@link #discard} it releases all further input unread. Owned by the channel's event loop.
 */
final class RequestDecoder extends HttpRequestDecoder {
    /** User event fired after the bytes of one read have been decoded. */
    static final Object DECODED = new Object();
    /** User event fired after the bytes of one read have been discarded unread. */
    static final Object DISCARDED = new Object();
    /** User event fired once when discarded input exceeds the limit given to {@link #discard}. */
    static final Object DISCARD_LIMIT = new Object();
    /** Bytes that may still be discarded before {@link #DISCARD_LIMIT} fires. */
    private long discardable;
    private boolean discarding;
    /** Between a request's head and its last content. */
    private boolean inMessage;
    /** Bytes other than the line breaks allowed before a request line have arrived for the next head. */
    private boolean headStarted;

    RequestDecoder(HttpDecoderConfig config) { super(config); }

    /** True while a request head has partly arrived and is not yet complete. */
    boolean headStarted() { return headStarted; }

    /**
     * Stops decoding: input still buffered and every later read is released without being parsed.
     * After {@code limit} such bytes {@link #DISCARD_LIMIT} is fired once. Calling it again resets
     * the remaining budget.
     */
    void discard(long limit) {
        discarding = true;
        headStarted = false;
        discardable = limit;
    }

    private void discarded(ChannelHandlerContext ctx, int bytes) {
        boolean within = discardable >= 0;
        discardable -= bytes;
        if (within && discardable < 0) { ctx.fireUserEventTriggered(DISCARD_LIMIT); }
    }

    @Override public void channelRead(ChannelHandlerContext ctx, Object message) throws Exception {
        if (discarding && message instanceof ByteBuf buffer) {
            int bytes = buffer.readableBytes();
            buffer.release();
            discarded(ctx, bytes);
            if (bytes > 0) { ctx.fireUserEventTriggered(DISCARDED); }
            return;
        }
        boolean bytes = message instanceof ByteBuf buffer && buffer.isReadable();
        super.channelRead(ctx, message);
        if (bytes) { ctx.fireUserEventTriggered(DECODED); }
    }

    @Override protected void decode(ChannelHandlerContext ctx, ByteBuf buffer, List<Object> out) throws Exception {
        if (discarding) {
            int bytes = buffer.readableBytes();
            buffer.skipBytes(bytes);
            discarded(ctx, bytes);
            return;
        }
        // RFC 9112 section 2.2: empty lines before a request line are ignored, so they do not start a head.
        if (!inMessage && !headStarted) { headStarted = buffer.forEachByte(ByteProcessor.FIND_NON_CRLF) != -1; }
        int first = out.size();
        super.decode(ctx, buffer, out);
        for (int i = first; i < out.size(); i++) {
            var decoded = out.get(i);
            if (decoded instanceof HttpRequest) { inMessage = true; headStarted = false; }
            if (decoded instanceof LastHttpContent) { inMessage = false; }
        }
    }
}
