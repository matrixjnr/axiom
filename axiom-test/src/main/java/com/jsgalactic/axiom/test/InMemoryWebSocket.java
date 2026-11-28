package com.jsgalactic.axiom.test;

import com.jsgalactic.axiom.execution.ExecutionContext;
import com.jsgalactic.axiom.execution.StreamPolicy;
import com.jsgalactic.axiom.http.Request;
import com.jsgalactic.axiom.server.internal.WebSocketAccess.WebSocketRoute;
import com.jsgalactic.axiom.server.internal.execution.RequestDispatcher;
import com.jsgalactic.axiom.websocket.WebSocketHandler;
import com.jsgalactic.axiom.websocket.WebSocketMessage;
import com.jsgalactic.axiom.websocket.WebSocketSession;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * A pair of in-memory sessions with the same message, error and close semantics as a live WebSocket
 * connection. No socket or event loop is involved: a send on one end calls the other end's handler
 * on the calling thread, as a live session delivers messages on its transport thread. Both ends
 * hold one stream slot from connect until close, shared with the application's other listeners and
 * tests.
 */
final class InMemoryWebSocket {
  /** The live transport's frame bound, so embedded tests see the same limit. */
  static final int MAX_MESSAGE = 64 * 1024;

  private static final System.Logger LOG = System.getLogger(InMemoryWebSocket.class.getName());
  private static final int NORMAL = 1000;
  private static final int ABNORMAL = 1006;

  private InMemoryWebSocket() {}

  /**
   * Connects an application handler to a test handler.
   *
   * @param route registered WebSocket route
   * @param applicationRequest handshake request seen by the server
   * @param clientRequest handshake request seen by the test
   * @param execution handshake execution metadata
   * @param clientHandler callbacks of the test end
   * @param dispatcher application-owned admission budget
   * @param streamPolicy application's aggregate stream bound
   * @return client end of the pair; the server end calls the registered handler
   */
  static WebSocketSession connect(
      WebSocketRoute route,
      Request applicationRequest,
      Request clientRequest,
      ExecutionContext execution,
      WebSocketHandler clientHandler,
      RequestDispatcher dispatcher,
      StreamPolicy streamPolicy) {
    Objects.requireNonNull(route, "route");
    Objects.requireNonNull(clientHandler, "clientHandler");
    var admitted = dispatcher.admitStreamSlot(route.admissionKey(), streamPolicy);
    var server =
        new Session(
            route.handler(),
            applicationRequest,
            execution,
            dispatcher,
            route.admissionKey(),
            admitted);
    var client = new Session(clientHandler, clientRequest, execution, dispatcher, null, false);
    server.peer = client;
    client.peer = server;
    server.connected();
    client.connected();
    return client;
  }

  private static final class Session implements WebSocketSession {
    private final WebSocketHandler handler;
    private final Request request;
    private final ExecutionContext execution;
    private final RequestDispatcher dispatcher;
    private final Object admissionKey;
    private final AtomicBoolean closed = new AtomicBoolean();
    private final AtomicBoolean admitted;

    private Session peer;
    private int closeCode = ABNORMAL;
    private String closeReason = "";

    private Session(
        WebSocketHandler handler,
        Request request,
        ExecutionContext execution,
        RequestDispatcher dispatcher,
        Object admissionKey,
        boolean admitted) {
      this.handler = handler;
      this.request = request;
      this.execution = execution;
      this.dispatcher = dispatcher;
      this.admissionKey = admissionKey;
      this.admitted = new AtomicBoolean(admitted);
    }

    private void connected() {
      callback(() -> handler.onConnect(this));
    }

    @Override
    public void send(WebSocketMessage message) {
      ensureOpen();
      Objects.requireNonNull(message, "message");
      if (message.length() > MAX_MESSAGE) {
        callback(() -> handler.onError(this, new IllegalArgumentException("Message too big")));
        close(1009, "Message too big");
        return;
      }
      peer.deliver(message);
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
      ensureOpen();
      Objects.requireNonNull(bytes, "bytes");
      // Pongs are a transport concern: there is no application callback for one.
    }

    @Override
    public void close() {
      close(NORMAL, "");
    }

    @Override
    public void close(int code, String reason) {
      if (!valid(code) || code == 1005 || code == ABNORMAL) {
        throw new IllegalArgumentException("Invalid WebSocket close code: " + code);
      }
      var text = Objects.requireNonNull(reason, "reason");
      if (text.getBytes(StandardCharsets.UTF_8).length > 123) {
        throw new IllegalArgumentException("Close reason exceeds 123 bytes");
      }
      if (!closed.compareAndSet(false, true)) {
        return;
      }
      closeCode = code;
      closeReason = text;
      release();
      var other = peer;
      if (other != null && other.closed.compareAndSet(false, true)) {
        other.closeCode = code;
        other.closeReason = text;
        other.release();
      }
      finished(this);
      if (other != null) {
        finished(other);
      }
    }

    @Override
    public boolean isOpen() {
      return !closed.get();
    }

    @Override
    public Optional<InetSocketAddress> remoteAddress() {
      return Optional.empty();
    }

    @Override
    public Request request() {
      return request;
    }

    @Override
    public ExecutionContext execution() {
      return execution;
    }

    private void deliver(WebSocketMessage message) {
      if (closed.get()) {
        return;
      }
      callback(() -> handler.onMessage(this, message));
    }

    private void finished(Session session) {
      callback(() -> session.handler.onClose(session, session.closeCode, session.closeReason));
    }

    private void release() {
      if (admissionKey != null && admitted.compareAndSet(true, false)) {
        dispatcher.releaseStreamSlot(admissionKey);
      }
    }

    private void ensureOpen() {
      if (closed.get()) {
        throw new IllegalStateException("WebSocket session is closed");
      }
    }

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

    private static boolean valid(int code) {
      return code == 1000 || (code >= 1001 && code <= 1011) || (code >= 3000 && code <= 4999);
    }
  }
}
