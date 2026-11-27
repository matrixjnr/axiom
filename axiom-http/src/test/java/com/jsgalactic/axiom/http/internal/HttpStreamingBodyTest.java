package com.jsgalactic.axiom.http.internal;

import static org.assertj.core.api.Assertions.assertThat;

import com.jsgalactic.axiom.Axiom;
import com.jsgalactic.axiom.application.Application;
import com.jsgalactic.axiom.context.StreamingHandler;
import com.jsgalactic.axiom.http.MultipartParser;
import com.jsgalactic.axiom.http.Response;
import com.jsgalactic.axiom.server.internal.execution.RequestDispatcher;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.http.HttpResponseEncoder;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** Streaming request bodies: bounded hand-off, transport back-pressure, limits and cleanup. */
class HttpStreamingBodyTest {
  private static final String HEAD = "POST /stream HTTP/1.1\r\nHost: a\r\n";
  private final RequestDispatcher executor = new RequestDispatcher(8);
  private final List<EmbeddedChannel> channels = new ArrayList<>();
  private final BodyBudget budget = new BodyBudget(1024 * 1024);
  private final Application app = Axiom.create();

  @AfterEach
  void stop() throws Exception {
    channels.forEach(EmbeddedChannel::finishAndReleaseAll);
    app.close();
    executor.close();
    executor.termination().toCompletableFuture().get(30, TimeUnit.SECONDS);
  }

  private EmbeddedChannel connect() {
    var channel = new EmbeddedChannel();
    channel
        .pipeline()
        .addLast(
            new RequestDecoder(NettyServer.decoderConfig()),
            new HttpResponseEncoder(),
            new HttpConnection(
                app, executor, TransportSettings.DEFAULTS, null, () -> false, budget));
    channels.add(channel);
    return channel;
  }

  private StreamingHandler echo(CountDownLatch entered, CountDownLatch allow, CountDownLatch done) {
    return ctx -> {
      entered.countDown();
      allow.await();
      var out = new ByteArrayOutputStream();
      var buffer = new byte[4096];
      for (int count; (count = ctx.bodyReader().read(buffer)) >= 0; ) {
        out.write(buffer, 0, count);
      }
      done.countDown();
      return Response.of(200, Integer.toString(out.size()));
    };
  }

  @Test
  void pausesTheClientAtTheHighWaterMarkAndResumesWhenRead() throws Exception {
    var entered = new CountDownLatch(1);
    var allow = new CountDownLatch(1);
    var done = new CountDownLatch(1);
    app.maxRequestBody(128 * 1024);
    app.post("/stream", echo(entered, allow, done));
    app.start();
    var channel = connect();

    channel.writeInbound(ascii(HEAD + "Content-Length: 100000\r\n\r\n" + "x".repeat(100_000)));
    assertThat(entered.await(30, TimeUnit.SECONDS)).isTrue();
    channel.runPendingTasks();
    assertThat(channel.config().isAutoRead())
        .as("client paused while the handler is behind")
        .isFalse();
    allow.countDown();
    assertThat(done.await(30, TimeUnit.SECONDS)).isTrue();
    assertThat(awaitOutput(channel, "HTTP/1.1 200")).contains("100000");
    assertThat(await(channel, () -> channel.config().isAutoRead())).isTrue();
    assertThat(budget.used()).isZero();
  }

  @Test
  void rejectsAnOversizedStreamedBodyAsItArrivesWithOneResponse() throws Exception {
    var entered = new CountDownLatch(1);
    app.maxRequestBody(16);
    app.post(
        "/stream",
        (StreamingHandler)
            ctx -> {
              entered.countDown();
              var reader = ctx.bodyReader();
              var buffer = new byte[64];
              var total = 0;
              for (int count; (count = reader.read(buffer)) >= 0; ) {
                total += count;
              }
              return Response.of(200, Integer.toString(total));
            });
    app.start();
    var channel = connect();

    channel.writeInbound(
        ascii(HEAD + "Transfer-Encoding: chunked\r\n\r\n" + "11\r\n" + "x".repeat(17) + "\r\n"));
    var response = awaitOutput(channel, "HTTP/1.1 413 ");
    assertThat(response).contains("content_too_large").doesNotContain("HTTP/1.1 200");
    assertThat(entered.await(30, TimeUnit.SECONDS)).isTrue();
    assertThat(await(channel, () -> !channel.isActive())).isTrue();
    assertThat(budget.used()).isZero();
  }

  @Test
  void usesTheInFlightBodyBudgetForBytesStillQueued() throws Exception {
    var entered = new CountDownLatch(1);
    var allow = new CountDownLatch(1);
    var done = new CountDownLatch(1);
    app.maxRequestBody(1000);
    app.post("/stream", echo(entered, allow, done));
    app.start();
    var channel = connect();

    channel.writeInbound(ascii(HEAD + "Content-Length: 80\r\n\r\n" + "x".repeat(80)));
    assertThat(entered.await(30, TimeUnit.SECONDS)).isTrue();
    assertThat(budget.used())
        .as("a streamed declared body reserves only bytes that arrived")
        .isEqualTo(80);
    allow.countDown();
    assertThat(done.await(30, TimeUnit.SECONDS)).isTrue();
    assertThat(awaitOutput(channel, "HTTP/1.1 200")).contains("80");
    assertThat(await(channel, () -> budget.used() == 0)).isTrue();
  }

  @Test
  void closingTheConnectionCancelsABlockedRead() throws Exception {
    var entered = new CountDownLatch(1);
    var cancelled = new CountDownLatch(1);
    app.post(
        "/stream",
        (StreamingHandler)
            ctx -> {
              entered.countDown();
              try {
                ctx.bodyReader().read(new byte[16]);
              } catch (IOException stopped) {
                cancelled.countDown();
                throw stopped;
              }
              return "unexpected";
            });
    app.start();
    var channel = connect();

    channel.writeInbound(ascii(HEAD + "Content-Length: 20\r\n\r\n"));
    assertThat(entered.await(30, TimeUnit.SECONDS)).isTrue();
    channel.close();
    channel.runPendingTasks();
    assertThat(cancelled.await(30, TimeUnit.SECONDS)).isTrue();
    assertThat(budget.used()).isZero();
  }

  @Test
  void aStreamingRouteWithNoBodyEndsItsReaderAtOnce() throws Exception {
    app.post(
        "/empty", (StreamingHandler) ctx -> ctx.text("read:" + ctx.bodyReader().read(new byte[8])));
    app.start();
    var channel = connect();
    channel.writeInbound(ascii("POST /empty HTTP/1.1\r\nHost: a\r\n\r\n"));
    assertThat(awaitOutput(channel, "HTTP/1.1 200")).contains("read:-1");
  }

  @Test
  void bufferedBodyAccessStillWorksForOrdinaryRoutes() throws Exception {
    app.post("/buffered", ctx -> Response.of(200, Integer.toString(ctx.request().body().length())));
    app.start();
    var channel = connect();
    channel.writeInbound(
        ascii("POST /buffered HTTP/1.1\r\nHost: a\r\nContent-Length: 5\r\n\r\nhello"));
    assertThat(awaitOutput(channel, "HTTP/1.1 200")).contains("5");
  }

  @Test
  @Tag("integration")
  void parsesMultipartEventsOverTheListener() throws Exception {
    var parts = new ArrayList<String>();
    try (var fixture = new Fixture()) {
      fixture.app.post(
          "/upload",
          (StreamingHandler)
              ctx -> {
                try (var parser =
                    new MultipartParser(
                        ctx.bodyReader(),
                        ctx.header("Content-Type").orElse(null),
                        new MultipartParser.Limits(4, 1024, 1024, 8192),
                        new MultipartParser.Listener() {
                          @Override
                          public void part(MultipartParser.Part part) {
                            parts.add(part.name() + "=" + part.text());
                          }
                        })) {
                  parser.parse();
                }
                return ctx.text(String.join(",", parts));
              });
      try (var wire = new Wire(fixture.listen())) {
        String body =
            "------b\r\nContent-Disposition: form-data; name=\"a\"\r\n\r\nfirst\r\n"
                + "------b\r\nContent-Disposition: form-data; name=\"b\"\r\n\r\nsecond\r\n------b--\r\n";
        wire.write(
            "POST /upload HTTP/1.1\r\nHost: a\r\nContent-Type: multipart/form-data; boundary=----b\r\n"
                + "Content-Length: "
                + body.getBytes(StandardCharsets.US_ASCII).length
                + "\r\n\r\n"
                + body);
        assertThat(wire.read(false).text()).isEqualTo("a=first,b=second");
      }
    }
  }

  /** Runs the channel's tasks until the condition holds, without depending on elapsed time. */
  private static boolean await(EmbeddedChannel channel, BooleanSupplier condition) {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
    while (!condition.getAsBoolean()) {
      if (System.nanoTime() > deadline) {
        return false;
      }
      channel.runPendingTasks();
      Thread.onSpinWait();
    }
    return true;
  }

  private static String awaitOutput(EmbeddedChannel channel, String expected) {
    var text = new StringBuilder();
    assertThat(
            await(
                channel,
                () -> {
                  text.append(outbound(channel));
                  return text.toString().contains(expected);
                }))
        .as("output contains " + expected)
        .isTrue();
    return text.toString();
  }

  private static ByteBuf ascii(String text) {
    return Unpooled.copiedBuffer(text, StandardCharsets.US_ASCII);
  }

  private static String outbound(EmbeddedChannel channel) {
    var text = new StringBuilder();
    for (ByteBuf buffer; (buffer = channel.readOutbound()) != null; ) {
      text.append(buffer.toString(StandardCharsets.ISO_8859_1));
      buffer.release();
    }
    return text.toString();
  }
}
