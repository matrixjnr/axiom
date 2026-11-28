package com.jsgalactic.axiom.test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.jsgalactic.axiom.Axiom;
import com.jsgalactic.axiom.websocket.WebSocketHandler;
import com.jsgalactic.axiom.websocket.WebSocketMessage;
import com.jsgalactic.axiom.websocket.WebSocketSession;
import java.util.ArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class WebSocketTestClientTest {
  @Test
  void connectsMessagesAndClosesWithoutSockets() throws Exception {
    var app = Axiom.create();
    var serverMessages = new ArrayList<WebSocketMessage>();
    var serverClosed = new CountDownLatch(1);
    var serverCode = new AtomicInteger();
    var serverSession = new AtomicReference<WebSocketSession>();
    app.webSocket(
        "/ws",
        new WebSocketHandler() {
          @Override
          public void onConnect(WebSocketSession session) {
            serverSession.set(session);
          }

          @Override
          public void onMessage(WebSocketSession session, WebSocketMessage message) {
            serverMessages.add(message);
            session.send(message);
          }

          @Override
          public void onClose(WebSocketSession session, int code, String reason) {
            serverCode.set(code);
            serverClosed.countDown();
          }
        });
    var clientMessages = new ArrayList<WebSocketMessage>();
    var clientClosed = new CountDownLatch(1);
    var connected = new CountDownLatch(1);
    WebSocketHandler client =
        new WebSocketHandler() {
          @Override
          public void onConnect(WebSocketSession session) {
            connected.countDown();
          }

          @Override
          public void onMessage(WebSocketSession session, WebSocketMessage message) {
            clientMessages.add(message);
          }

          @Override
          public void onClose(WebSocketSession session, int code, String reason) {
            clientClosed.countDown();
          }
        };
    try (var test = TestClient.start(app)) {
      var session = test.webSocket("/ws", client);
      assertThat(connected.await(5, TimeUnit.SECONDS)).isTrue();
      assertThat(session.remoteAddress()).isEmpty();
      assertThat(session.request().path()).isEqualTo("/ws");
      assertThat(session.execution()).isNotNull();

      session.sendText("hello");
      session.sendBinary(new byte[] {0, 1, -1});
      assertThat(serverMessages).hasSize(2);
      assertThat(serverMessages.get(0).text()).isEqualTo("hello");
      assertThat(serverMessages.get(1).bytes()).containsExactly(0, 1, -1);
      assertThat(clientMessages).hasSize(2);
      assertThat(clientMessages.get(0).text()).isEqualTo("hello");
      assertThat(clientMessages.get(1).bytes()).containsExactly(0, 1, -1);

      session.close(1000, "done");
      assertThat(serverClosed.await(5, TimeUnit.SECONDS)).isTrue();
      assertThat(clientClosed.await(5, TimeUnit.SECONDS)).isTrue();
      assertThat(serverCode.get()).isEqualTo(1000);
      assertThat(session.isOpen()).isFalse();
      assertThat(serverSession.get().isOpen()).isFalse();
      assertThatThrownBy(() -> session.sendText("late")).isInstanceOf(IllegalStateException.class);
    }
  }

  @Test
  void unmappedRouteFailsFastAndClosingTheClientEndsOpenSessions() throws Exception {
    var app = Axiom.create();
    app.webSocket("/ws", new WebSocketHandler() {});
    var closed = new CountDownLatch(1);
    var code = new AtomicInteger();
    WebSocketHandler client =
        new WebSocketHandler() {
          @Override
          public void onClose(WebSocketSession session, int status, String reason) {
            code.set(status);
            closed.countDown();
          }
        };
    var test = TestClient.start(app);
    assertThatThrownBy(() -> test.webSocket("/missing", client))
        .isInstanceOf(IllegalArgumentException.class);
    var session = test.webSocket("/ws", client);
    test.close();
    assertThat(closed.await(5, TimeUnit.SECONDS)).isTrue();
    assertThat(code.get()).isEqualTo(1001);
    assertThat(session.isOpen()).isFalse();
  }

  @Test
  void enforcesTheMessageBoundAndImmutablePayloads() throws Exception {
    var app = Axiom.create();
    var messages = new ArrayList<WebSocketMessage>();
    app.webSocket(
        "/ws",
        new WebSocketHandler() {
          @Override
          public void onMessage(WebSocketSession session, WebSocketMessage message) {
            messages.add(message);
          }
        });
    try (var test = TestClient.start(app)) {
      var session = test.webSocket("/ws");
      session.sendBinary(new byte[InMemoryWebSocket.MAX_MESSAGE + 1]);
      assertThat(messages).isEmpty();
      assertThat(session.isOpen()).isFalse();

      var other = test.webSocket("/ws");
      byte[] bytes = {1, 2};
      other.sendBinary(bytes);
      bytes[0] = 9;
      assertThat(messages.get(0).bytes()).containsExactly(1, 2);
      var copied = messages.get(0).bytes();
      copied[0] = 7;
      assertThat(messages.get(0).bytes()).containsExactly(1, 2);
    }
  }
}
