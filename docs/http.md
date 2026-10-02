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
idle keep-alive connections and connections still receiving a request. A
connection with a running handler keeps it running; its response is sent with
`Connection: close` and queued pipelined requests on it are dropped unanswered.
After a fixed five-second grace period, remaining connections close and their
handlers are interrupted; then execution and I/O threads stop. Await
`server.termination()` to join resource shutdown. Handlers must cooperate with
interruption; termination cannot complete while a handler refuses to stop.
Direct in-memory `app.handle` calls remain the caller's responsibility.

A failed bind releases its resources before reporting `IOException`. The
application remains running, so binding another address is safe. Missing or
multiple transport providers fail before startup. Closed applications cannot bind.

## Wire behavior

The listener accepts HTTP/1.1 origin-form requests with exactly one valid Host.
Raw paths retain encoding and repeated slashes; query strings are excluded from
routing and are not yet exposed through the request API. Responses support UTF-8
strings, byte arrays, and empty bodies. Unsupported body objects and handler
exceptions produce a generic 500 and close the connection; exception details are
not sent to clients. The in-memory API still propagates exceptions.

The transport controls Content-Length, Transfer-Encoding and connection headers.
Hop-by-hop headers, including names nominated by Connection, are removed. HEAD
requires an explicit route and sends no body or Content-Length because the current
application API does not retain the representation length. Statuses 204 and 304
omit Content-Length; 205 uses zero. Keep-alive and pipelining are supported, with
one active handler per connection and responses in request order.

Request bodies, transfer-coded requests, CONNECT and upgrades return 501 and close.
Expect requests return 417; older HTTP versions return 505. Malformed requests
return 400 when no earlier response is outstanding; otherwise the connection
closes to avoid sending an error ahead of an earlier pipelined response.

## Resource limits

Each listener owns one acceptor thread, one I/O thread per available processor
(at least two), a deadline scheduler and a dispatcher that defaults to 36 active
virtual-thread tasks with no waiting queue.
Aggregate and route limits are configurable through `app.admissionPolicy(...)`;
see [admission and bounded queues](admission.md).
User handlers never execute on I/O threads. Full execution capacity produces 503
and closes that connection. So does any failure to start a request, including a
pipelined request reached after the application has closed. Connections are
limited to 128; additional connections close immediately without consuming a
slot. The listening socket requests a 1024-entry accept backlog and sets
SO_REUSEADDR so a restart can rebind while old connections linger in TIME_WAIT.
Connections use TCP_NODELAY and a 32/128 KiB write-buffer water mark. A connection holds at
most eight outstanding requests. Pipeline overflow closes the connection.

Reads continue while a handler runs, so a client disconnect (including a
half-close after sending the request) cancels and interrupts the active handler
and drops queued requests. Pipelined requests are buffered only up to the
eight-request bound above; the next queued handler starts after the previous
write completes. The decoder limits request lines to 4 KiB and
headers to 8 KiB. Response bodies are limited to 1 MiB after encoding and response
headers to 8 KiB; larger responses produce 500. Application allocations before
returning a response are outside these limits. Idle keep-alive connections close
after 30 seconds without network read/write activity. A running handler is not
interrupted by inactivity; its execution deadline bounds it instead.
A request head must arrive within ten seconds of its first byte; otherwise the
listener answers 408 Request Timeout and closes (or just closes when an earlier
pipelined response is outstanding). Trickling bytes does not extend the bound.

The default execution deadline is ten seconds, configurable before startup through
`app.requestTimeout(Duration)`. Responses include a generated `X-Request-ID`.
See [execution and deadlines](execution.md) for timing, cancellation and capacity ownership.

Other limits remain fixed. TLS, HTTP/2, JSON codecs, request body/header/query APIs,
observability integrations and a configurable shutdown grace period remain future
work.
