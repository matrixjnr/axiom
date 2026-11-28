package com.jsgalactic.axiom.http.internal;

import com.jsgalactic.axiom.execution.ExecutionContext;
import com.jsgalactic.axiom.http.Request;
import com.jsgalactic.axiom.server.internal.execution.RequestDispatcher;
import com.jsgalactic.axiom.websocket.WebSocketHandler;
import com.jsgalactic.axiom.websocket.WebSocketMessage;
import com.jsgalactic.axiom.websocket.WebSocketSession;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.codec.TooLongFrameException;
import io.netty.handler.codec.http.websocketx.BinaryWebSocketFrame;
import io.netty.handler.codec.http.websocketx.CloseWebSocketFrame;
import io.netty.handler.codec.http.websocketx.CorruptedWebSocketFrameException;
import io.netty.handler.codec.http.websocketx.PingWebSocketFrame;
import io.netty.handler.codec.http.websocketx.PongWebSocketFrame;
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame;
import io.netty.handler.codec.http.websocketx.WebSocketCloseStatus;
import io.netty.handler.codec.http.websocketx.WebSocketFrame;
import io.netty.handler.timeout.IdleStateEvent;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * The server side of one WebSocket connection. All mutable state belongs to the channel's event
 * loop; the public session methods may be called from any thread and hand their work to it. One
 * stream slot is held from the handshake until {@link #end} and released exactly once there.
 */
final class NettyWebSocketSession extends SimpleChannelInboundHandler<WebSocketFrame>
    implements WebSocketSession {
  private static final System.Logger LOG = System.getLogger(NettyWebSocketSession.class.getName());

  private final WebSocketHandler handler;
  private final Request request;
  private final ExecutionContext execution;
  private final Optional<InetSocketAddress> remote;
  private final RequestDispatcher dispatcher;
  private final Object admissionKey;
  private final ListenerMetrics metrics;
  private final AtomicBoolean closed = new AtomicBoolean();

  private volatile ChannelHandlerContext context;
  private volatile boolean started;
  private volatile Runnable readsPaused;
  private volatile Runnable readsResumed;
  private int pendingWrites;
  private boolean admitted;
  private boolean paused;

  NettyWebSocketSession(
      WebSocketHandler handler,
      Request request,
      ExecutionContext execution,
      Optional<InetSocketAddress> remote,
      RequestDispatcher dispatcher,
      Object admissionKey,
      boolean admitted,
      ListenerMetrics metrics) {
    this.handler = handler;
    this.request = request;
    this.execution = execution;
    this.remote = remote;
    this.dispatcher = dispatcher;
    this.admissionKey = admissionKey;
    this.admitted = admitted;
    this.metrics = metrics;
  }

  @Override
  public void handlerAdded(ChannelHandlerContext ctx) {
    context = ctx;
    ctx.executor()
        .execute(() -> {
          if (!closed.get()) {
            started = true;
            metrics.webSocket(ListenerMetrics.WebSocketOutcome.OPENED);
            callback(() -> handler.onConnect(this));
          }
        });
  }

  @Override
  protected void channelRead0(ChannelHandlerContext ctx, WebSocketFrame frame) {
    if (frame instanceof CloseWebSocketFrame close) {
      int code = close.statusCode();
      String reason = close.reasonText();
      metrics.frame("in", "control");
      end(code, reason == null ? "" : reason, true);
      return;
    }
    if (frame instanceof PingWebSocketFrame ping) {
      metrics.frame("in", "control");
      write(new PongWebSocketFrame(Unpooled.copiedBuffer(ping.content())), "control");
      return;
    }
    if (frame instanceof PongWebSocketFrame) {
      metrics.frame("in", "control");
      return;
    }
    if (frame instanceof TextWebSocketFrame || frame instanceof BinaryWebSocketFrame) {
      metrics.frame("in", frame instanceof TextWebSocketFrame ? "text" : "binary");
      if (frame.content().readableBytes() > WebSocketSupport.MAX_FRAME) {
        fail(WebSocketCloseStatus.MESSAGE_TOO_BIG, "Message too big");
        return;
      }
      WebSocketMessage message;
      try {
        message = WebSocketSupport.message(frame, frame.content());
      } catch (CorruptedWebSocketFrameException invalid) {
        fail(invalid.closeStatus(), invalid.getMessage());
        return;
      }
      callback(() -> handler.onMessage(this, message));
      return;
    }
    // A continuation that escaped the aggregator has no message of its own.
    metrics.frame("in", "control");
  }

  @Override
  public void channelWritabilityChanged(ChannelHandlerContext ctx) throws Exception {
    resumeReads();
    super.channelWritabilityChanged(ctx);
  }

  @Override
  public void userEventTriggered(ChannelHandlerContext ctx, Object event) throws Exception {
    if (event instanceof IdleStateEvent && !closed.get()) {
      end(WebSocketCloseStatus.ABNORMAL_CLOSURE.code(), "", false);
    } else {
      super.userEventTriggered(ctx, event);
    }
  }

  @Override
  public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
    var status = closeStatus(cause);
    callback(() -> handler.onError(this, cause));
    end(status.code(), status.reasonText(), true);
  }

  @Override
  public void channelInactive(ChannelHandlerContext ctx) {
    end(WebSocketCloseStatus.ABNORMAL_CLOSURE.code(), "", false);
  }

  @Override
  public void send(WebSocketMessage message) {
    Objects.requireNonNull(message, "message");
    ensureOpen();
    onLoop(() -> {
      if (message.isText()) {
        write(new TextWebSocketFrame(message.text()), "text");
      } else {
        write(new BinaryWebSocketFrame(Unpooled.wrappedBuffer(message.bytes())), "binary");
      }
    });
  }

  @Override
  public void sendText(String text) {
    send(WebSocketMessage.text(text));
  }

  @Override
  public void sendBinary(byte[] bytes) {
    send(WebSocketMessage.binary(bytes));
  }

  @Override
  public void ping(byte[] bytes) {
    Objects.requireNonNull(bytes, "bytes");
    ensureOpen();
    onLoop(() -> write(new PingWebSocketFrame(Unpooled.wrappedBuffer(bytes.clone())), "control"));
  }

  @Override
  public void close() {
    close(WebSocketCloseStatus.NORMAL_CLOSURE.code(), "");
  }

  @Override
  public void close(int code, String reason) {
    if (!WebSocketCloseStatus.isValidStatusCode(code)
        || code == WebSocketCloseStatus.EMPTY.code()
        || code == WebSocketCloseStatus.ABNORMAL_CLOSURE.code()) {
      throw new IllegalArgumentException("Invalid WebSocket close code: " + code);
    }
    var text = Objects.requireNonNull(reason, "reason");
    if (text.getBytes(StandardCharsets.UTF_8).length > 123) {
      throw new IllegalArgumentException("Close reason exceeds 123 bytes");
    }
    onLoop(() -> end(code, text, true));
  }

  @Override
  public boolean isOpen() {
    return !closed.get();
  }

  @Override
  public Optional<InetSocketAddress> remoteAddress() {
    return remote;
  }

  @Override
  public Request request() {
    return request;
  }

  @Override
  public ExecutionContext execution() {
    return execution;
  }

  /** Ends the session when the listener shuts down, with a close frame the peer can read. */
  void shutdown() {
    onLoop(() -> end(WebSocketCloseStatus.ENDPOINT_UNAVAILABLE.code(), "shutting down", true));
  }

  /** Whether reads are currently enabled; for backpressure tests. */
  boolean reading() {
    var ctx = context;
    return ctx == null || ctx.channel().config().isAutoRead();
  }

  /** Installs one-shot notifications for read pauses and resumes; for deterministic tests. */
  void observeReads(Runnable pausedAction, Runnable resumedAction) {
    readsPaused = pausedAction;
    readsResumed = resumedAction;
  }

  /** Whether the connect callback has run; for tests that coordinate without sleeps. */
  boolean started() {
    return started;
  }

  private void ensureOpen() {
    if (closed.get()) {
      throw new IllegalStateException("WebSocket session is closed");
    }
  }

  private void onLoop(Runnable action) {
    var ctx = context;
    if (ctx == null) {
      throw new IllegalStateException("WebSocket session is not attached to a channel");
    }
    if (ctx.executor().inEventLoop()) {
      action.run();
    } else {
      ctx.executor()
          .execute(() -> {
            try {
              action.run();
            } catch (RuntimeException failure) {
              callback(() -> handler.onError(this, failure));
            }
          });
    }
  }

  private void write(WebSocketFrame frame, String kind) {
    if (closed.get()) {
      frame.release();
      return;
    }
    pendingWrites++;
    metrics.frame("out", kind);
    ChannelFuture written = context.writeAndFlush(frame);
    if (pendingWrites >= WebSocketSupport.MAX_PENDING_WRITES || !context.channel().isWritable()) {
      pauseReads();
    }
    written.addListener(future -> {
      if (pendingWrites > 0) {
        pendingWrites--;
      }
      if (!future.isSuccess()) {
        end(WebSocketCloseStatus.ABNORMAL_CLOSURE.code(), "", false);
      } else {
        resumeReads();
      }
    });
  }

  private void pauseReads() {
    if (paused) {
      return;
    }
    paused = true;
    context.channel().config().setAutoRead(false);
    var notify = readsPaused;
    if (notify != null) {
      readsPaused = null;
      notify.run();
    }
  }

  private void resumeReads() {
    if (pendingWrites == 0 && paused && context != null && context.channel().isWritable()) {
      paused = false;
      context.channel().config().setAutoRead(true);
      var notify = readsResumed;
      if (notify != null) {
        readsResumed = null;
        notify.run();
      }
    }
  }

  private void fail(WebSocketCloseStatus status, String reason) {
    callback(() -> handler.onError(this, new IllegalArgumentException(reason)));
    end(status.code(), status.reasonText(), true);
  }

  private void end(int code, String reason, boolean goodbye) {
    if (!closed.compareAndSet(false, true)) {
      return;
    }
    if (admitted) {
      dispatcher.releaseStreamSlot(admissionKey);
      admitted = false;
    }
    metrics.webSocket(ListenerMetrics.WebSocketOutcome.CLOSED);
    callback(() -> handler.onClose(this, code, reason));
    var ctx = context;
    if (ctx == null) {
      return;
    }
    ctx.channel().config().setAutoRead(true);
    if (goodbye) {
      metrics.frame("out", "control");
      ctx.writeAndFlush(new CloseWebSocketFrame(code, reason))
          .addListener(ChannelFutureListener.CLOSE);
    } else {
      ctx.close();
    }
  }

  /** Runs one application callback, reporting its failure without failing the connection. */
  private void callback(Runnable action) {
    try {
      action.run();
    } catch (RuntimeException | Error failure) {
      try {
        handler.onError(this, failure);
      } catch (RuntimeException | Error nested) {
        LOG.log(System.Logger.Level.ERROR, "A WebSocket error handler failed", nested);
      }
    }
  }

  private static WebSocketCloseStatus closeStatus(Throwable cause) {
    if (cause instanceof CorruptedWebSocketFrameException corrupted
        && corrupted.closeStatus() != null) {
      return corrupted.closeStatus();
    }
    if (cause instanceof TooLongFrameException) {
      return WebSocketCloseStatus.MESSAGE_TOO_BIG;
    }
    return WebSocketCloseStatus.INTERNAL_SERVER_ERROR;
  }
}
