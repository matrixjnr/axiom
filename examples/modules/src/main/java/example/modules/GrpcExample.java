package example.modules;

import com.jsgalactic.axiom.Axiom;
import com.jsgalactic.axiom.application.Application;
import com.jsgalactic.axiom.grpc.Grpc;
import io.grpc.BindableService;
import io.grpc.MethodDescriptor;
import io.grpc.ServerServiceDefinition;
import io.grpc.stub.ServerCalls;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;

/**
 * Runs a hand-written gRPC service beside an HTTP application. A generated stub works the same way:
 * the only requirement is a {@link BindableService}. The gRPC listener is separate from the
 * HTTP/1.1 listener because gRPC needs HTTP/2.
 */
public final class GrpcExample {
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

  /** The unary method this service exposes. */
  public static final MethodDescriptor<String, String> ECHO =
      MethodDescriptor.<String, String>newBuilder()
          .setType(MethodDescriptor.MethodType.UNARY)
          .setFullMethodName("example.Echo/Echo")
          .setRequestMarshaller(TEXT)
          .setResponseMarshaller(TEXT)
          .build();

  private GrpcExample() {}

  /**
   * Builds the gRPC server for an application. Nothing starts until {@link Grpc#start()}.
   *
   * @param app the application whose lifecycle drains and closes the server
   * @return the configured server
   */
  public static Grpc create(Application app) {
    return Grpc.builder(app).service(new EchoService()).address("127.0.0.1", 9090).build();
  }

  /**
   * Serves gRPC on port 9090 and HTTP on http://127.0.0.1:8080/ until the process stops.
   *
   * @param args unused
   * @throws Exception if either listener cannot start
   */
  public static void main(String[] args) throws Exception {
    var app = Axiom.create().closeOnJvmShutdown();
    var grpc = create(app);
    // Start gRPC first: it registers its drain and close hooks while the application configures.
    grpc.start();
    var server = app.listen(8080);
    System.out.println("gRPC echo on 127.0.0.1:9090, HTTP on http://127.0.0.1:8080/");
    server.termination().toCompletableFuture().join();
  }

  /** A unary service implemented without generated code. */
  public static final class EchoService implements BindableService {
    @Override
    public ServerServiceDefinition bindService() {
      return ServerServiceDefinition.builder("example.Echo")
          .addMethod(
              ECHO,
              ServerCalls.asyncUnaryCall((request, observer) -> {
                observer.onNext("echo:" + request);
                observer.onCompleted();
              }))
          .build();
    }
  }
}
