package com.jsgalactic.axiom.http.internal;

import static io.netty.buffer.Unpooled.unreleasableBuffer;
import static io.netty.handler.codec.http2.Http2CodecUtil.connectionPrefaceBuf;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.ByteToMessageDecoder;
import java.util.List;

/**
 * Chooses the wire protocol of a cleartext connection from its first bytes. A client that starts
 * with the HTTP/2 connection preface gets the HTTP/2 pipeline; every other client that sends bytes
 * gets the HTTP/1.x pipeline. The decision is made on the connection's event loop from bytes
 * already read, without waiting for a timeout, and the bytes that decided it are passed unchanged
 * to the chosen pipeline.
 */
final class Http2PrefaceDetector extends ByteToMessageDecoder {
  private static final ByteBuf PREFACE = unreleasableBuffer(connectionPrefaceBuf()).asReadOnly();
  private final Runnable http1;
  private final Runnable http2;

  Http2PrefaceDetector(Runnable http1, Runnable http2) {
    this.http1 = http1;
    this.http2 = http2;
  }

  @Override
  protected void decode(ChannelHandlerContext ctx, ByteBuf in, List<Object> out) {
    int prefix = Math.min(in.readableBytes(), PREFACE.readableBytes());
    if (!ByteBufUtil.equals(PREFACE, PREFACE.readerIndex(), in, in.readerIndex(), prefix)) {
      install(ctx, in, http1);
      return;
    }
    if (prefix < PREFACE.readableBytes()) {
      return;
    } // Wait for the rest of the preface.
    // The frame codec consumes the preface itself; leave every byte in place for it.
    install(ctx, in, http2);
  }

  private void install(ChannelHandlerContext ctx, ByteBuf in, Runnable pipeline) {
    pipeline.run();
    ctx.pipeline().remove(this);
    // Fire from the pipeline head so the bytes reach the handlers just installed.
    if (in.isReadable()) {
      ctx.channel().pipeline().fireChannelRead(in.retain());
    }
  }
}
