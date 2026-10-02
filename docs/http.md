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
and queued pipelined requests on it are dropped unanswered.
After a fixed five-second grace period, remaining connections close and their
handlers are interrupted; then execution and I/O threads stop. Await
`server.termination()` to join resource shutdown. Handlers must cooperate with
interruption; termination cannot complete while a handler refuses to stop.
Direct in-memory `app.handle` calls remain the caller's responsibility.

A failed bind releases its resources before reporting `IOException`. The
application remains running, so binding another address is safe. Missing or
multiple transport providers fail before startup. Closed applications cannot bind.

## Wire behavior

The listener accepts HTTP/1.1 origin-form requests with exactly one valid Host,
and HTTP/1.0 requests, whose Host is optional but validated when present.
Responses always use HTTP/1.1. An HTTP/1.0 connection closes after each response
unless the request sends `Connection: keep-alive`, which the response echoes.
Paths that `Request` rejects (empty or dot segments, backslashes, malformed or
encoded separators; see [routing rules](routing.md)) receive 400. Accepted raw paths
retain their encoding; query strings are excluded from routing and are not yet
exposed through the request API. Request headers are available to handlers, and
request bodies are read up to the application's limit; see
[request bodies](bodies.md). Responses support UTF-8 strings, byte arrays, empty
bodies and values encoded by an installed codec. Unencodable body objects and
handler exceptions other than `AxiomException` produce a generic 500 and close the
connection; exception details are not sent to clients. The in-memory API still
propagates those exceptions.

The transport controls Content-Length, Transfer-Encoding, connection headers and
`Date`, which every response carries as an IMF-fixdate with one-second precision.
Hop-by-hop headers, including names nominated by Connection, are removed. HEAD
uses an explicit HEAD route or falls back to GET, and sends no body or Content-Length because the current
application API does not retain the representation length. Statuses 204 and 304
omit Content-Length; 205 uses zero. Keep-alive and pipelining are supported, with
one active handler per connection and responses in request order.

Every error response, whether produced by the listener or the application, is an
`application/problem+json` body holding only status, code and request ID; see
[errors](errors.md) for the full status table. CONNECT, upgrades and unknown
transfer codings return 501; an overlong request line 414; an oversized header
section 431; an
`Expect` other than `100-continue` 417; other HTTP versions 505. These close the
connection. Malformed requests return 400 when no earlier response is outstanding;
otherwise the connection closes to avoid sending an error ahead of an earlier
pipelined response.

## Resource limits

Each listener owns one acceptor thread, one I/O thread per available processor
(at least two), a deadline scheduler and a dispatcher that defaults to 36 active
virtual-thread tasks with no waiting queue. Aggregate and route limits are
configurable through `app.admissionPolicy(...)`; see
[admission and bounded queues](admission.md). User handlers never execute on I/O
threads. Full execution capacity with no free queue slot, or an expired queue
wait, produces 503 and closes that connection. So does any other failure to start
a request, including a pipelined request reached after the application has
closed. Connections are limited to 128; additional connections close immediately
without consuming a slot. The listening socket requests a 1024-entry accept
backlog and sets SO_REUSEADDR so a restart can rebind while old connections
linger in TIME_WAIT. Connections use TCP_NODELAY and a 32/128 KiB write-buffer
water mark. A connection holds at most eight outstanding requests. Pipeline
overflow closes the connection.

Reads continue while a handler runs, so a client disconnect (including a
half-close after sending the request) cancels and interrupts the active handler
and drops queued requests. Pipelined requests are buffered only up to the
eight-request bound above; the next queued handler starts after the previous
write completes. The decoder limits request lines to 4 KiB (414 beyond) and headers to 8 KiB (431
beyond). Request bodies are limited by `app.maxRequestBody` (1 MiB by default, at
most 64 MiB): an oversized Content-Length gets 413 before the body is read, and a
chunked body gets 413 as soon as its running total exceeds the limit. Bodies of
requests waiting behind the active one plus the body being received are bounded at
twice the limit per connection; beyond that the connection closes. Received
buffers are released on completion, error, disconnect and shutdown.
Response bodies are limited to 1 MiB after encoding and response
headers to 8 KiB; larger responses produce 500. Application allocations before
returning a response are outside these limits. Connections close after 30 seconds
without network read/write activity, including idle keep-alive connections and a
response write stalled by a client that stopped reading. A request waiting for
admission or a running handler is not interrupted by inactivity; its queue wait
and execution deadline bound it instead.
A request head must arrive within ten seconds of its first byte; otherwise the
listener answers 408 Request Timeout and closes (or just closes when an earlier
pipelined response is outstanding). Trickling bytes does not extend the bound.
A request body must arrive before the request deadline, which starts when the head
is parsed; otherwise the listener answers 408 and closes. A connection receiving
body bytes is not idle, but one that stops sending mid-body for 30 seconds is.

The default execution deadline is ten seconds, configurable before startup through
`app.requestTimeout(Duration)`. Responses include a generated `X-Request-ID`.
See [execution and deadlines](execution.md) for timing, cancellation and capacity ownership.

Other limits remain fixed. TLS, HTTP/2, streaming request bodies, query parameter
APIs, observability integrations and a configurable shutdown grace period remain
future work.
