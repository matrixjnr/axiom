package com.jsgalactic.axiom.http.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.jsgalactic.axiom.http.Response;
import com.jsgalactic.axiom.lifecycle.ListenerOptions;
import com.jsgalactic.axiom.lifecycle.Server;
import com.jsgalactic.axiom.lifecycle.TlsOptions;
import io.netty.handler.codec.http2.DefaultHttp2Headers;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import javax.net.ssl.SSLException;
import javax.net.ssl.SSLSocket;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * HTTP/2 over real sockets: h2c prior knowledge, ALPN, the shared request path, flow control,
 * trailers, limits and shutdown. Every wait ends on an event (a future or a latch), never on a
 * fixed delay.
 */
@Tag("integration")
class Http2ListenerTest {
  private static final InetSocketAddress LOOPBACK = new InetSocketAddress("127.0.0.1", 0);
  private static final ListenerOptions H2 = ListenerOptions.builder().http2(true).build();

  private static Server listen(Fixture fixture, ListenerOptions options) throws IOException {
    var server = fixture.app.listen(LOOPBACK, options);
    fixture.servers.add(server);
    return server;
  }

  private static byte[] bytes(String text) {
    return text.getBytes(StandardCharsets.UTF_8);
  }

  @Test
  void h2cPriorKnowledgeServesGetAndPostWithHeadersAndTrailers() throws Exception {
    try (var fixture = new Fixture()) {
      fixture.app.get(
          "/hello",
          ctx ->
              Response.of(200, "hello").withHeader("x-seen", ctx.header("x-test").orElse("none")));
      fixture.app.post(
          "/echo",
          ctx ->
              ctx.header("x-trailer").orElse("none")
                  + ":"
                  + new String(ctx.request().body().bytes(), StandardCharsets.UTF_8));
      var server = listen(fixture, H2);
      try (var client = new H2Client(server.localAddress(), null)) {
        var get = H2Client.headers("GET", "/hello");
        get.set("x-test", "value");
        var reply = client.stream().send(get, new byte[0]).await();
        assertThat(reply.status()).isEqualTo(200);
        assertThat(reply.text()).isEqualTo("hello");
        assertThat(reply.header("x-seen")).isEqualTo("value");
        assertThat(reply.header("x-request-id")).isNotNull();
        assertThat(reply.header("date")).isNotNull();

        var post = H2Client.headers("POST", "/echo");
        var stream = client.stream();
        stream.head(post);
        stream.data(bytes("body"));
        var trailers = new DefaultHttp2Headers();
        trailers.set("x-trailer", "tail");
        stream.trailers(trailers);
        assertThat(stream.await().text()).isEqualTo("tail:body");
      }
    }
  }

  @Test
  void tlsNegotiatesH2WithHttp11FallbackAndKeepsTheTlsRules() throws Exception {
    try (var tls = new TlsFixture();
        var fixture = new Fixture()) {
      var identity = tls.identity("localhost");
      var material = TlsOptions.pem(identity.chain(), identity.key());
      fixture.app.get("/scheme", ctx -> ctx.request().scheme() + " " + ctx.request().isSecure());
      var server = listen(fixture, ListenerOptions.builder().tls(material).http2(true).build());
      var context = TlsFixture.client(identity.certificate(), null);
      try (var client = new H2Client(server.localAddress(), context)) {
        assertThat(client.protocol()).isEqualTo("h2");
        var reply = client.stream().send(H2Client.headers("GET", "/scheme"), new byte[0]).await();
        assertThat(reply.status()).isEqualTo(200);
        assertThat(reply.text()).isEqualTo("https true");
      }
      try (var socket =
          (SSLSocket)
              context
                  .getSocketFactory()
                  .createSocket(
                      server.localAddress().getAddress(), server.localAddress().getPort())) {
        socket.setSoTimeout(30_000);
        var parameters = socket.getSSLParameters();
        parameters.setApplicationProtocols(new String[] {"http/1.1"});
        socket.setSSLParameters(parameters);
        socket.startHandshake();
        assertThat(socket.getApplicationProtocol()).isEqualTo("http/1.1");
        socket
            .getOutputStream()
            .write(bytes("GET /scheme HTTP/1.1\r\nHost: a\r\nConnection: close\r\n\r\n"));
        socket.getOutputStream().flush();
        var response = new String(socket.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertThat(response).startsWith("HTTP/1.1 200").endsWith("https true");
      }
      // A listener that did not opt in still offers http/1.1 only: TLS behavior is unchanged.
      var unchanged = listen(fixture, ListenerOptions.builder().tls(material).build());
      try (var socket =
          (SSLSocket)
              context
                  .getSocketFactory()
                  .createSocket(
                      unchanged.localAddress().getAddress(), unchanged.localAddress().getPort())) {
        socket.setSoTimeout(30_000);
        var parameters = socket.getSSLParameters();
        parameters.setApplicationProtocols(new String[] {"h2"});
        socket.setSSLParameters(parameters);
        assertThatThrownBy(socket::startHandshake).isInstanceOf(SSLException.class);
      }
    }
  }

  @Test
  void concurrentStreamsRunOnOneConnectionAndShareTheDispatcher() throws Exception {
    try (var fixture = new Fixture()) {
      var started = new CountDownLatch(8);
      var release = new CountDownLatch(1);
      fixture.app.get(
          "/slow",
          ctx -> {
            started.countDown();
            release.await();
            return "ok";
          });
      var server = listen(fixture, H2);
      try (var client = new H2Client(server.localAddress(), null)) {
        var streams = new ArrayList<H2Client.Stream>();
        for (int i = 0; i < 8; i++) {
          streams.add(client.stream().send(H2Client.headers("GET", "/slow"), new byte[0]));
        }
        assertThat(started.await(10, TimeUnit.SECONDS)).isTrue();
        release.countDown();
        for (var stream : streams) {
          assertThat(stream.await().status()).isEqualTo(200);
        }
      }
    }
  }

  @Test
  void aPausedReaderStopsAStreamedResponseAtTheFlowControlWindow() throws Exception {
    try (var fixture = new Fixture()) {
      var firstWrite = new CountDownLatch(1);
      var finished = new CountDownLatch(1);
      var data = new byte[64 * 1024];
      fixture.app.get(
          "/blob",
          ctx ->
              Response.stream(
                  200,
                  "application/octet-stream",
                  out -> {
                    for (int i = 0; i < 32; i++) {
                      out.write(data);
                      firstWrite.countDown();
                    }
                    finished.countDown();
                  }));
      var server = listen(fixture, H2);
      try (var client = new H2Client(server.localAddress(), null)) {
        var stream = client.stream();
        stream.channel().config().setAutoRead(false);
        stream.send(H2Client.headers("GET", "/blob"), new byte[0]);
        assertThat(firstWrite.await(10, TimeUnit.SECONDS)).isTrue();
        assertThat(finished.getCount()).isEqualTo(1);
        stream.channel().config().setAutoRead(true);
        var reply = stream.await();
        assertThat(reply.status()).isEqualTo(200);
        assertThat(reply.body).hasSize(32 * data.length);
        assertThat(finished.await(10, TimeUnit.SECONDS)).isTrue();
      }
    }
  }

  @Test
  void streamedResponsesSendTrailersNamedByTheTrailerHeader() throws Exception {
    try (var fixture = new Fixture()) {
      fixture.app.get(
          "/feed",
          ctx ->
              Response.stream(200, "text/plain", out -> out.write("hi"))
                  .withHeader("Trailer", "x-checksum")
                  .withHeader("x-checksum", "abc"));
      var server = listen(fixture, H2);
      try (var client = new H2Client(server.localAddress(), null)) {
        var reply = client.stream().send(H2Client.headers("GET", "/feed"), new byte[0]).await();
        assertThat(reply.text()).isEqualTo("hi");
        assertThat(reply.trailer("x-checksum")).isEqualTo("abc");
        assertThat(reply.header("x-checksum")).isNull();
        assertThat(reply.header("trailer")).isNull();
      }
    }
  }

  @Test
  void problemResponsesAndLimitsUseTheSameMappingAsHttp1() throws Exception {
    try (var fixture = new Fixture()) {
      fixture.app.maxRequestBody(16);
      fixture.app.get(
          "/boom",
          ctx -> {
            throw new IllegalStateException("secret");
          });
      fixture.app.post("/small", ctx -> "ok");
      var options =
          ListenerOptions.builder().http2(true).maxPipelinedRequests(3).maxHeaderBytes(512).build();
      var server = listen(fixture, options);
      try (var client = new H2Client(server.localAddress(), null)) {
        var settings = client.settings().get(10, TimeUnit.SECONDS);
        assertThat(settings.maxConcurrentStreams()).isEqualTo(3);
        assertThat(settings.maxHeaderListSize()).isEqualTo(512L);

        var failure = client.stream().send(H2Client.headers("GET", "/boom"), new byte[0]).await();
        assertThat(failure.status()).isEqualTo(500);
        assertThat(failure.header("content-type")).isEqualTo("application/problem+json");
        assertThat(failure.header("x-request-id")).isNotNull();
        assertThat(failure.text()).doesNotContain("secret");

        var huge = H2Client.headers("POST", "/small");
        huge.set("content-type", "application/octet-stream");
        var stream = client.stream().send(huge, new byte[64]);
        assertThat(stream.await().status()).isEqualTo(413);
      }
    }
  }

  @Test
  void shutdownSendsGoawayAndWaitsForStreamsAlreadyRunning() throws Exception {
    try (var fixture = new Fixture()) {
      var entered = new CountDownLatch(1);
      var release = new CountDownLatch(1);
      fixture.app.get(
          "/slow",
          ctx -> {
            entered.countDown();
            release.await();
            return "done";
          });
      var server = listen(fixture, H2);
      var client = new H2Client(server.localAddress(), null);
      var stream = client.stream().send(H2Client.headers("GET", "/slow"), new byte[0]);
      assertThat(entered.await(10, TimeUnit.SECONDS)).isTrue();
      server.close();
      assertThat(client.goAway().get(10, TimeUnit.SECONDS)).isZero();
      assertThat(server.termination().toCompletableFuture().isDone()).isFalse();
      release.countDown();
      assertThat(stream.await().text()).isEqualTo("done");
      server.termination().toCompletableFuture().get(10, TimeUnit.SECONDS);
      client.closed().get(10, TimeUnit.SECONDS);
      client.close();
    }
  }

  @Test
  void anH2EnabledListenerStillServesHttp11AndRefusesH2cUpgrade() throws Exception {
    try (var fixture = new Fixture()) {
      fixture.app.get("/", ctx -> "ok");
      var server = listen(fixture, H2);
      try (var wire = new Wire(server)) {
        assertThat(wire.get("/").text()).isEqualTo("ok");
        wire.write("GET / HTTP/1.1\r\nHost: a\r\nUpgrade: h2c\r\nConnection: Upgrade\r\n\r\n");
        assertThat(wire.read(false).status()).isEqualTo(501);
      }
    }
  }

  @Test
  void aListenerWithoutHttp2RefusesTheH2cPreface() throws Exception {
    try (var fixture = new Fixture()) {
      fixture.app.get("/", ctx -> "ok");
      var server = listen(fixture, ListenerOptions.defaults());
      try (var socket =
          new Socket(server.localAddress().getAddress(), server.localAddress().getPort())) {
        socket.setSoTimeout(30_000);
        socket
            .getOutputStream()
            .write("PRI * HTTP/2.0\r\n\r\nSM\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
        socket.getOutputStream().flush();
        var text = new String(socket.getInputStream().readAllBytes(), StandardCharsets.ISO_8859_1);
        assertThat(text).startsWith("HTTP/1.1 ").doesNotContain("HTTP/1.1 200");
      }
    }
  }

  @Test
  void tlsPollingStillWorksWithHttp2Enabled() throws Exception {
    try (var tls = new TlsFixture();
        var fixture = new Fixture()) {
      var first = tls.identity("first");
      var renewed = tls.identity("second");
      var chain = tls.directory().resolve("chain.pem");
      var key = tls.directory().resolve("key.pem");
      TlsFixture.install(first, chain, key);
      var options =
          ListenerOptions.builder()
              .tls(
                  TlsOptions.builder()
                      .certificateChain(chain)
                      .privateKey(key)
                      .reloadInterval(Duration.ofSeconds(1))
                      .build())
              .http2(true)
              .build();
      var server = listen(fixture, options);
      try (var client =
          new H2Client(server.localAddress(), TlsFixture.client(first.certificate(), null))) {
        assertThat(client.protocol()).isEqualTo("h2");
        TlsFixture.install(renewed, chain, key);
        ((NettyServer) server).pollTls();
        var reply = client.stream().send(H2Client.headers("GET", "/"), new byte[0]).await();
        assertThat(reply.status()).isEqualTo(404);
      }
    }
  }

  @Test
  void rejectedResponseBodyStaysBoundedAcrossStreams() throws Exception {
    try (var fixture = new Fixture()) {
      var waiting = new CountDownLatch(1);
      var release = new CountDownLatch(1);
      var seen = new AtomicInteger();
      fixture.app.post(
          "/slow",
          ctx -> {
            seen.incrementAndGet();
            waiting.countDown();
            release.await();
            return "ok";
          });
      fixture.app.maxRequestBody(64 * 1024);
      var server =
          listen(
              fixture,
              ListenerOptions.builder().http2(true).maxInFlightBodyBytes(64 * 1024).build());
      try (var client = new H2Client(server.localAddress(), null)) {
        var streams = new ArrayList<H2Client.Stream>();
        for (int i = 0; i < 2; i++) {
          var headers = H2Client.headers("POST", "/slow");
          headers.set("content-length", "2048");
          streams.add(client.stream().send(headers, new byte[2048]));
        }
        assertThat(waiting.await(10, TimeUnit.SECONDS)).isTrue();
        assertThat(((NettyServer) server).bodyBytesInFlight()).isEqualTo(4096);
        release.countDown();
        for (var stream : streams) {
          assertThat(stream.await().status()).isEqualTo(200);
        }
        assertThat(seen.get()).isEqualTo(2);
      }
    }
  }
}
