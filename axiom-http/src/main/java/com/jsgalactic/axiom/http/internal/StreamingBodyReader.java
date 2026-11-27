package com.jsgalactic.axiom.http.internal;

import com.jsgalactic.axiom.http.BodyReadException;
import com.jsgalactic.axiom.http.BodyReader;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import java.io.IOException;
import java.util.ArrayDeque;
import java.util.Objects;

/**
 * The listener's side of a streamed request body. Network buffers are copied into private arrays as
 * they arrive and released at once, and queued for the handler's virtual thread. The transport
 * turns channel reads off at the high water mark and back on below the low one, so a slow handler
 * pauses the client instead of accumulating body bytes. The same reservation used for buffered
 * bodies covers the bytes still in this queue, and is released when they are consumed or the
 * request ends. All state except the desired auto-read flag is guarded by {@code this}.
 */
final class StreamingBodyReader implements BodyReader {
  private static final int HIGH_WATER = 64 * 1024;
  private static final int LOW_WATER = 16 * 1024;
  private final ChannelHandlerContext context;
  private final BodyBudget.Reservation reservation;
  private final ArrayDeque<byte[]> chunks = new ArrayDeque<>();
  private long queued;
  private long received;
  private long bytesRead;
  private boolean ended;
  private boolean closed;
  private boolean handlerPaused;
  private boolean autoPaused;

  StreamingBodyReader(ChannelHandlerContext context, BodyBudget.Reservation reservation) {
    this.context = Objects.requireNonNull(context, "context");
    this.reservation = Objects.requireNonNull(reservation, "reservation");
  }

  /** Copies one network buffer into the queue; false when the listener's budget cannot take it. */
  boolean accept(ByteBuf content) {
    int length = content.readableBytes();
    byte[] bytes = new byte[length];
    content.readBytes(bytes);
    if (!reservation.grow(length)) {
      return false;
    }
    synchronized (this) {
      chunks.addLast(bytes);
      queued += length;
      received += length;
      if (queued > HIGH_WATER) {
        pauseReads();
      }
    }
    return true;
  }

  /** Marks the end of the body and wakes a handler waiting for its next byte. */
  void end() {
    synchronized (this) {
      ended = true;
      notifyAll();
    }
  }

  @Override
  public synchronized int read(byte[] bytes, int offset, int length) throws IOException {
    Objects.checkFromIndexSize(offset, length, bytes.length);
    while (true) {
      if (closed) {
        throw new BodyReadException(BodyReadException.Reason.CANCELLED);
      }
      if (handlerPaused) {
        waitForByte();
        continue;
      }
      if (!chunks.isEmpty()) {
        var chunk = chunks.peekFirst();
        int count = Math.min(length, chunk.length);
        System.arraycopy(chunk, 0, bytes, offset, count);
        bytesRead += count;
        queued -= count;
        chunks.removeFirst();
        if (count < chunk.length) {
          byte[] rest = new byte[chunk.length - count];
          System.arraycopy(chunk, count, rest, 0, rest.length);
          chunks.addFirst(rest);
        }
        reservation.shrinkTo(queued);
        if (queued < LOW_WATER) {
          resumeReads();
        }
        return count;
      }
      if (chunks.isEmpty() && ended) {
        return -1;
      }
      waitForByte();
    }
  }

  /** Waits for a signal; an interrupted handler fails with its interrupt flag restored. */
  private void waitForByte() throws IOException {
    try {
      wait();
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      throw new BodyReadException(BodyReadException.Reason.INTERRUPTED);
    }
  }

  @Override
  public synchronized void pause() {
    handlerPaused = true;
    pauseReads();
  }

  @Override
  public synchronized void resume() {
    handlerPaused = false;
    if (queued < HIGH_WATER) {
      resumeReads();
    }
    notifyAll();
  }

  @Override
  public synchronized boolean paused() {
    return handlerPaused || autoPaused;
  }

  @Override
  public synchronized long bytesRead() {
    return bytesRead;
  }

  /** Total bytes accepted from the network, whether or not the handler has read them. */
  synchronized long bytesReceived() {
    return received;
  }

  /** Bytes still held in the queue, which are the only ones the budget still needs to cover. */
  synchronized long queuedBytes() {
    return queued;
  }

  /** Reports whether the body ended and every accepted byte was consumed. */
  synchronized boolean finished() {
    return ended && chunks.isEmpty();
  }

  @Override
  public synchronized void close() {
    if (closed) {
      return;
    }
    closed = true;
    chunks.clear();
    queued = 0;
    handlerPaused = false;
    resumeReads();
    reservation.release();
    notifyAll();
  }

  /** Called on the event loop, or scheduled there when a handler thread changes the water mark. */
  private void pauseReads() {
    if (!autoPaused) {
      autoPaused = true;
      setAutoRead(false);
    }
  }

  private void resumeReads() {
    if (autoPaused) {
      autoPaused = false;
      setAutoRead(true);
    }
  }

  private void setAutoRead(boolean enabled) {
    if (context.executor().inEventLoop()) {
      apply(enabled);
    } else {
      context.executor().execute(() -> apply(enabled));
    }
  }

  private void apply(boolean enabled) {
    if (context.channel().isActive() && context.channel().config().isAutoRead() != enabled) {
      context.channel().config().setAutoRead(enabled);
    }
  }
}
