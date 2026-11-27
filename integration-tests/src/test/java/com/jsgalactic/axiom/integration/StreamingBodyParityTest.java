package com.jsgalactic.axiom.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.jsgalactic.axiom.Axiom;
import com.jsgalactic.axiom.application.Application;
import com.jsgalactic.axiom.context.StreamingHandler;
import com.jsgalactic.axiom.http.Body;
import com.jsgalactic.axiom.http.BodyReader;
import com.jsgalactic.axiom.http.MultipartParser;
import com.jsgalactic.axiom.http.Request;
import com.jsgalactic.axiom.http.Response;
import com.jsgalactic.axiom.lifecycle.Server;
import com.jsgalactic.axiom.test.TestClient;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** The same streaming handler sees the same bytes and limits through TestClient and a listener. */
@Tag("integration")
class StreamingBodyParityTest {
  private static final String BOUNDARY = "----parity";
  private static final String MULTIPART =
      "--"
          + BOUNDARY
          + "\r\n"
          + "Content-Disposition: form-data; name=\"a\"\r\n\r\nfirst\r\n"
          + "--"
          + BOUNDARY
          + "\r\n"
          + "Content-Disposition: form-data; name=\"b\"\r\n\r\nsecond\r\n"
          + "--"
          + BOUNDARY
          + "--\r\n";
  private static final String CONTENT_TYPE = "multipart/form-data; boundary=" + BOUNDARY;

  private static Application app() {
    var app = Axiom.create();
    app.post(
        "/echo",
        (StreamingHandler)
            ctx -> {
              var out = new ByteArrayOutputStream();
              var buffer = new byte[16];
              for (int count; (count = ctx.bodyReader().read(buffer)) >= 0; ) {
                out.write(buffer, 0, count);
              }
              return ctx.text(out.toString(StandardCharsets.UTF_8));
            });
    app.post(
        "/parts",
        (StreamingHandler)
            ctx -> {
              var names = new StringBuilder();
              try (var parser =
                  new MultipartParser(
                      ctx.bodyReader(),
                      ctx.header("Content-Type").orElse(null),
                      MultipartParser.Limits.DEFAULT,
                      part ->
                          names.append(part.name()).append('=').append(part.text()).append(';'))) {
                parser.parse();
              }
              return ctx.text(names.toString());
            });
    return app;
  }

  @Test
  void bothClientsDeliverTheWholeBodyAndParseTheSameParts() throws Exception {
    var app = app();
    try (var client = TestClient.start(app)) {
      var embeddedEcho = client.post("/echo", "text/plain", "hello streaming");
      var embeddedParts = client.post("/parts", CONTENT_TYPE, MULTIPART);
      assertThat(embeddedEcho.body()).isNotNull();
      assertThat(text(embeddedEcho)).isEqualTo("hello streaming");
      assertThat(text(embeddedParts)).isEqualTo("a=first;b=second;");

      var server = app.listen(0);
      try {
        assertThat(send(server, "/echo", "text/plain", bytes("hello streaming")))
            .isEqualTo(text(embeddedEcho));
        assertThat(send(server, "/parts", CONTENT_TYPE, bytes(MULTIPART)))
            .isEqualTo(text(embeddedParts));
      } finally {
        server.close();
        server.termination().toCompletableFuture().get(10, TimeUnit.SECONDS);
      }
    }
  }

  @Test
  void aSuppliedReaderIsTheSameHandOffAsABufferedBody() throws Exception {
    var entered = new CountDownLatch(1);
    var app = Axiom.create();
    app.post(
        "/paused",
        (StreamingHandler)
            ctx -> {
              entered.countDown();
              var body = new ByteArrayOutputStream();
              var buffer = new byte[8];
              for (int count; (count = ctx.bodyReader().read(buffer)) >= 0; ) {
                body.write(buffer, 0, count);
              }
              return ctx.text(body.toString(StandardCharsets.UTF_8));
            });
    try (var client = TestClient.start(app)) {
      var reader = BodyReader.of(bytes("held"));
      reader.pause();
      var result = client.submit(Request.fromTarget("POST", "/paused"), reader);
      assertThat(entered.await(30, TimeUnit.SECONDS)).isTrue();
      assertThat(result).isNotDone();
      reader.resume();
      assertThat(text(result.get(30, TimeUnit.SECONDS))).isEqualTo("held");
    }
  }

  @Test
  void theAggregateCapAppliesToAnIncrementalHandOff() throws Exception {
    var app = Axiom.create();
    app.maxRequestBody(4);
    app.post(
        "/echo",
        (StreamingHandler)
            ctx -> {
              var total = 0;
              var buffer = new byte[8];
              for (int count; (count = ctx.bodyReader().read(buffer)) >= 0; ) {
                total += count;
              }
              return ctx.text(Integer.toString(total));
            });
    try (var client = TestClient.start(app)) {
      var request = Request.fromTarget("POST", "/echo").withBody(Body.empty());
      var response = client.execute(request, BodyReader.of(bytes("12345678")));
      assertThat(response.status()).isEqualTo(413);
      assertThat(text(response)).contains("content_too_large");
    }
  }

  @Test
  void malformedMultipartIsAClientErrorOnBothPaths() throws Exception {
    var app = app();
    try (var client = TestClient.start(app)) {
      var bad = "--" + BOUNDARY + "\r\n\r\na\r\n"; // no closing boundary
      var embedded = client.post("/parts", CONTENT_TYPE, bad);
      assertThat(embedded.status()).isEqualTo(400);
      var server = app.listen(0);
      try {
        var reply = sendWithStatus(server, "/parts", CONTENT_TYPE, bytes(bad));
        assertThat(reply.status).isEqualTo(400);
        assertThat(reply.body).contains("malformed_multipart");
      } finally {
        server.close();
        server.termination().toCompletableFuture().get(10, TimeUnit.SECONDS);
      }
    }
  }

  private static String text(Response response) {
    var body = response.body();
    return body instanceof byte[] bytes
        ? new String(bytes, StandardCharsets.UTF_8)
        : String.valueOf(body);
  }

  private static byte[] bytes(String text) {
    return text.getBytes(StandardCharsets.UTF_8);
  }

  private static String send(Server server, String path, String contentType, byte[] body)
      throws IOException {
    return sendWithStatus(server, path, contentType, body).body;
  }

  private static Reply sendWithStatus(Server server, String path, String contentType, byte[] body)
      throws IOException {
    try (var socket = new Socket()) {
      socket.connect(server.localAddress(), 5000);
      socket.setSoTimeout(10_000);
      var head =
          "POST "
              + path
              + " HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n"
              + "Content-Type: "
              + contentType
              + "\r\nContent-Length: "
              + body.length
              + "\r\n\r\n";
      var out = socket.getOutputStream();
      out.write(head.getBytes(StandardCharsets.ISO_8859_1));
      out.write(body);
      out.flush();
      return read(socket.getInputStream());
    }
  }

  private static Reply read(InputStream in) throws IOException {
    var status = line(in);
    var headers = new HashMap<String, String>();
    for (String header; !(header = line(in)).isEmpty(); ) {
      int colon = header.indexOf(':');
      headers.put(header.substring(0, colon).toLowerCase(), header.substring(colon + 1).trim());
    }
    int length = Integer.parseInt(headers.getOrDefault("content-length", "0"));
    return new Reply(
        Integer.parseInt(status.substring(9, 12)),
        new String(in.readNBytes(length), StandardCharsets.UTF_8));
  }

  private static String line(InputStream in) throws IOException {
    var bytes = new ByteArrayOutputStream();
    for (int value; (value = in.read()) != -1; ) {
      if (value == '\n') {
        return bytes.toString(StandardCharsets.ISO_8859_1).replace("\r", "");
      }
      bytes.write(value);
    }
    throw new IOException("Unexpected end of stream");
  }

  private record Reply(int status, String body) {}
}
