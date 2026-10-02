package io.axiom.integration;

import io.axiom.application.Application;
import io.axiom.integration.Exchange.Client;
import io.axiom.integration.Exchange.Reply;
import io.axiom.lifecycle.Server;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * The JSON contract over a live HTTP/1.1 listener, using a raw socket so that header values and
 * body bytes are sent exactly as written. Each request uses its own connection.
 */
class ListenerJsonTest extends JsonContractTest {
    @Override Client open() throws IOException {
        Application app = OrdersApi.create();
        Server server = app.listen(0);
        return new Client() {
            @Override public Reply send(String method, String path, Map<String, String> headers, byte[] body)
                    throws IOException {
                return exchange(head(method, path, headers, body.length), body);
            }

            @Override public Reply sendOversized(String path, String contentType, int length) throws IOException {
                // Only the head: the listener must answer from the declared length alone.
                return exchange(head("POST", path, Map.of("Content-Type", contentType), length), new byte[0]);
            }

            private Reply exchange(String head, byte[] body) throws IOException {
                try (var socket = new Socket()) {
                    socket.connect(server.localAddress(), 5000);
                    socket.setSoTimeout(10_000);
                    var out = socket.getOutputStream();
                    out.write(head.getBytes(StandardCharsets.ISO_8859_1));
                    out.write(body);
                    out.flush();
                    return read(socket.getInputStream());
                }
            }

            @Override public void close() throws Exception {
                app.close();
                server.termination().toCompletableFuture().get(10, TimeUnit.SECONDS);
            }
        };
    }

    private static String head(String method, String path, Map<String, String> headers, int length) {
        var head = new StringBuilder(method).append(' ').append(path).append(" HTTP/1.1\r\n")
                .append("Host: localhost\r\nConnection: close\r\n");
        headers.forEach((name, value) -> head.append(name).append(": ").append(value).append("\r\n"));
        if (length > 0 || !method.equals("GET")) { head.append("Content-Length: ").append(length).append("\r\n"); }
        return head.append("\r\n").toString();
    }

    private static Reply read(InputStream in) throws IOException {
        var status = line(in);
        if (!status.startsWith("HTTP/1.1 ")) { throw new IOException("Unexpected status line"); }
        var headers = new HashMap<String, String>();
        for (String line; !(line = line(in)).isEmpty();) {
            int colon = line.indexOf(':');
            headers.put(line.substring(0, colon), line.substring(colon + 1).trim());
        }
        var lengthHeader = headers.entrySet().stream().filter(e -> e.getKey().equalsIgnoreCase("Content-Length"))
                .map(Map.Entry::getValue).findFirst().orElse("0");
        int length = Integer.parseInt(lengthHeader);
        var body = in.readNBytes(length);
        if (body.length != length) { throw new IOException("Truncated body"); }
        return Reply.of(Integer.parseInt(status.substring(9, 12)), headers, body);
    }

    private static String line(InputStream in) throws IOException {
        var bytes = new ByteArrayOutputStream();
        for (int value; (value = in.read()) != -1;) {
            if (value == '\n') { return bytes.toString(StandardCharsets.ISO_8859_1).replace("\r", ""); }
            bytes.write(value);
            if (bytes.size() > 16_384) { throw new IOException("Unbounded line"); }
        }
        throw new IOException("Unexpected end of stream");
    }
}
