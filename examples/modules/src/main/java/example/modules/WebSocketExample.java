package example.modules;

import com.jsgalactic.axiom.Axiom;
import com.jsgalactic.axiom.application.Application;
import com.jsgalactic.axiom.websocket.WebSocketHandler;
import com.jsgalactic.axiom.websocket.WebSocketMessage;
import com.jsgalactic.axiom.websocket.WebSocketSession;

/**
 * Registers an opt-in WebSocket echo route on an HTTP/1.1 listener. The handler owns each session
 * only for the duration of its callbacks; the application and its listener own the connection and
 * close every session when the listener drains.
 */
public final class WebSocketExample {
  private WebSocketExample() {}

  /**
   * Builds an application with a text and binary echo route at {@code /echo}.
   *
   * @param handler session callbacks; shared by all sessions and thread-safe
   * @return a configured application, not yet started
   */
  public static Application create(WebSocketHandler handler) {
    var app = Axiom.create();
    app.webSocket("/echo", handler);
    return app;
  }

  /**
   * Serves the echo route on ws://127.0.0.1:8080/echo until the process stops.
   *
   * @param args unused
   * @throws Exception if the listener cannot start
   */
  public static void main(String[] args) throws Exception {
    WebSocketHandler echo =
        new WebSocketHandler() {
          @Override
          public void onMessage(WebSocketSession session, WebSocketMessage message) {
            session.send(message);
          }
        };
    var app = create(echo).closeOnJvmShutdown();
    var server = app.listen(8080);
    System.out.println("WebSocket echo on ws://127.0.0.1:8080/echo");
    server.termination().toCompletableFuture().join();
  }
}
