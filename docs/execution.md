# Request execution and deadlines

HTTP handlers and response preparation run on one Java 21 virtual thread per
admitted request. Blocking a handler does not occupy a Netty I/O thread. The
protocol-neutral dispatcher lives in `axiom-server`; `axiom-http` uses it as an
implementation dependency. Core remains independent of both modules.

## Request metadata

```java
var app = Axiom.create();
app.requestTimeout(Duration.ofSeconds(3));
app.get("/status", ctx -> {
    var execution = ctx.execution();
    return "Request " + execution.requestId()
            + " has " + execution.remainingTime().toMillis() + " ms remaining";
});
app.listen(8080);
```

Every invocation has immutable `ExecutionContext` metadata: a generated request ID
and a monotonic deadline. The ID is a random 96-bit per-process prefix plus a
hexadecimal sequence number (for example `q3J0bW9yZS1yYW5k-1a`), so generating it
costs one atomic increment rather than a secure random draw per request. The
metadata can be shared with application tasks, while the mutable handler `Context`
remains confined to the handler's thread. No thread-local propagation is provided.
Pass the metadata explicitly to work that needs the remaining budget.

HTTP responses carry the same identity in `X-Request-ID`, including framework
failures. Incoming IDs and application-supplied response IDs do not replace the
framework identity. IDs are correlation values, not authentication credentials:
anyone who has seen one ID can predict later IDs from the same process.
Handler exception logs include the identity; no exception details enter the body.

## Deadline boundary

The default budget is ten seconds. Configure `app.requestTimeout(Duration)` before
startup; positive budgets up to one day are supported and configuration freezes
with the routes. All listeners share this configuration. Route overrides are not
implemented yet.

For HTTP, the budget starts when validated request headers are adapted, before
the body is received and before waiting behind earlier pipelined requests. It
covers body receipt, dispatch, application code, and response preparation
(including codec encoding). An expired queued request is never invoked. A timer
interrupts active execution and produces a single 504 Gateway Timeout when the
connection is still writable. Timeout closes the connection and drops later
pipelined requests. Results returned after cancellation or timeout are discarded.
Application-thrown timeout exceptions remain application failures (500).

Completion and timeout compete for one terminal outcome. Completion checks the
monotonic deadline even if the timer thread is delayed. The budget ends when the
prepared response wins completion; it does not bound subsequent socket writes.
The 30-second network inactivity timeout does not close a connection while its
request waits for admission or runs; the queue wait and this budget bound it
instead. It still closes idle connections and stalled response writes. Reading
continues throughout. A request head (request line and headers) must arrive within
ten seconds of its first byte, or the listener answers 408 and closes; trickling
bytes does not extend that bound. Receiving the request body counts against the
execution budget, which starts when the head is parsed: a body still incomplete at
the deadline is answered 408 and the handler never runs (see
[request bodies](bodies.md)). Slow response delivery has no absolute deadline; only
the inactivity timeout applies to it. See [HTTP listeners](http.md) for the limits.

## Capacity and cancellation

Each listener defaults to 36 active tasks and no dispatcher waiting queue.
Configure aggregate and route limits through `app.admissionPolicy(...)` before
startup. Bounded queues create no execution threads and expire without invoking
handlers. Excess capacity and queue-wait expiry produce 503; the execution
deadline continues while a request is queued and produces 504 if it expires first.
See [admission and bounded queues](admission.md) for configuration, scheduling,
listener scope and counters. Connection and pipeline bounds still apply.
Cancellation interrupts the owned virtual thread. **Capacity is released only
when that thread exits the request action**, even if its outcome is already 504
or cancelled. A handler that ignores interruption continues occupying capacity;
its late value is discarded. A timeout cannot roll back application side effects
or stop tasks the application launches independently.

Closing a connection cancels its active execution. Reads continue while a
handler runs, so a remote disconnect cancels it promptly. Closing a listener stops
admission and fails waiting requests with 503, lets active executions finish
within a grace period, then cancels what remains and closes connections.
`Server.termination()` completes after its executors and transport stop. Close is
idempotent and nonblocking, including inside a handler; termination may wait
indefinitely for code that refuses interruption.

## Synchronous execution

`app.handle(request)` still executes on the calling thread. It maps
`AxiomException`s to problem responses (see [errors](errors.md)) and propagates
other handler exceptions. `TestClient` instead runs each request through a private
dispatcher (virtual thread, application admission policy and timeout), returns
503/504 problem responses for admission and deadline failures, and still propagates
other handler exceptions. Each direct invocation gets fresh metadata using
the configured budget. The adapter overload `handle(request, execution)` accepts
explicit metadata and rejects an already expired context with `TimeoutException`.
These synchronous paths do not schedule cancellation or interrupt caller-owned
threads. Their metadata lets application code check its remaining budget, but
they do not simulate the HTTP dispatcher's timing or wire behavior.
