package com.jsgalactic.axiom.integration;

import com.jsgalactic.axiom.http.Body;
import com.jsgalactic.axiom.http.Request;
import com.jsgalactic.axiom.integration.Exchange.Client;
import com.jsgalactic.axiom.integration.Exchange.Reply;
import com.jsgalactic.axiom.test.TestClient;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/** The JSON contract through the in-memory {@link TestClient}. */
class TestClientJsonTest extends JsonContractTest {
    @Override Client open() {
        var testClient = TestClient.start(OrdersApi.create());
        return new Client() {
            @Override public Reply send(String method, String path, Map<String, String> headers, byte[] body)
                    throws Exception {
                var contentType = headers.entrySet().stream().filter(e -> e.getKey().equalsIgnoreCase("Content-Type"))
                        .map(Map.Entry::getValue).findFirst().orElse(null);
                var response = testClient.execute(new Request(method, path, headers, Body.of(contentType, body)));
                byte[] content = switch (response.body()) {
                    case null -> new byte[0];
                    case byte[] bytes -> bytes;
                    case String text -> text.getBytes(StandardCharsets.UTF_8);
                    default -> throw new AssertionError("Unprepared response body");
                };
                return Reply.of(response.status(), response.headers(), content);
            }

            @Override public Reply sendOversized(String path, String contentType, int length) throws Exception {
                return send("POST", path, Map.of("Content-Type", contentType), new byte[length]);
            }

            @Override public void close() { testClient.close(); }
        };
    }
}
