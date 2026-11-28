package com.jsgalactic.axiom.grpc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.jsgalactic.axiom.Axiom;
import com.jsgalactic.axiom.application.Application;
import com.jsgalactic.axiom.error.BadRequestException;
import com.jsgalactic.axiom.lifecycle.TlsOptions;
import com.jsgalactic.axiom.observability.Health;
import io.grpc.BindableService;
import io.grpc.CallOptions;
import io.grpc.Context;
import io.grpc.ManagedChannel;
import io.grpc.MethodDescriptor;
import io.grpc.ServerServiceDefinition;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.inprocess.InProcessChannelBuilder;
import io.grpc.inprocess.InProcessServerBuilder;
import io.grpc.stub.ClientCalls;
import io.grpc.stub.ServerCalls;
import io.grpc.stub.StreamObserver;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/** In-process tests: no sockets, no sleeps, bounded waits on latches. */
@Timeout(30)
class GrpcTest {
  private static final MethodDescriptor.Marshaller<String> TEXT =
      new MethodDescriptor.Marshaller<>() {
        @Override
        public InputStream stream(String value) {
          return new ByteArrayInputStream(value.getBytes(StandardCharsets.UTF_8));
        }

        @Override
        public String parse(InputStream stream) {
          try (stream) {
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
          } catch (IOException failure) {
            throw new UncheckedIOException(failure);
          }
        }
      };
  private static final MethodDescriptor<String, String> UNARY =
      method("Unary", MethodDescriptor.MethodType.UNARY);
  private static final MethodDescriptor<String, String> STREAM =
      method("Stream", MethodDescriptor.MethodType.SERVER_STREAMING);

  private record Service(
      BiConsumer<String, StreamObserver<String>> unary,
      BiConsumer<String, StreamObserver<String>> stream)
      implements BindableService {
    @Override
    public ServerServiceDefinition bindService() {
      return ServerServiceDefinition.builder("test.Service")
          .addMethod(UNARY, ServerCalls.asyncUnaryCall(unary::accept))
          .addMethod(STREAM, ServerCalls.asyncServerStreamingCall(stream::accept))
          .build();
    }
  }

  private static final class RecordingObserver implements StreamObserver<String> {
    final CountDownLatch done = new CountDownLatch(1);
    final List<String> values = new CopyOnWriteArrayList<>();
    final AtomicReference<Throwable> error = new AtomicReference<>();

    @Override
    public void onNext(String value) {
      values.add(value);
    }

    @Override
    public void onError(Throwable failure) {
      error.set(failure);
      done.countDown();
    }

    @Override
    public void onCompleted() {
      done.countDown();
    }
  }

  @FunctionalInterface
  private interface ChannelTest {
    void run(ManagedChannel channel) throws Exception;
  }

  private static MethodDescriptor<String, String> method(
      String name, MethodDescriptor.MethodType type) {
    return MethodDescriptor.<String, String>newBuilder()
        .setType(type)
        .setFullMethodName("test.Service/" + name)
        .setRequestMarshaller(TEXT)
        .setResponseMarshaller(TEXT)
        .build();
  }

  private static Service quiet() {
    return new Service(
        (request, observer) -> observer.onCompleted(),
        (request, observer) -> observer.onCompleted());
  }

  private static Grpc start(
      Application app, String name, Service service, Consumer<Grpc.Builder> configure)
      throws Exception {
    var builder =
        Grpc.builder(app)
            .service(service)
            .transport(InProcessServerBuilder.forName(name))
            .drainGrace(Duration.ofSeconds(2));
    configure.accept(builder);
    var grpc = builder.build();
    grpc.start();
    return grpc;
  }

  private static void withChannel(String name, ChannelTest test) throws Exception {
    var channel = InProcessChannelBuilder.forName(name).directExecutor().usePlaintext().build();
    try {
      test.run(channel);
    } finally {
      channel.shutdownNow();
    }
  }

  private static Status.Code code(Throwable failure) {
    return ((StatusRuntimeException) failure).getStatus().getCode();
  }

  private static boolean await(CountDownLatch latch) {
    try {
      return latch.await(2, TimeUnit.SECONDS);
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      throw new AssertionError(interrupted);
    }
  }

  @Test
  void unaryRpcSeesTheCallContextAndReturnsTheResponse() throws Exception {
    var name = InProcessServerBuilder.generateName();
    var app = Axiom.create();
    var service =
        new Service(
            (request, observer) -> {
              var call = GrpcCall.current();
              assertThat(call.execution().requestId()).isNotBlank();
              assertThat(call.remainingTime()).isPositive();
              assertThat(call.cancelled()).isFalse();
              observer.onNext(request + "!");
              observer.onCompleted();
            },
            (request, observer) -> observer.onCompleted());
    var grpc = start(app, name, service, builder -> {});
    try {
      withChannel(
          name,
          channel ->
              assertThat(ClientCalls.blockingUnaryCall(channel, UNARY, CallOptions.DEFAULT, "hi"))
                  .isEqualTo("hi!"));
    } finally {
      grpc.stop();
      app.close();
    }
  }

  @Test
  void serverStreamingDeliversEveryItem() throws Exception {
    var name = InProcessServerBuilder.generateName();
    var app = Axiom.create();
    var service =
        new Service(
            (request, observer) -> observer.onCompleted(),
            (request, observer) -> {
              for (int index = 0; index < 3; index++) {
                observer.onNext(request + index);
              }
              observer.onCompleted();
            });
    var grpc = start(app, name, service, builder -> {});
    try {
      withChannel(
          name,
          channel -> {
            var received = new ArrayList<String>();
            ClientCalls.blockingServerStreamingCall(channel, STREAM, CallOptions.DEFAULT, "x")
                .forEachRemaining(received::add);
            assertThat(received).containsExactly("x0", "x1", "x2");
          });
    } finally {
      grpc.stop();
      app.close();
    }
  }

  @Test
  void clientDeadlineIsVisibleAndCancelsTheCall() throws Exception {
    var name = InProcessServerBuilder.generateName();
    var app = Axiom.create();
    var cancelled = new CountDownLatch(1);
    var observed = new AtomicReference<Duration>();
    var service =
        new Service(
            (request, observer) -> {
              var call = GrpcCall.current();
              observed.set(call.remainingTime());
              call.onCancel(cancelled::countDown);
              assertThat(await(cancelled)).isTrue();
            },
            (request, observer) -> observer.onCompleted());
    var grpc = start(app, name, service, builder -> {});
    try {
      withChannel(
          name,
          channel -> {
            var options = CallOptions.DEFAULT.withDeadlineAfter(200, TimeUnit.MILLISECONDS);
            assertThatThrownBy(() -> ClientCalls.blockingUnaryCall(channel, UNARY, options, "hi"))
                .isInstanceOf(StatusRuntimeException.class)
                .satisfies(
                    failure -> assertThat(code(failure)).isEqualTo(Status.Code.DEADLINE_EXCEEDED));
            assertThat(cancelled.getCount()).isZero();
            assertThat(observed.get()).isPositive().isLessThanOrEqualTo(Duration.ofMillis(200));
          });
    } finally {
      grpc.stop();
      app.close();
    }
  }

  @Test
  void clientCancellationIsDeliveredToTheHandler() throws Exception {
    var name = InProcessServerBuilder.generateName();
    var app = Axiom.create();
    var started = new CountDownLatch(1);
    var cancelled = new CountDownLatch(1);
    var service =
        new Service(
            (request, observer) -> {
              GrpcCall.current().onCancel(cancelled::countDown);
              started.countDown();
              assertThat(await(cancelled)).isTrue();
            },
            (request, observer) -> observer.onCompleted());
    var grpc = start(app, name, service, builder -> {});
    try {
      withChannel(
          name,
          channel -> {
            var clientContext = Context.current().withCancellation();
            var previous = clientContext.attach();
            try {
              var observer = new RecordingObserver();
              ClientCalls.asyncUnaryCall(
                  channel.newCall(UNARY, CallOptions.DEFAULT), "hi", observer);
              assertThat(started.await(2, TimeUnit.SECONDS)).isTrue();
              clientContext.cancel(null);
              assertThat(await(cancelled)).isTrue();
              assertThat(observer.done.await(2, TimeUnit.SECONDS)).isTrue();
              assertThat(code(observer.error.get())).isEqualTo(Status.Code.CANCELLED);
            } finally {
              clientContext.detach(previous);
            }
          });
    } finally {
      grpc.stop();
      app.close();
    }
  }

  @Test
  void applicationCloseDrainsRunningCallsAndSharesReadiness() throws Exception {
    var name = InProcessServerBuilder.generateName();
    var app = Axiom.create();
    var healthBuilder = Health.builder(app).readiness("database", () -> true);
    var started = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    var service =
        new Service(
            (request, observer) -> {
              started.countDown();
              assertThat(await(release)).isTrue();
              observer.onNext("done");
              observer.onCompleted();
            },
            (request, observer) -> observer.onCompleted());
    var builder =
        Grpc.builder(app)
            .service(service)
            .transport(InProcessServerBuilder.forName(name))
            .drainGrace(Duration.ofSeconds(2))
            .readiness(healthBuilder);
    var grpc = builder.build();
    var health = healthBuilder.build();
    health.register(app);
    grpc.start();
    try {
      withChannel(
          name,
          channel -> {
            assertThat(grpc.isReady()).isTrue();
            assertThat(health.readiness().status()).isEqualTo(Health.Status.UP);
            var observer = new RecordingObserver();
            ClientCalls.asyncUnaryCall(channel.newCall(UNARY, CallOptions.DEFAULT), "hi", observer);
            assertThat(started.await(2, TimeUnit.SECONDS)).isTrue();
            app.close();
            assertThat(grpc.isRunning()).isFalse();
            assertThat(grpc.isReady()).isFalse();
            assertThat(health.readiness().status()).isEqualTo(Health.Status.DOWN);
            assertThatThrownBy(() ->
                    ClientCalls.blockingUnaryCall(channel, UNARY, CallOptions.DEFAULT, "later"))
                .isInstanceOf(StatusRuntimeException.class)
                .satisfies(failure -> assertThat(code(failure)).isEqualTo(Status.Code.UNAVAILABLE));
            release.countDown();
            assertThat(observer.done.await(2, TimeUnit.SECONDS)).isTrue();
            assertThat(observer.error.get()).isNull();
            assertThat(observer.values).containsExactly("done");
            assertThat(grpc.awaitTermination(Duration.ofSeconds(2))).isTrue();
          });
    } finally {
      grpc.stop();
      app.close();
    }
  }

  @Test
  void errorsAreMappedToGrpcStatuses() throws Exception {
    var name = InProcessServerBuilder.generateName();
    var app = Axiom.create();
    var service =
        new Service(
            (request, observer) -> {
              if (request.equals("bad")) {
                throw new BadRequestException("invalid");
              }
              throw new IllegalStateException("boom");
            },
            (request, observer) -> observer.onCompleted());
    var grpc =
        start(
            app,
            name,
            service,
            builder -> builder.mapError(IllegalStateException.class, Status.FAILED_PRECONDITION));
    try {
      withChannel(
          name,
          channel -> {
            assertThatThrownBy(
                    () -> ClientCalls.blockingUnaryCall(channel, UNARY, CallOptions.DEFAULT, "bad"))
                .isInstanceOf(StatusRuntimeException.class)
                .satisfies(
                    failure -> assertThat(code(failure)).isEqualTo(Status.Code.INVALID_ARGUMENT));
            assertThatThrownBy(() ->
                    ClientCalls.blockingUnaryCall(channel, UNARY, CallOptions.DEFAULT, "other"))
                .isInstanceOf(StatusRuntimeException.class)
                .satisfies(failure -> {
                  assertThat(code(failure)).isEqualTo(Status.Code.FAILED_PRECONDITION);
                  assertThat(((StatusRuntimeException) failure).getStatus().getDescription())
                      .isEqualTo("boom");
                });
          });
    } finally {
      grpc.stop();
      app.close();
    }
  }

  @Test
  void configurationIsValidatedBeforeStarting() {
    var app = Axiom.create();
    var noService = Grpc.builder(app);
    assertThatIllegalArgumentException()
        .isThrownBy(noService::build)
        .withMessageContaining("at least one");
    var supplier = TlsOptions.builder().sslContext(() -> null).build();
    assertThatIllegalArgumentException()
        .isThrownBy(() -> Grpc.builder(app).service(quiet()).tls(supplier).build())
        .withMessageContaining("SSLContext");
    assertThatIllegalArgumentException()
        .isThrownBy(() -> Grpc.builder(app).service(quiet()).address("", 9090))
        .withMessageContaining("Invalid");
    assertThatIllegalArgumentException()
        .isThrownBy(() ->
            Grpc.builder(app).service(quiet()).mapError(IllegalStateException.class, Status.OK))
        .withMessageContaining("OK");
    assertThatIllegalArgumentException()
        .isThrownBy(() -> Grpc.builder(app).service(quiet()).drainGrace(Duration.ofDays(2)));
  }

  @Test
  void serverCannotBeStartedTwiceOrAfterApplicationClose() throws Exception {
    var name = InProcessServerBuilder.generateName();
    var app = Axiom.create();
    var grpc = start(app, name, quiet(), builder -> {});
    try {
      assertThatIllegalStateException()
          .isThrownBy(grpc::start)
          .withMessageContaining("already started");
    } finally {
      grpc.stop();
    }
    app.close();
    var closed =
        Grpc.builder(app)
            .service(quiet())
            .transport(InProcessServerBuilder.forName(name + "-closed"))
            .build();
    assertThatIllegalStateException().isThrownBy(closed::start).withMessageContaining("closed");
  }
}
