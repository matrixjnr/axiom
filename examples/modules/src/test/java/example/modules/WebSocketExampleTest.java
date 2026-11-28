package example.modules;

import static org.assertj.core.api.Assertions.assertThat;

import com.jsgalactic.axiom.test.TestClient;
import com.jsgalactic.axiom.websocket.WebSocketHandler;
import com.jsgalactic.axiom.websocket.WebSocketMessage;
import com.jsgalactic.axiom.websocket.WebSocketSession;
import java.util.ArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class WebSocketExampleTest {
  @Test
  void echoesTextAndBinaryAndClosesThroughTheTestClient() throws Exception {
    var serverMessages = new ArrayList<WebSocketMessage>();
    WebSocketHandler echo =
        new WebSocketHandler() {
          @Override
          public void onMessage(WebSocketSession session, WebSocketMessage message) {
            serverMessages.add(message);
            session.send(message);
          }
        };
    var clientMessages = new ArrayList<WebSocketMessage>();
    var closed = new CountDownLatch(1);
    WebSocketHandler client =
        new WebSocketHandler() {
          @Override
          public void onMessage(WebSocketSession session, WebSocketMessage message) {
            clientMessages.add(message);
          }

          @Override
          public void onClose(WebSocketSession session, int code, String reason) {
            closed.countDown();
          }
        };
    try (var test = TestClient.start(WebSocketExample.create(echo))) {
      var session = test.webSocket("/echo", client);
      session.sendText("hello");
      session.sendBinary(new byte[] {0, 1, -1});
      assertThat(serverMessages).hasSize(2);
      assertThat(serverMessages.get(0).text()).isEqualTo("hello");
      assertThat(serverMessages.get(1).bytes()).containsExactly(0, 1, -1);
      assertThat(clientMessages).hasSize(2);
      assertThat(clientMessages.get(0).text()).isEqualTo("hello");
      assertThat(clientMessages.get(1).bytes()).containsExactly(0, 1, -1);
      session.close();
      assertThat(closed.await(5, TimeUnit.SECONDS)).isTrue();
      assertThat(session.isOpen()).isFalse();
    }
  }
}
