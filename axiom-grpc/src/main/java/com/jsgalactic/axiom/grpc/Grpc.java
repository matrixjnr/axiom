package com.jsgalactic.axiom.grpc;

import com.jsgalactic.axiom.application.Application;
import com.jsgalactic.axiom.error.BadRequestException;
import com.jsgalactic.axiom.error.ConflictException;
import com.jsgalactic.axiom.error.DecodeException;
import com.jsgalactic.axiom.error.ForbiddenException;
import com.jsgalactic.axiom.error.NotFoundException;
import com.jsgalactic.axiom.error.RequestTimeoutException;
import com.jsgalactic.axiom.error.ServiceUnavailableException;
import com.jsgalactic.axiom.error.TooManyRequestsException;
import com.jsgalactic.axiom.error.UnauthorizedException;
import com.jsgalactic.axiom.lifecycle.TlsConfigurationException;
import com.jsgalactic.axiom.lifecycle.TlsOptions;
import com.jsgalactic.axiom.observability.Health;
import io.grpc.BindableService;
import io.grpc.Context;
import io.grpc.Contexts;
import io.grpc.ForwardingServerCall;
import io.grpc.ForwardingServerCallListener;
import io.grpc.Metadata;
import io.grpc.Server;
import io.grpc.ServerBuilder;
import io.grpc.ServerCall;
import io.grpc.ServerCallHandler;
import io.grpc.ServerInterceptor;
import io.grpc.ServerInterceptors;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.netty.shaded.io.grpc.netty.GrpcSslContexts;
import io.grpc.netty.shaded.io.grpc.netty.NettyServerBuilder;
import io.grpc.netty.shaded.io.netty.handler.ssl.ClientAuth;
import io.grpc.netty.shaded.io.netty.handler.ssl.SslContext;
import io.grpc.netty.shaded.io.netty.handler.ssl.SslContextBuilder;
import io.grpc.netty.shaded.io.netty.handler.ssl.SslProvider;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Runs generated gRPC services beside an Axiom application. Each instance owns one gRPC server on
 * its own listener, so the HTTP listener keeps speaking HTTP/1.1 and grpc-java keeps its own HTTP/2
 * transport. Services are ordinary {@link BindableService} implementations; no Netty type appears
 * in this API. Closing the application starts a graceful drain of the gRPC server, and a drain of
 * the application stops it accepting new calls.
 */
public final class Grpc implements AutoCloseable {
  private static final Duration MAX_GRACE = Duration.ofDays(1);
  private static final Duration DEFAULT_DRAIN_GRACE = Duration.ofSeconds(30);
  private static final String DEFAULT_HOST = "0.0.0.0";
  private static final int DEFAULT_PORT = 9090;

  private final Application application;
  private final List<BindableService> services;
  private final String host;
  private final int port;
  private final TlsOptions tls;
  private final Duration drainGrace;
  private final Map<Class<?>, Status> errors;
  private final Health.Builder health;
  private final ServerBuilder<?> transport;
  private final AtomicBoolean draining = new AtomicBoolean();
  private volatile Server server;
  private volatile boolean started;

  private Grpc(Builder builder) {
    application = builder.application;
    services = List.copyOf(builder.services);
    host = builder.host;
    port = builder.port;
    tls = builder.tls;
    drainGrace = builder.drainGrace;
    errors = Map.copyOf(builder.errors);
    health = builder.health;
    transport = builder.transport;
  }

  /**
   * Starts a builder for one gRPC server. The application supplies the lifecycle hooks; it need not
   * have an HTTP listener.
   *
   * @param application the application whose lifecycle owns the server
   * @return a new builder
   * @throws NullPointerException if the application is null
   */
  public static Builder builder(Application application) {
    return new Builder(Objects.requireNonNull(application, "application"));
  }

  /**
   * Starts the server and binds its listener. Calling this twice fails; starting after the
   * application closed fails as well. The application is started if it is still configuring.
   * Services are intercepted before the server starts, so their handlers can read {@link
   * GrpcCall#current()} and exceptions are mapped as configured.
   *
   * @throws IOException if the listener cannot bind or TLS material cannot be used
   * @throws IllegalStateException if the server or the application was already started or closed
   */
  public synchronized void start() throws IOException {
    if (started) {
      throw new IllegalStateException("The gRPC server is already started");
    }
    if (application.state() == Application.State.CLOSED) {
      throw new IllegalStateException("The application is closed");
    }
    application.start();
    var builder =
        transport != null
            ? transport
            : NettyServerBuilder.forAddress(new InetSocketAddress(host, port));
    if (tls != null) {
      if (!(builder instanceof NettyServerBuilder netty)) {
        throw new IllegalStateException("TLS needs the gRPC Netty transport");
      }
      netty.sslContext(sslContext(tls));
    }
    List<ServerInterceptor> interceptors =
        List.of(new CallInterceptor(), new ErrorInterceptor(errors));
    for (var service : services) {
      builder.addService(ServerInterceptors.intercept(service, interceptors));
    }
    var bound = builder.build();
    try {
      bound.start();
    } catch (IOException failure) {
      bound.shutdownNow();
      throw failure;
    }
    server = bound;
    started = true;
    try {
      application.onDrain(this::beginDrain);
      application.onClose(this::beginDrain);
    } catch (RuntimeException failure) {
      bound.shutdownNow();
      server = null;
      started = false;
      throw failure;
    }
  }

  /**
   * Reports whether the server accepted calls and has not begun shutting down.
   *
   * @return true while the listener is accepting calls
   */
  public boolean isRunning() {
    var current = server;
    return started && current != null && !current.isShutdown() && !current.isTerminated();
  }

  /**
   * Reports whether this server should receive traffic. It is true only while {@link #isRunning()}
   * and before a drain begins, which makes it suitable as an Axiom readiness check.
   *
   * @return true while the server is accepting calls
   */
  public boolean isReady() {
    return isRunning() && !draining.get();
  }

  /**
   * Returns the bound port, or the configured port before startup. Binding with port zero chooses a
   * free port, which this method reports once the server started.
   *
   * @return the port of this server, or -1 for a transport without a socket
   */
  public int port() {
    var current = server;
    return current == null ? port : current.getPort();
  }

  /**
   * Begins a graceful drain and waits for it to finish. New calls are refused while calls already
   * running may finish within the configured grace period; a call still running after it is
   * cancelled. Idempotent, and safe before startup.
   */
  public void stop() {
    beginDrain();
    try {
      awaitTermination(drainGrace);
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
    }
  }

  /**
   * Waits for the drain to finish without starting one.
   *
   * @param timeout positive or zero, at most one day
   * @return true when the server terminated or was never started
   * @throws InterruptedException if the waiting thread is interrupted
   * @throws IllegalArgumentException if the timeout is out of range
   */
  public boolean awaitTermination(Duration timeout) throws InterruptedException {
    Objects.requireNonNull(timeout, "timeout");
    if (timeout.isNegative() || timeout.compareTo(MAX_GRACE) > 0) {
      throw new IllegalArgumentException("timeout must be from zero to " + MAX_GRACE);
    }
    var current = server;
    return current == null || current.awaitTermination(timeout.toMillis(), TimeUnit.MILLISECONDS);
  }

  /** Begins a graceful drain without waiting; {@link #stop()} is the waiting variant. */
  @Override
  public void close() {
    beginDrain();
  }

  private void beginDrain() {
    if (!draining.compareAndSet(false, true)) {
      return;
    }
    var current = server;
    if (current == null || current.isShutdown()) {
      return;
    }
    current.shutdown();
    Thread.ofVirtual()
        .name("axiom-grpc-drain")
        .start(() -> {
          try {
            if (!current.awaitTermination(drainGrace.toMillis(), TimeUnit.MILLISECONDS)) {
              current.shutdownNow();
            }
          } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            current.shutdownNow();
          }
        });
  }

  private static SslContext sslContext(TlsOptions options) throws TlsConfigurationException {
    if (options.sslContext().isPresent()) {
      throw new IllegalArgumentException(
          "gRPC TLS needs PEM files; an SSLContext supplier cannot supply gRPC's TLS context");
    }
    if (options.reloadInterval().isPresent()) {
      throw new IllegalArgumentException(
          "gRPC TLS does not reload key material; remove reloadInterval");
    }
    var chain = options.certificateChain().orElseThrow().toFile();
    var key = options.privateKey().orElseThrow().toFile();
    try {
      var builder =
          GrpcSslContexts.configure(SslContextBuilder.forServer(chain, key), SslProvider.JDK);
      builder.ciphers(options.cipherSuites()).protocols(options.minimumProtocol().jdkName());
      if (options.clientAuth() == TlsOptions.ClientAuth.REQUIRED) {
        builder
            .clientAuth(ClientAuth.REQUIRE)
            .trustManager(options.clientTrust().orElseThrow().toFile());
      }
      return builder.build();
    } catch (Exception failure) {
      // A parser's message can echo key material; report only the class of the failure.
      throw new TlsConfigurationException(
          "gRPC TLS material is not usable: " + failure.getClass().getSimpleName());
    }
  }

  private static Status mapped(Status status, Map<Class<?>, Status> errors) {
    var cause = status.getCause();
    if (status.getCode() != Status.Code.UNKNOWN || cause == null) {
      return status;
    }
    if (cause instanceof StatusRuntimeException reported) {
      return reported.getStatus();
    }
    for (Class<?> type = cause.getClass();
        type != null && type != Object.class;
        type = type.getSuperclass()) {
      var configured = errors.get(type);
      if (configured != null) {
        return described(configured, cause, status);
      }
    }
    var builtIn = builtIn(cause);
    return builtIn == null ? status : described(builtIn, cause, status);
  }

  private static Status described(Status status, Throwable cause, Status original) {
    var message = cause.getMessage();
    return status
        .withDescription(message == null || message.isBlank() ? original.getDescription() : message)
        .withCause(cause);
  }

  private static Status builtIn(Throwable cause) {
    if (cause instanceof BadRequestException || cause instanceof DecodeException) {
      return Status.INVALID_ARGUMENT;
    }
    if (cause instanceof NotFoundException) {
      return Status.NOT_FOUND;
    }
    if (cause instanceof UnauthorizedException) {
      return Status.UNAUTHENTICATED;
    }
    if (cause instanceof ForbiddenException) {
      return Status.PERMISSION_DENIED;
    }
    if (cause instanceof ConflictException) {
      return Status.ALREADY_EXISTS;
    }
    if (cause instanceof TooManyRequestsException) {
      return Status.RESOURCE_EXHAUSTED;
    }
    if (cause instanceof RequestTimeoutException) {
      return Status.DEADLINE_EXCEEDED;
    }
    if (cause instanceof ServiceUnavailableException) {
      return Status.UNAVAILABLE;
    }
    return null;
  }

  private static final class CallInterceptor implements ServerInterceptor {
    @Override
    public <ReqT, RespT> ServerCall.Listener<ReqT> interceptCall(
        ServerCall<ReqT, RespT> call, Metadata headers, ServerCallHandler<ReqT, RespT> next) {
      var attached = Context.current().withValue(GrpcCall.KEY, GrpcCall.attach(Context.current()));
      return Contexts.interceptCall(attached, call, headers, next);
    }
  }

  private static final class ErrorInterceptor implements ServerInterceptor {
    private final Map<Class<?>, Status> errors;

    ErrorInterceptor(Map<Class<?>, Status> errors) {
      this.errors = errors;
    }

    @Override
    public <ReqT, RespT> ServerCall.Listener<ReqT> interceptCall(
        ServerCall<ReqT, RespT> call, Metadata headers, ServerCallHandler<ReqT, RespT> next) {
      ServerCall<ReqT, RespT> wrapped =
          new ForwardingServerCall.SimpleForwardingServerCall<>(call) {
            @Override
            public void close(Status status, Metadata trailers) {
              super.close(mapped(status, errors), trailers);
            }
          };
      ServerCall.Listener<ReqT> delegate;
      try {
        delegate = next.startCall(wrapped, headers);
      } catch (RuntimeException failure) {
        fail(wrapped, failure);
        return new ServerCall.Listener<>() {};
      }
      return new ForwardingServerCallListener.SimpleForwardingServerCallListener<>(delegate) {
        @Override
        public void onMessage(ReqT message) {
          guarded(() -> super.onMessage(message), wrapped);
        }

        @Override
        public void onHalfClose() {
          guarded(super::onHalfClose, wrapped);
        }

        @Override
        public void onReady() {
          guarded(super::onReady, wrapped);
        }

        @Override
        public void onCancel() {
          guarded(super::onCancel, wrapped);
        }

        @Override
        public void onComplete() {
          guarded(super::onComplete, wrapped);
        }
      };
    }

    private void guarded(Runnable action, ServerCall<?, ?> call) {
      try {
        action.run();
      } catch (RuntimeException failure) {
        fail(call, failure);
      }
    }

    private void fail(ServerCall<?, ?> call, RuntimeException failure) {
      if (!call.isCancelled()) {
        call.close(
            mapped(
                Status.UNKNOWN.withDescription("Application failure").withCause(failure), errors),
            new Metadata());
      }
    }
  }

  /** Collects services and settings before {@link #build()}. Not thread-safe. */
  public static final class Builder {
    private final Application application;
    private final List<BindableService> services = new ArrayList<>();
    private final Map<Class<?>, Status> errors = new LinkedHashMap<>();
    private String host = DEFAULT_HOST;
    private int port = DEFAULT_PORT;
    private TlsOptions tls;
    private Duration drainGrace = DEFAULT_DRAIN_GRACE;
    private Health.Builder health;
    private ServerBuilder<?> transport;

    private Builder(Application application) {
      this.application = application;
    }

    /**
     * Registers a generated service. Every registered service is attached to the same server;
     * duplicate service names fail when gRPC starts.
     *
     * @param service a generated service implementation
     * @return this builder
     * @throws NullPointerException if the service is null
     */
    public Builder service(BindableService service) {
      services.add(Objects.requireNonNull(service, "service"));
      return this;
    }

    /**
     * Sets the listener address. The default binds all interfaces on port 9090; port zero asks the
     * operating system for a free port, reported by {@link Grpc#port()} after startup.
     *
     * @param host host name or address to bind
     * @param port port from 0 to 65535
     * @return this builder
     * @throws IllegalArgumentException for an empty host or a port out of range
     */
    public Builder address(String host, int port) {
      Objects.requireNonNull(host, "host");
      if (host.isBlank() || port < 0 || port > 65535) {
        throw new IllegalArgumentException("Invalid gRPC listener address: " + host + ":" + port);
      }
      this.host = host;
      this.port = port;
      return this;
    }

    /**
     * Sets TLS from Axiom's transport-neutral options. PEM certificate and key files are mapped to
     * gRPC's TLS context with the configured cipher suites, minimum protocol and client
     * certificates. An {@code SSLContext} supplier or a reload interval cannot be mapped and is
     * refused here.
     *
     * @param options Axiom TLS options, or null for a plaintext listener
     * @return this builder
     */
    public Builder tls(TlsOptions options) {
      tls = options;
      return this;
    }

    /**
     * Sets how long a graceful drain waits for running calls before cancelling them. The default is
     * 30 seconds.
     *
     * @param grace zero to one day
     * @return this builder
     * @throws IllegalArgumentException if the grace period is out of range
     */
    public Builder drainGrace(Duration grace) {
      Objects.requireNonNull(grace, "grace");
      if (grace.isNegative() || grace.compareTo(MAX_GRACE) > 0) {
        throw new IllegalArgumentException("drainGrace must be from zero to " + MAX_GRACE);
      }
      drainGrace = grace;
      return this;
    }

    /**
     * Maps an exception class and its subclasses to a gRPC status. A handler exception that gRPC
     * reports as UNKNOWN is closed with the nearest registered status and the exception's message.
     * Axiom's own errors have built-in mappings; a registered class wins over them.
     *
     * @param type exception class
     * @param status status to answer, not OK
     * @return this builder
     * @throws IllegalArgumentException if the status is OK
     */
    public Builder mapError(Class<? extends Exception> type, Status status) {
      Objects.requireNonNull(type, "type");
      Objects.requireNonNull(status, "status");
      if (status.getCode() == Status.Code.OK) {
        throw new IllegalArgumentException("Cannot map an error to OK");
      }
      errors.put(type, status);
      return this;
    }

    /**
     * Adds a readiness check named {@code grpc} to an Axiom health builder. It is UP while the
     * server is running and DOWN once a drain begins or the server stops.
     *
     * @param builder health builder that has not been built yet
     * @return this builder
     */
    public Builder readiness(Health.Builder builder) {
      health = Objects.requireNonNull(builder, "builder");
      return this;
    }

    /**
     * Builds the server without starting it. Build time validation covers the address, TLS source,
     * grace period and readiness hook; binding happens in {@link Grpc#start()}.
     *
     * @return the configured server
     * @throws IllegalArgumentException if no service was registered or TLS is not mappable
     */
    public Grpc build() {
      if (services.isEmpty()) {
        throw new IllegalArgumentException("Register at least one gRPC service");
      }
      if (tls != null) {
        if (tls.sslContext().isPresent()) {
          throw new IllegalArgumentException(
              "gRPC TLS needs PEM files; an SSLContext supplier cannot supply gRPC's TLS context");
        }
        if (tls.reloadInterval().isPresent()) {
          throw new IllegalArgumentException(
              "gRPC TLS does not reload key material; remove reloadInterval");
        }
      }
      var grpc = new Grpc(this);
      if (health != null) {
        health.readiness("grpc", grpc::isReady);
      }
      return grpc;
    }

    /** Replaces the Netty transport with another gRPC transport; for in-process tests. */
    Builder transport(ServerBuilder<?> builder) {
      transport = Objects.requireNonNull(builder, "builder");
      return this;
    }
  }
}
