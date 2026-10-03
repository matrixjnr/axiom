# HTTP listeners

Add `axiom-http` and call `app.listen(8080)`. This starts the application, freezes
routes, and binds **127.0.0.1**. Use `listen(new InetSocketAddress(host, port))` to
choose an interface, or port zero for an allocated port. `Server.localAddress()`
reports the actual address. Netty is an implementation dependency; application
and transport-provider contracts expose only JDK and Axiom types.

## Ownership and shutdown

An application owns every listener it creates. Multiple listeners can share its
routes. Closing one listener leaves the application and other listeners running.
Closing the application permanently rejects requests and closes all its listeners.
Both close operations are idempotent and nonblocking, including from a handler.

Listener shutdown is graceful. It stops accepting connections at once and closes
idle keep-alive connections and connections still receiving a request. It also
stops admission: requests waiting for execution capacity are answered 503 and
their connections close without invoking the handler. A connection with a
running handler keeps it running; its response is sent with `Connection: close`
and queued pipelined requests on it are dropped unanswered. This holds for every
response sent after `close()` is called, so it is already in force when a waiting
request receives its 503. The connection then
lingers for at most 500 milliseconds by default (see [wire behavior](#wire-behavior)) before it
closes; connections already lingering when the listener closes stop within the same
bound, so they do not hold up shutdown.
After the shutdown grace period (five seconds by default, see
[listener options](#listener-options)), remaining connections close and their
handlers are interrupted; then execution and I/O threads stop. Await
`server.termination()` to join resource shutdown. Handlers must cooperate with
interruption; termination cannot complete while a handler refuses to stop.
Direct in-memory `app.handle` calls remain the caller's responsibility.

### Shutdown on SIGTERM

Nothing closes an application by itself: Axiom never installs a JVM shutdown hook unless asked, so
a library or test that embeds it is not surprised. **Closing on `SIGTERM` is opt-in.** A service
run by a container runtime or Kubernetes, which stops it with `SIGTERM`, calls
`app.closeOnJvmShutdown()` once at startup:

```java
var app = Axiom.create().closeOnJvmShutdown();
app.get("/", ctx -> "ok");
var server = app.listen(new InetSocketAddress("0.0.0.0", 8080));
server.termination().toCompletableFuture().join(); // keeps main alive until the drain is done
```

When the JVM begins to shut down (`SIGTERM`, `SIGINT`, `System.exit` or the end of the last
non-daemon thread) the hook closes the application and waits for every listener to terminate. The
drain is the graceful shutdown described above, within each listener's `shutdownGrace`: running
requests finish and are answered with `Connection: close`, waiting requests get 503, and what is
left is interrupted after the grace period. The wait is bounded by the longest `shutdownGrace` of
the application's listeners plus five seconds, so a handler that ignores interruption cannot keep the
JVM from exiting. Closing makes the application not `RUNNING`, so a `Health` readiness probe
reports DOWN from then on; if a load balancer needs time to notice, call `health.beginDrain()` and
wait before the JVM exits (see [observability](observability.md#health-and-readiness)). The grace
period must fit inside the platform's own kill timeout (Kubernetes
`terminationGracePeriodSeconds`, 30 seconds by default). Calling it twice registers one hook,
an explicit `app.close()` unregisters it, and it throws `IllegalStateException` on a closed
application or when the JVM is already shutting down. Hooks of different libraries run concurrently
and in no defined order.

Open [streams](streaming.md#shutdown) keep the grace period too, and are told that shutdown began
(`BodyWriter.shutdownRequested()`, `onShutdown`) so that a body can end normally with a final chunk;
a body still running when the period ends is cut with `SHUTDOWN`, and its connection closes without
a final chunk, so the client sees the body cut off rather than completed.

A failed bind releases its resources before reporting `IOException`. The
application remains running, so binding another address is safe. Missing or
multiple transport providers fail before startup. Closed applications cannot bind.

## Wire behavior

The listener accepts HTTP/1.1 origin-form requests with exactly one valid Host,
and HTTP/1.0 requests, whose Host is optional but validated when present. The
asterisk-form target is accepted only as `OPTIONS *` (see
[OPTIONS *](routing.md#options-)); `*` with any other method and absolute-form
targets such as `http://host/path` receive 400.
Responses always use HTTP/1.1. An HTTP/1.0 connection closes after each response
unless the request sends `Connection: keep-alive`, which the response echoes.
A method that is not an RFC 9110 token receives 400; methods are case-sensitive, and
method-override headers such as `X-HTTP-Method-Override` are ignored. The
[method table](routing.md#methods) lists the answer for every method, including
OPTIONS, TRACE, CONNECT and extension methods. Paths that `Request` rejects (empty or dot segments, backslashes, malformed or
encoded separators; see [routing rules](routing.md)) receive 400. Accepted raw paths
retain their encoding. Query strings are excluded from routing, retained on the
request and validated as described in [routing rules](routing.md#query-parameters);
a malformed or oversized query also receives 400. Request headers are available to handlers, the
connection's peer is `ctx.request().remoteAddress()` (forwarding headers are never applied
to it; see [trusted proxies](security.md#client-address-and-trusted-proxies)), and
request bodies are read up to the application's limit; see
[request bodies](bodies.md). Responses support UTF-8 strings, byte arrays, empty
bodies, values encoded by an installed codec and [streams](streaming.md). Unencodable body objects and
handler exceptions other than `AxiomException` produce a generic 500 and close the
connection; exception details are not sent to clients. The in-memory API still
propagates those exceptions.

The transport controls Content-Length, Transfer-Encoding, connection headers and
`Date`, which every response carries as an IMF-fixdate with one-second precision.
A [streamed response](streaming.md) is sent with `Transfer-Encoding: chunked` on HTTP/1.1,
and unframed until the connection closes on HTTP/1.0; HEAD gets its head only.
Hop-by-hop headers, including names nominated by Connection, are removed. HEAD
uses an explicit HEAD route or falls back to GET and sends no body bytes. OPTIONS
for a routed path without an OPTIONS route is answered 204 with `Allow` and no
handler runs (see [automatic OPTIONS](routing.md#automatic-options)). TRACE is never
echoed: it is answered 405 or 404 (see [TRACE](routing.md#trace)). A successful
HEAD response (2xx other than 204 and 205) carries the Content-Length the GET
representation would have, its encoded body length, replacing any value the
application set; a representation the transport could not send is a 500 for HEAD as
for GET. Error responses to HEAD keep their own framing: no body and no Content-Length,
including errors the listener produces before routing (400, 413, 417, 501 and the
others below).
Statuses 204 and 304 omit Content-Length; 205 uses zero, because RFC 9112 section 6.3 does not treat it as
bodiless and the client needs explicit framing to read the next response. The
interim `100 Continue` carries no header fields. Keep-alive and pipelining are supported, with
one active handler per connection and responses in request order.

Every error response, whether produced by the listener or the application, is an
`application/problem+json` body holding only status, code and request ID; see
[errors](errors.md) for the full status table. CONNECT (see
[CONNECT](routing.md#connect)), upgrades and unsupported transfer codings return 501; an overlong request line 414; an oversized header
section 431; an
`Expect` other than `100-continue` 417; other HTTP versions 505; malformed requests
400. These close the connection.

`Transfer-Encoding` follows a fixed contract (details in [request bodies](bodies.md#limits)).
Only `chunked` is supported. One field line whose last coding is `chunked` but that applies
another coding first (`gzip, chunked`) is answered **501**: its framing is unambiguous and
the coding is merely unsupported, which is the case RFC 9112 section 6.1 says a server
should answer with 501. A list whose last coding is not `chunked` is **400**, as RFC 9112
section 6.3 requires, because the body length cannot be determined. More than one
Transfer-Encoding field line is **400** even when the combined list would be acceptable:
the RFC allows combining the lines, but intermediaries disagree about how, which is a
classic request-smuggling vector, so the listener refuses rather than picks a reading.
Both answers close the connection and nothing sent after the rejected head is parsed or
executed, whether the request arrives alone or pipelined behind others.

Closing right after a response could destroy it: if the client is still sending
(the rest of a rejected body, or further pipelined requests), unread input makes the
operating system reset the connection, which can discard response bytes not yet
delivered, and a client may report the reset instead of the response. So whenever
the listener ends a connection after a response, for any reason (a listener error,
`Connection: close` from the client or the handler, HTTP/1.0 without keep-alive, or
listener shutdown), it shuts down its output, so the client reads the whole response
and end of stream, and keeps reading and discarding input without buffering it. The
connection closes when the client closes its side, once no input has arrived for 500
milliseconds, after two seconds in total, after 16 MiB of discarded input, or on
inactivity, whichever comes first. Each arriving byte restarts the 500-millisecond
quiet period but never extends the two-second total.

Lingering exists for a client that is still sending, so a client that has gone quiet
is taken to have finished: one that reads the response and keeps its socket open is
closed after the quiet period, and closing a connection with no unread input sends no
reset. The quiet period is longer than a typical TCP retransmission timeout, so a
single lost segment does not end it. The trade-off is a client that is still sending
but pauses for longer (a very lossy link, or a client that stalls between writes): its
next bytes arrive at a closed connection, the operating system answers with a reset,
and response bytes the client has not read yet may be lost. The quiet period is the
`lingerQuietTimeout` listener option (see [listener options](#listener-options)): raise it
for clients known to upload slowly or over lossy links, up to the `lingerTimeout` total, which
is the hard bound either way. The cost of a longer period is that a client which reads the
response and then idles keeps its lingering connection (and its socket) for that long, which
the lingering pool and its bound limit (see [resource limits](#resource-limits)). Once the listener is
closing, lingering lasts at most 500 milliseconds in total by default, so lingering connections
barely delay shutdown. A lingering connection holds no request data and no longer
counts against the connection limit (see [resource limits](#resource-limits)).
Connections closed without a response (an idle
connection at shutdown, a connection still receiving a request at shutdown,
inactivity, a transport failure or a disconnect) close at once.

### Errors on pipelined requests

A listener error never overtakes an earlier response and never aborts earlier work.
When a pipelined request is rejected (any of the errors above, 408 for a head or body
that arrives too slowly, 413, or 503 for exceeding the pipeline bounds below) while
earlier requests on the connection are running or queued:

1. The rejected request and everything after it are never executed. The listener
   stops parsing input: bytes after an error are read and discarded unparsed, since
   after a framing error (a malformed chunk, conflicting Content-Length and
   Transfer-Encoding, an unparseable head) their boundaries cannot be trusted.
   Requests already parsed from the same read are dropped too.
2. The running request and every request received completely before the error run
   normally, in order, and each gets its usual response. The running handler is not
   interrupted.
3. The error response follows them, with `Connection: close`, and the connection
   closes.

Responses arrive in request order, so the client can match each one to its request;
the rejected request and those after it, which receive no response of their own,
were not executed. Input keeps being read and discarded, never buffered, so a client
disconnect still cancels and interrupts the running handler. A client that sends
more than 16 MiB after the error is treated as abusive: the connection closes at
once, the running handler is cancelled and interrupted, and no further responses are
sent. If an earlier response itself closes the connection (a listener 5xx, a
handler's `Connection: close`, or listener shutdown, which sends the running response
with `Connection: close`), that response is the last one and the pending error is
not sent. The 30-second inactivity timeout does not interrupt the running handler,
but still closes the connection if a response write stalls, and each response must
be written within the response bound (see [resource limits](#resource-limits)).

## Resource limits

Each listener owns one acceptor thread, one I/O thread per available processor
(at least two), a deadline scheduler and a dispatcher that defaults to 36 active
virtual-thread tasks with no waiting queue. Aggregate and route limits are
configurable through `app.admissionPolicy(...)`; see
[admission and bounded queues](admission.md). User handlers never execute on I/O
threads. Full execution capacity with no free queue slot, or an expired queue
wait, produces 503 and closes that connection. So does any other failure to start
a request, including a pipelined request reached after the application has
closed. Connections are limited to 128 by default; additional connections close immediately
without a response and without consuming a slot. A connection that only lingers after its last response
(see [wire behavior](#wire-behavior)) gives its slot back, because it holds no request
data and its remaining life is bounded by the linger caps. At most 32 connections per
listener linger this way by default; beyond that a lingering connection keeps its slot until it
closes, so a listener never has more than 160 open sockets by default (the connection cap plus
the lingering pool). The gauge `axiom.http.connections` (tag `state` = `open` or `lingering`)
reports both pools, see [observability](observability.md). The listening socket requests a 1024-entry accept
backlog and sets SO_REUSEADDR so a restart can rebind while old connections
linger in TIME_WAIT. Connections use TCP_NODELAY and a 32/128 KiB write-buffer
water mark. A connection holds at most eight outstanding requests, including the
running one. A further request is refused when its head arrives: it is never
executed, and after the eight earlier responses it is answered 503 and the
connection closes (see [errors on pipelined requests](#errors-on-pipelined-requests)).

Reads continue while a handler runs, so a client disconnect (including a
half-close after sending the request) cancels and interrupts the active handler
and drops queued requests. Pipelined requests are buffered only up to the
eight-request bound above; the next queued handler starts after the previous
write completes. The decoder limits request lines to 4 KiB (414 beyond) and headers to 8 KiB (431
beyond). Request bodies are limited by `app.maxRequestBody` (1 MiB by default, at
most 64 MiB): an oversized Content-Length gets 413 before the body is read, and a
chunked body gets 413 as soon as its running total exceeds the limit. Per connection the listener holds at most the running request's body (`L`) plus
the bodies of waiting pipelined requests and the body being received (`2 × L`
together; a request that would exceed that share is answered 503 after the earlier
responses and the connection closes), so `3 × L` with
`L = maxRequestBody`. Across all of a listener's connections, request bodies are further
limited by `maxInFlightBodyBytes` (64 MiB by default): a request whose body does not fit in what
remains is answered 503 and its connection closes, instead of the listener holding up to
`128 × 3 × L`; see [request bodies](bodies.md#memory-per-connection). Body bytes are copied out of
network buffers as they arrive, so no Netty buffer is retained across reads.
Response bodies are limited to 1 MiB of encoded bytes (a `String` counts its UTF-8
bytes) and response headers to 8 KiB, counted as name, value and four characters
per field; header values must be Latin-1. Other responses produce 500. The listener
and `TestClient` apply these rules from one shared definition. Application allocations before
returning a response are outside these limits. Connections close after 30 seconds
without network read/write activity, including idle keep-alive connections and a
response write stalled by a client that stopped reading. A request waiting for
admission or a running handler is not interrupted by inactivity; its queue wait
and execution deadline bound it instead. Writing one response must also finish
within 30 seconds of handing it to the socket: a client that keeps reading a few
bytes at a time never looks inactive, so without this bound it could hold a
connection indefinitely. When the bound passes, the connection closes and the
client receives a truncated response; at the 1 MiB response limit this needs a
client reading slower than about 35 KB/s. The same bound applies to the write of the interim
`100 Continue`, tracked separately from the final response because the client may send its body
while that write is pending: a client that never reads loses the connection after
`responseTimeout` instead of after the inactivity timeout. A [stream](streaming.md) is not a response of at
most 1 MiB: its body is limited by its own byte cap (64 MiB by default), by the request
deadline, and by the same 30 seconds for a client that takes no data, applied to each wait for
the channel to become writable rather than to the whole stream. The inactivity timeout does not
interrupt a running stream either; a handler that is writing nothing is bounded by the request
deadline.
A request head must arrive within ten seconds of its first byte; otherwise the
listener answers 408 Request Timeout and closes (after any earlier pipelined
responses). Trickling bytes does not extend the bound. The
bound also starts when the head's first bytes arrive together with the end of the
previous request or its body; empty lines before a request line, which are ignored,
do not start it.
A request body must arrive before the request deadline, which starts when the head
is parsed; otherwise the listener answers 408 and closes. A connection receiving
body bytes is not idle, but one that stops sending mid-body for 30 seconds is.

The default execution deadline is ten seconds, configurable before startup through
`app.requestTimeout(Duration)`. Responses include a generated `X-Request-ID`.
See [execution and deadlines](execution.md) for timing, cancellation and capacity ownership.

A listener serves HTTPS when its options carry [TLS](tls.md). HTTP/2, streaming request bodies,
WebSocket and observability integrations remain future work.

## Listener options

Transport limits and timeouts are set per listener with an immutable `ListenerOptions`,
passed to `app.listen(address, options)`. `app.listen(address)` and `app.listen(port)` use
`ListenerOptions.defaults()`, which keeps the values described in this document. Each
builder method validates its value when called and throws `IllegalArgumentException` naming
the setting and its range; `build()` cannot fail. The options apply to that listener only, so
listeners of one application may differ. Application-level limits (request body size,
execution deadline, admission) stay on the application.

```java
var options = ListenerOptions.builder()
        .shutdownGrace(Duration.ofSeconds(30))
        .maxConnections(512)
        .build();
var server = app.listen(new InetSocketAddress("0.0.0.0", 8080), options);
```

| Setting | Default | Valid range | Governs |
| --- | --- | --- | --- |
| `shutdownGrace` | 5 s | 0 to 1 day | Time `close()` lets running exchanges finish before connections close and handlers are interrupted; zero interrupts at once |
| `idleTimeout` | 30 s | 1 ms to 1 day | Network inactivity after which a connection closes |
| `headTimeout` | 10 s | 1 ms to 1 day | Time from a request's first byte until its head is complete (408) |
| `responseTimeout` | 30 s | 1 ms to 1 day | Time to write one response to the socket, including an interim `100 Continue` |
| `lingerTimeout` | 2 s | 1 ms to 1 day | Total linger after the last response |
| `lingerQuietTimeout` | 500 ms | 1 ms to 1 day | Silence that ends lingering |
| `shutdownLingerTimeout` | 500 ms | 1 ms to 1 day | Total linger once the listener is closing (the smaller of this and `lingerTimeout` applies) |
| `handshakeTimeout` | 10 s | 1 ms to 1 day | Time a new connection may take to finish its TLS handshake; applies only with `tls` |
| `tls` | none (plain HTTP) | a `TlsOptions` | Serves HTTPS with the given certificate, protocols and client-authentication settings; validated at startup, see [TLS](tls.md) |
| `maxDiscardedInput` | 16 MiB | 0 to 1 GiB | Bytes discarded unread after an error or last response before the connection closes |
| `maxConnections` | 128 | 1 to 1,000,000 | Open connections per listener |
| `maxLingeringConnections` | 32 | 0 to 1,000,000 | Lingering connections that stop counting against `maxConnections`; zero keeps them on their regular slots |
| `maxPipelinedRequests` | 8 | 1 to 1024 | Outstanding requests per connection, including the running one |
| `maxInFlightBodyBytes` | 64 MiB | 1 byte to 1 TiB | Request body bytes the listener holds at once, across connections (503 beyond); must be at least the application's `maxRequestBody` |
| `maxRequestLine` | 4096 | 256 to 65,536 | Longest request line in bytes (414 beyond) |
| `maxHeaderBytes` | 8192 | 256 to 1 MiB | Largest header section in bytes (431 beyond) |
| `ioThreads` | processors, at least 2 | 1 to 1024 | I/O threads of the listener; handlers never run on them |

Choosing values:

- **File descriptors and load balancers.** A listener can hold `maxConnections +
  maxLingeringConnections` sockets (160 by default) plus its listening socket, its event-loop
  selectors and wakeup pipes, and the files and outbound connections the application itself opens.
  Raise the process descriptor limit (`ulimit -n`, container or systemd `LimitNOFILE`) above the
  sum over all listeners with headroom, and size any connection limit on a load balancer or
  proxy in front of the listener to the same sum, not to `maxConnections`. The lingering pool
  is only used briefly, but a burst of connections that all end at once can fill it.
- Total open sockets are at most `maxConnections + maxLingeringConnections`. The memory held for
  request bodies is capped by `maxInFlightBodyBytes` however many connections are open (see
  [request bodies](bodies.md#memory-per-connection)); lower it to bound heap use under many slow
  uploaders, and raise it together with `maxRequestBody` when large bodies must upload concurrently.
- The connection cap and admission are independent limits. A connection over the cap is closed
  at once without a response, so the client sees a closed connection, never a 503; admission 503s
  are only produced for requests on accepted connections. A cap below the application's admission
  capacity (active plus queued requests) leaves part of that capacity unreachable, because clients
  are turned away before admission could queue or answer them. Set `maxConnections` at or above
  that capacity plus headroom for idle keep-alive connections; see
  [admission and bounded queues](admission.md).
- A short `shutdownGrace` interrupts handlers sooner; a long one delays `termination()` for as long
  as a handler keeps running. Handlers must still cooperate with interruption.
- `lingerQuietTimeout` longer than `lingerTimeout` has no effect beyond the total bound, and a
  very small `lingerQuietTimeout` raises the chance that a client still sending gets a reset (see
  [wire behavior](#wire-behavior)).
- `responseTimeout` is also the stall bound of streamed responses. The byte cap of a stream is not a
  listener option: each response sets its own (`Response.DEFAULT_STREAM_LIMIT` by default); see
  [streaming](streaming.md).
- On a TLS listener, `handshakeTimeout` bounds the handshake and a connection holds its slot while it
  handshakes; see [handshake limits](tls.md#handshake-limits).
- `headTimeout` and `idleTimeout` are the main defense against slow clients; raising them or the
  connection cap widens the exposure to slowloris-style clients.

