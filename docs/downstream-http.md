---
title: Downstream HTTP calls
parent: Guides
nav_order: 12
---

# Downstream HTTP calls inside a handler

The [DownstreamHttp adapter](../examples/readme/src/main/java/DownstreamHttp.java)
shows how to use Java 21's built-in HTTP client with the
[scoped parallel handler example](middleware.md#decision-synchronous-middleware-parallel-work-inside-handlers).
It is application example code, with no new Axiom API or client dependency.

## Own the client at application scope

Create one `HttpClient` for the application so calls share its connection pool.
Give it a finite connect timeout and disable automatic redirects. The adapter
borrows it; handlers and child tasks never close it. On shutdown, close the
application first. `Application.close()` is nonblocking: await listener
termination before closing the client.

```java
try (var http = HttpClient.newBuilder()
        .version(HttpClient.Version.HTTP_1_1)
        .connectTimeout(Duration.ofSeconds(2))
        .followRedirects(HttpClient.Redirect.NEVER).build();
        var app = FanOutApi.create(
                (id, budget) -> DownstreamHttp.fetch(http,
                        URI.create("https://profiles.internal/profile?id="
                                + URLEncoder.encode(id, StandardCharsets.UTF_8)), budget),
                (id, budget) -> DownstreamHttp.fetch(http,
                        URI.create("https://recommendations.internal/items?id="
                                + URLEncoder.encode(id, StandardCharsets.UTF_8)), budget))) {
    app.error(TimeoutException.class, (ctx, failure) -> { throw new GatewayTimeoutException(); });
    app.error(HttpTimeoutException.class, (ctx, failure) -> { throw new GatewayTimeoutException(); });
    var server = app.listen(8080);
    try { server.termination().toCompletableFuture().join(); }
    finally {
        app.close();
        server.termination().toCompletableFuture().join();
    }
}
```

These service addresses are placeholders supplied by the application, not URLs
accepted from request parameters. Import the named types from `java.net`,
`java.net.http`, `java.nio.charset`, `java.time`, `java.util.concurrent` and
`com.jsgalactic.axiom.error`. The example classes are in the default package.

## Bound the complete operation

`FanOutApi` passes each child the remaining request budget. The adapter sets
`HttpRequest.timeout` and also waits on the original `sendAsync` future with
that budget, subtracting time already spent preparing and starting the call.
The body subscriber completes only after the entire body arrives. Thus a
server that sends headers and then stalls cannot leave the child waiting
indefinitely for body bytes. A zero or negative budget fails before sending.

The adapter requires status 200 and decodes UTF-8 text. It buffers at most
64 KiB of body bytes; an oversized body cancels the subscription and fails
with `IOException`. Choose a different status policy, media type and explicit
size cap for a real service. It does not return an open stream to the handler.
Network errors and unexpected statuses remain exceptions for the application's
error policy; downstream timeout exceptions can be mapped to 504 as above.
Framework request expiry and interruption still follow the cancellation path.

## Cancellation and cleanup guarantees

On interruption, deadline expiry or any other exit, the adapter calls
`cancel(true)` on the original exchange future. It propagates the original
exception rather than an `ExecutionException` wrapper. The local executor in
`FanOutApi` waits for its Java child tasks to terminate, including when a
sibling fails. It does not close the shared HTTP client for each request.

The [Java 21 HttpClient contract](https://docs.oracle.com/en/java/javase/21/docs/api/java.net.http/java/net/http/HttpClient.html)
says cancellation attempts to release network resources asynchronously and gives
no exact release time. A request may already have reached the downstream
server. The adapter bounds waiting for a result and requests cancellation;
it does not guarantee rollback of downstream work or immediate socket disposal.
Use idempotency controls for operations that can have side effects.

The [real-socket tests](../examples/readme/src/test/java/DownstreamHttpLiveTest.java)
exercise HTTP/1.1 on loopback: interruption before headers and during the body,
body wait expiry after headers, and a failed sibling cancelling an HTTP call.
They coordinate endpoint state with latches and observe peer EOF or reset after
cancellation. The finite waits in those tests are regression watchdogs, not a
network cleanup SLA. They also check UTF-8, status errors, response size limits,
an exhausted budget and continued use of the borrowed client.

For other clients, including JDBC drivers and native libraries, verify their
interruption and timeout contracts separately. An uninterruptible call can
delay scoped executor closure past the request deadline. Java cannot safely
force-stop arbitrary application code. The accepted boundary and verification
work are recorded in [#190](https://github.com/matrixjnr/axiom/issues/190).
