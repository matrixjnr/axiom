package com.jsgalactic.axiom.http.internal;

import static org.assertj.core.api.Assertions.assertThat;

import com.jsgalactic.axiom.websocket.WebSocketHandler;
import com.jsgalactic.axiom.websocket.WebSocketMessage;
import com.jsgalactic.axiom.websocket.WebSocketSession;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("integration")
class WebSocketTest {
  private static final byte[] SMALL = {0, 1, -1, 42};

  @Test
  void upgradesOnlyARegisteredGetAndLeavesHttpRoutesAlone() throws Exception {
    try (var fixture = new Fixture()) {
      var connected = new CountDownLatch(1);
      fixture.app.webSocket(
          "/ws",
          new WebSocketHandler() {
            @Override
            public void onConnect(WebSocketSession session) {
              connected.countDown();
            }

            @Override
            public void onMessage(WebSocketSession session, WebSocketMessage message) {
              session.send(message);
            }
          });
      fixture.app.get("/", ctx -> "plain");
      fixture.app.head("/", ctx -> "head");
      fixture.app.options("/", ctx -> "options");
      var server = fixture.listen();
      try (var wire = new WebSocketWire(server)) {
        var handshake = wire.handshake("/ws?ignored=yes");
        assertThat(handshake.get(":status")).isEqualTo("101");
        assertThat(handshake.get("Upgrade")).isEqualTo("websocket");
        assertThat(handshake.get("Sec-WebSocket-Accept")).isEqualTo(WebSocketWire.accept());
        assertThat(connected.await(5, TimeUnit.SECONDS)).isTrue();
        wire.frame(true, 1, "hello".getBytes(StandardCharsets.UTF_8));
        var echo = wire.read();
        assertThat(echo.opcode()).isEqualTo(1);
        assertThat(echo.fin()).isTrue();
        assertThat(echo.text()).isEqualTo("hello");
      }
      try (var wire = new Wire(server)) {
        assertThat(wire.get("/").text()).isEqualTo("plain");
        wire.write("HEAD / HTTP/1.1\r\nHost: localhost\r\n\r\n");
        var head = wire.read(true);
        assertThat(head.status()).isEqualTo(200);
        assertThat(head.headers()).containsEntry("content-length", "4");
        wire.write("OPTIONS / HTTP/1.1\r\nHost: localhost\r\n\r\n");
        assertThat(wire.read(false).text()).isEqualTo("options");
      }
    }
  }

  @Test
  void rejectsInvalidAndUnregisteredHandshakes() throws Exception {
    try (var fixture = new Fixture();
        var wire = new WebSocketWire(fixture.listen())) {
      assertThat(wire.handshake("/missing").get(":status")).isEqualTo("501");
    }
    try (var fixture = new Fixture()) {
      fixture.app.webSocket("/ws", new WebSocketHandler() {});
      try (var wire = new WebSocketWire(fixture.listen())) {
        assertThat(wire.handshake("/ws", "short", "13", true).get(":status")).isEqualTo("400");
      }
      try (var wire = new WebSocketWire(fixture.listen())) {
        assertThat(wire.handshake("/ws", WebSocketWire.key(), "12", true).get(":status"))
            .isEqualTo("400");
      }
      try (var wire = new Wire(fixture.listen())) {
        wire.write("POST /ws HTTP/1.1\r\nHost: localhost\r\nUpgrade: websocket\r\n\r\n");
        assertThat(wire.read(false).status()).isEqualTo(501);
      }
    }
  }

  @Test
  void aggregatesFragmentsAndEchoesBinaryFrames() throws Exception {
    try (var fixture = new Fixture()) {
      var received = new CountDownLatch(2);
      fixture.app.webSocket(
          "/ws",
          new WebSocketHandler() {
            @Override
            public void onMessage(WebSocketSession session, WebSocketMessage message) {
              session.send(message);
              received.countDown();
            }
          });
      try (var wire = new WebSocketWire(fixture.listen())) {
        wire.handshake("/ws");
        wire.frame(false, 1, "hel".getBytes(StandardCharsets.UTF_8));
        wire.frame(true, 0, "lo".getBytes(StandardCharsets.UTF_8));
        var text = wire.read();
        assertThat(text.opcode()).isEqualTo(1);
        assertThat(text.text()).isEqualTo("hello");
        wire.frame(true, 2, SMALL);
        var binary = wire.read();
        assertThat(binary.opcode()).isEqualTo(2);
        assertThat(binary.payload()).containsExactly(SMALL);
        assertThat(received.await(5, TimeUnit.SECONDS)).isTrue();
      }
    }
  }

  @Test
  void closesOversizedFramesWithMessageTooBig() throws Exception {
    try (var fixture = new Fixture()) {
      var error = new CountDownLatch(1);
      var closed = new CountDownLatch(1);
      var code = new AtomicInteger();
      fixture.app.webSocket(
          "/ws",
          new WebSocketHandler() {
            @Override
            public void onError(WebSocketSession session, Throwable failure) {
              error.countDown();
            }

            @Override
            public void onClose(WebSocketSession session, int status, String reason) {
              code.set(status);
              closed.countDown();
            }
          });
      try (var wire = new WebSocketWire(fixture.listen())) {
        wire.handshake("/ws");
        wire.frame(true, 2, new byte[WebSocketSupport.MAX_FRAME + 1]);
        var frame = wire.read();
        assertThat(frame.opcode()).isEqualTo(8);
        assertThat(frame.code()).isEqualTo(1009);
        assertThat(error.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(closed.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(code.get()).isEqualTo(1009);
      }
    }
  }

  @Test
  void answersPingAndSendsApplicationPing() throws Exception {
    try (var fixture = new Fixture()) {
      var connected = new CountDownLatch(1);
      var session = new AtomicReference<NettyWebSocketSession>();
      fixture.app.webSocket(
          "/ws",
          new WebSocketHandler() {
            @Override
            public void onConnect(WebSocketSession established) {
              session.set((NettyWebSocketSession) established);
              connected.countDown();
            }
          });
      try (var wire = new WebSocketWire(fixture.listen())) {
        wire.handshake("/ws");
        assertThat(connected.await(5, TimeUnit.SECONDS)).isTrue();
        wire.frame(true, 9, SMALL);
        var pong = wire.read();
        assertThat(pong.opcode()).isEqualTo(10);
        assertThat(pong.payload()).containsExactly(SMALL);
        session.get().ping(new byte[] {7, 8});
        var ping = wire.read();
        assertThat(ping.opcode()).isEqualTo(9);
        assertThat(ping.payload()).containsExactly(7, 8);
      }
    }
  }

  @Test
  void pausesReadsUntilThePeerConsumesWrites() throws Exception {
    try (var fixture = new Fixture()) {
      var connected = new CountDownLatch(1);
      var paused = new CountDownLatch(1);
      var resumed = new CountDownLatch(1);
      var session = new AtomicReference<NettyWebSocketSession>();
      fixture.app.webSocket(
          "/ws",
          new WebSocketHandler() {
            @Override
            public void onConnect(WebSocketSession established) {
              session.set((NettyWebSocketSession) established);
              connected.countDown();
            }

            @Override
            public void onMessage(WebSocketSession established, WebSocketMessage message) {
              established.sendBinary(new byte[60 * 1024]);
            }
          });
      var server = fixture.listen();
      try (var wire = new WebSocketWire(server, 4096)) {
        wire.handshake("/ws");
        assertThat(connected.await(5, TimeUnit.SECONDS)).isTrue();
        session.get().observeReads(paused::countDown, resumed::countDown);
        for (int i = 0; i < 40; i++) {
          wire.frame(true, 2, SMALL);
        }
        assertThat(paused.await(10, TimeUnit.SECONDS)).isTrue();
        assertThat(session.get().reading()).isFalse();
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
        while (!session.get().reading() && System.nanoTime() < deadline) {
          wire.read();
        }
        assertThat(session.get().reading()).isTrue();
        assertThat(resumed.await(5, TimeUnit.SECONDS)).isTrue();
      }
    }
  }

  @Test
  void endsSessionsWithinTheListenerShutdownGrace() throws Exception {
    try (var fixture = new Fixture()) {
      var connected = new CountDownLatch(1);
      var closed = new CountDownLatch(1);
      var code = new AtomicInteger();
      var session = new AtomicReference<WebSocketSession>();
      fixture.app.webSocket(
          "/ws",
          new WebSocketHandler() {
            @Override
            public void onConnect(WebSocketSession established) {
              session.set(established);
              connected.countDown();
            }

            @Override
            public void onClose(WebSocketSession established, int status, String reason) {
              code.set(status);
              closed.countDown();
            }
          });
      var server = fixture.listen();
      try (var wire = new WebSocketWire(server)) {
        wire.handshake("/ws");
        assertThat(connected.await(5, TimeUnit.SECONDS)).isTrue();
        server.close();
        assertThat(closed.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(code.get()).isEqualTo(1001);
        assertThat(session.get().isOpen()).isFalse();
        assertThat(server.termination().toCompletableFuture().get(10, TimeUnit.SECONDS)).isNull();
      }
    }
  }
}
