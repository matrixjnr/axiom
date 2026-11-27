---
title: gRPC
parent: Guides
nav_order: 13
---

# gRPC

`axiom-grpc` is an opt-in adapter that runs generated gRPC services beside an Axiom application. The
adapter depends on `axiom-core` and on pinned grpc-java artifacts; nothing in core or the starter
changes, and gRPC's transport (including its shaded Netty) stays inside the adapter. Applications
add the module and their own generated stubs:

```kotlin
dependencies {
    implementation("com.jsgalactic.axiom:axiom-grpc")
    implementation("io.grpc:grpc-protobuf:<grpc-version>")
}
```

The gRPC version is aligned in the BOM's version catalog; applications that use generated protobuf
stubs already have `grpc-stub` and `grpc-protobuf` through their stub dependency.

## Registering services

A generated service implements `io.grpc.BindableService`. Register one or more instances before
startup, then start the gRPC server. The application is started if it is still configuring, so gRPC
can run without an HTTP listener.

```java
var app = Axiom.create();
var grpc = Grpc.builder(app)
        .service(new GreeterImpl())
        .address("0.0.0.0", 9090)
        .build();
grpc.start();
```

No service runs unless `service(...)` was called. Starting twice fails, and binding failures surface
as `IOException`. `port()` reports the bound port after startup, including the OS-chosen port when
the configuration used port zero. No grpc-netty type appears in the API; the transport is built and
owned internally.

## A separate listener by decision

gRPC and the Axiom HTTP listener do not share a port. Axiom's listener speaks HTTP/1.1 over TLS and
advertises ALPN `http/1.1`; gRPC requires HTTP/2 and `h2` ALPN on a TLS listener. Netty cannot serve
both protocols on one TLS port with the two independent ALPN configurations, and merging the two
transports would duplicate grpc-java's HTTP/2 framing, flow control and cancellation machinery.
`axiom-grpc` therefore binds its own listener on its own port and leaves the HTTP listener alone.
Use a different port, and a load balancer or service mesh that routes by port or ALPN if both
protocols must be exposed.

## TLS

`TlsOptions` is the same transport-neutral type the HTTP listener takes. With PEM material, the
adapter maps the certificate chain, key, cipher suites, minimum protocol and required client
certificates to gRPC's TLS context:

```java
var tls = TlsOptions.pem(Path.of("/etc/axiom/chain.pem"), Path.of("/etc/axiom/key.pem"));
var grpc = Grpc.builder(app).service(new GreeterImpl()).tls(tls).build();
```

A `TlsOptions` built with an `SSLContext` supplier or a key reload interval is refused at build time:
gRPC's TLS context needs Netty key material, which cannot be recovered from a JDK `SSLContext`, and
this adapter does not swap TLS material on a live gRPC listener. See [TLS](tls.md#key-material) for
the PEM format and fail-fast behavior of Axiom's own listener. Invalid or unreadable material makes
`start()` throw a `TlsConfigurationException` whose message never contains key bytes.

## Lifecycle and readiness

Closing the application drains the gRPC server: it stops accepting new calls and lets calls already
running finish. The drain grace defaults to 30 seconds and is configurable with
`drainGrace(Duration)`; a call still running when it expires is cancelled. `stop()` and `close()` on
`Grpc` begin the same drain, and `awaitTermination(Duration)` waits for it without side effects.
`isRunning()` reports whether the listener accepts calls; `isReady()` is false from the moment a
drain begins.

The same hooks are available to Axiom's health checks. `readiness(Health.Builder)` adds a `grpc`
readiness check that is UP while the server accepts calls and DOWN once draining, so a single
`Health` instance reflects both listeners:

```java
var healthBuilder = Health.builder(app).readiness("database", () -> pool.isValid());
var grpc = Grpc.builder(app).service(new GreeterImpl()).readiness(healthBuilder).build();
var health = healthBuilder.build();
health.register(app);
app.closeOnJvmShutdown(Duration.ofSeconds(5));
```

Build and register `Health` before starting the application, because registering routes freezes the
router. See [health and readiness](observability.md#health-and-readiness) for probes and drain
semantics. An application that already has its own readiness check can use `grpc::isReady` directly
instead of `readiness(...)`.

## Deadlines, cancellation and errors

gRPC carries the caller's deadline and cancellation in the call context. Inside a generated handler,
`GrpcCall.current()` exposes them without a grpc-java type in the signature:

```java
var call = GrpcCall.current();
Duration left = call.remainingTime();
call.onCancel(() -> cancelWork());
```

`execution()` returns an Axiom `ExecutionContext` with the call's identity and original deadline
budget (one day when the caller sent no deadline). `cancelled()` and `onCancel(Runnable)` observe
client cancellation and deadline expiry; callbacks run once, on the thread that observes the
cancellation, so they must be quick. When the deadline elapses or the caller cancels, gRPC closes
the call with `DEADLINE_EXCEEDED` or `CANCELLED` itself; the adapter does not replace those statuses.

A handler exception is answered with a gRPC status. Axiom's own errors have built-in mappings (for
example `BadRequestException` to `INVALID_ARGUMENT` and `NotFoundException` to `NOT_FOUND`), and
`mapError(type, status)` registers an application mapping that wins over them. The nearest matching
superclass is used, and the exception message becomes the status description. Statuses a handler
produces itself are passed through unchanged. Unmapped exceptions answer `UNKNOWN`.

## Scope and limits

- gRPC uses its own listener, executor and flow control. Axiom's admission budget, middleware and
  HTTP error handlers do not apply to gRPC calls; use gRPC interceptors for cross-cutting concerns.
- PEM TLS is supported; an `SSLContext` supplier, TLS reload and the gRPC health-checking protocol
  service (`grpc.health.v1`) are not. Readiness is shared through Axiom's `Health` instead.
- Requests, streams and connection limits are grpc-java's own; only the drain grace period is set by
  this adapter.
- No performance measurements are published for this transport; size and tune it from your own load
  tests.
