---
title: Admission
parent: Operations
nav_order: 1
---

# Admission and bounded queues

Admission reserves execution capacity before creating a virtual thread. Each
listener has aggregate active and waiting limits for requests, and a separate
aggregate limit for streams. Requests to a registered route also share limits
by method and template: `/users/a` and `/users/b` both count against
`GET /users/:id`. Parameter values cannot create new admission buckets.

## Configuration

```java
import com.jsgalactic.axiom.execution.AdmissionPolicy;
import com.jsgalactic.axiom.execution.StreamPolicy;
import com.jsgalactic.axiom.http.Response;
import java.time.Duration;

var app = Axiom.create();
app.admissionPolicy(new AdmissionPolicy(32, 64, Duration.ofMillis(200)));
var report = app.get("/reports/:id", ctx -> buildReport(ctx.path("id")));
app.admissionPolicy(report, new AdmissionPolicy(2, 8, Duration.ofMillis(100)));
var events = app.get("/events", ctx -> Response.sse(sink -> sink.send("ready")));
app.streamPolicy(StreamPolicy.reject(8));
app.streamPolicy(events, StreamPolicy.reject(2));
app.listen(8080);
```

`AdmissionPolicy(maxActive, maxQueued, queueTimeout)` validates a positive active
limit, a waiting limit from zero to 100,000, and a positive wait of at most one day when
queuing is enabled. `AdmissionPolicy.reject(36)` is the default: 36 active tasks
and no dispatcher queue. Use `reject(n)` for immediate overload rejection.

Configure policies before startup; startup freezes them along with routes.
Overrides require an already registered route. A route without an override uses
the complete aggregate policy as its own limit. An override replaces all three
route values, while aggregate limits continue to apply. Setting either queue
capacity to zero disables waiting for that request. The effective wait is the
smaller of the aggregate and route queue timeouts.

**Limits apply independently to each listener**, including listeners sharing one
application. They are not a budget shared across listeners, processes or hosts.
Direct `handle` calls remain synchronous and bypass admission. `TestClient` applies
the policies through its own dispatcher.
`app.resolve(request)` exposes the compiled route identity for adapters; it
requires a running application and returns empty for 404/405 and for OPTIONS
requests the application answers automatically (see [methods](routing.md#methods)).
Those requests run no handler, but they share one default bucket under the
application's default policy and still consume aggregate capacity when dispatched,
so a saturated listener answers them 503 like any other request.

## Stream capacity

Streams have their own lane. `StreamPolicy(maxActive)` and `StreamPolicy.reject(n)`
set one positive bound on streams running at the same time, configured before
startup like the request policy. The default is `reject(36)`, also the default for
each route; a route override replaces that route's bound while the aggregate bound
still applies. There is no stream queue: when either bound is full, the response is
an ordinary **503** with no stream head at all. Middleware and error handling have
finished before the transfer, so the refusal cannot be mistaken for a stream that
failed partway through.

When a response becomes a stream, the request's active slot is transferred into a
stream slot under the dispatcher's lock. The request slot is immediately available
to another request, so idle subscribers cannot starve ordinary traffic. The stream
keeps the request's deadline and cancellation: its slot is returned when the body
ends, is cancelled, or is cut by shutdown. A body that ignores interruption keeps
its slot until it exits, as an ordinary handler does. Each stream still has one
virtual thread; see [many subscribers](streaming.md#many-subscribers) for keeping
the number of long-lived bodies small.

## Queue behavior

A request starts only when both aggregate and route active capacity are available.
Otherwise it enters a bounded waiting queue if both policies allow waiting and
both queues have room. Waiting creates no execution thread. The dispatcher picks
the oldest eligible request when capacity is released. A blocked route does not
prevent an unrelated route from using spare capacity. FIFO order is preserved
within a route; there is no guarantee of strict arrival order across routes.

Queue residence starts when the dispatcher accepts the request into its queue.
The existing execution deadline already includes routing and time behind earlier
HTTP pipelined requests. Queue waiting cannot extend that deadline. Promotion
checks both clocks, so a delayed timer cannot start expired work. Queue timer
callbacks from before promotion cannot cancel the promoted request.

Expiry does not depend on timers alone. Each queued request has a timer for the
smaller of its queue wait and remaining deadline, and in addition every completed
request, and every submission that would otherwise be refused for a full queue,
fails every waiting request whose queue wait or execution deadline has run out, including
requests of routes at their own active limit that cannot promote anything and requests whose
own deadline is shorter than the wait of the requests ahead of them. Waiting requests are
kept ordered by the moment they expire, so this check removes expired requests in order
of expiry and stops at the first live one: its cost is a logarithmic removal per expired
request, never a scan of the queue, and it holds the dispatcher lock for that long only.
A late timer therefore delays an expiry only until the next completion or refused
submission, and an expired request never holds a queue slot that a new request needs.

- Full active capacity with no available queue slot: **503**, then connection close.
- Queue wait expires: **503**, then connection close; the handler is never invoked.
- Request execution deadline expires while waiting: **504**, then connection close.
- Dispatcher submission fails during promotion: a safe failure response and cleanup.

Application-thrown rejection or timeout exceptions remain 500 application failures.
The per-connection pipeline buffer is separate: only its front request is eligible
for admission. Pipeline order and the eight-request connection bound still apply.
The connection limit (128 by default, `ListenerOptions.maxConnections`; see
[listener options](http.md#listener-options)) can cap useful HTTP admission capacity below configured
values: a connection over the cap is closed without a response, never answered 503, so set it at or
above active plus queued capacity plus headroom for idle keep-alive connections. The network inactivity timeout does not close a connection whose request
is waiting; the queue wait and execution deadline bound it instead.

## Ownership and shutdown

Cancelling queued work removes it immediately, cancels its timer and returns its
queue slot. Cancelling active work interrupts the virtual thread but retains
active capacity until the request action exits. Code that ignores interruption
cannot cause a queued request to be promoted early. Cancelled or timed-out
requests have one terminal outcome; late handler results are discarded.

Closing a listener stops admission at once: new requests are rejected and waiting
requests are answered 503 and their connections closed; the handler is never
invoked. Shutdown never promotes waiting work. Active requests, including streams,
get the listener's drain grace period (see [HTTP listeners](http.md)), after which
they are cancelled. Listener termination still waits for active code to exit, and
the stream slot is returned when the body does. See
[execution and deadlines](execution.md) for interruption.

## Observation

`server.admission()` returns a consistent immutable `AdmissionSnapshot`:

| Field | Meaning |
| --- | --- |
| `active` | Reserved or running tasks, including cancelled code still executing |
| `queued` | Requests waiting for execution capacity |
| `accepted` | Total successful submissions, including requests initially queued |
| `rejected` | Total immediate rejections due to request capacity, dispatcher closure, executor/scheduler refusal at submission, or a full stream lane. A conflicting endpoint policy is a caller error and is not counted |
| `queueTimeouts` | Queue-wait expirations, excluding execution deadline expiry |

The metrics reason tag `stream_capacity` identifies a stream refused because its
aggregate or route bound was full. That refusal is counted as a 503 request, while
`rejected` counts it separately from refusals at submission. Counters are scoped to
one listener and remain readable after shutdown. Snapshots
are a polling surface for diagnostics and future metrics adapters. No observer
callbacks run under admission locks or on transport threads. Per-route metrics,
adaptive limits, CPU execution classes and distributed budgets remain future work.

Counters, queue depth and queue wait are also available as metrics; see
[observability](observability.md).
