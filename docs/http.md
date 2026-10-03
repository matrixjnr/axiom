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
and queued pipelined requests on it are dropped unanswered. The connection then
lingers briefly (see [wire behavior](#wire-behavior)) before it closes.
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
and HTTP/1.0 requests, whose Host is optional but validated when present. The
asterisk-form target is accepted only as `OPTIONS *` (see
[OPTIONS *](routing.md#options-)); `*` with any other method and absolute-form
targets such as `http://host/path` receive 400.
Responses always use HTTP/1.1. An HTTP/1.0 connection closes after each response
unless the request sends `Connection: keep-alive`, which the response echoes.
A method that is not an RFC 9110 token receives 400; methods are case-sensitive, and
method-override headers such as `X-HTTP-Method-Override` are ignored (see
[methods](routing.md#methods)). Paths that `Request` rejects (empty or dot segments, backslashes, malformed or
encoded separators; see [routing rules](routing.md)) receive 400. Accepted raw paths
retain their encoding. Query strings are excluded from routing, retained on the
request and validated as described in [routing rules](routing.md#query-parameters);
a malformed or oversized query also receives 400. Request headers are available to handlers, and
request bodies are read up to the application's limit; see
[request bodies](bodies.md). Responses support UTF-8 strings, byte arrays, empty
bodies and values encoded by an installed codec. Unencodable body objects and
handler exceptions other than `AxiomException` produce a generic 500 and close the
connection; exception details are not sent to clients. The in-memory API still
propagates those exceptions.

The transport controls Content-Length, Transfer-Encoding, connection headers and
`Date`, which every response carries as an IMF-fixdate with one-second precision.
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
[CONNECT](routing.md#connect)), upgrades and unknown transfer codings return 501; an overlong request line 414; an oversized header
section 431; an
`Expect` other than `100-continue` 417; other HTTP versions 505; malformed requests
400. These close the connection.

Closing right after a response could destroy it: if the client is still sending
(the rest of a rejected body, or further pipelined requests), unread input makes the
operating system reset the connection, which can discard response bytes not yet
delivered, and a client may report the reset instead of the response. So whenever
the listener ends a connection after a response, for any reason (a listener error,
`Connection: close` from the client or the handler, HTTP/1.0 without keep-alive, or
listener shutdown), it shuts down its output, so the client reads the whole response
and end of stream, and keeps reading and discarding input without buffering it. The
connection closes when the client closes its side, after two seconds, after 16 MiB of
discarded input, or on inactivity, whichever comes first. The listener does not try
to guess that a client has finished sending: a client that reads the response but
keeps its socket open holds the connection for the full two seconds. A lingering
connection still counts against the connection limit and can delay listener
shutdown by up to those two seconds. Connections closed without a response (an idle
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
but still closes the connection if a response write stalls.

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
`L = maxRequestBody`. These bounds are per connection and per listener, not
global: 128 connections allow about `384 × L` per listener; see
[request bodies](bodies.md#memory-per-connection). Body bytes are copied out of
network buffers as they arrive, so no Netty buffer is retained across reads.
Response bodies are limited to 1 MiB of encoded bytes (a `String` counts its UTF-8
bytes) and response headers to 8 KiB, counted as name, value and four characters
per field; header values must be Latin-1. Other responses produce 500. The listener
and `TestClient` apply these rules from one shared definition. Application allocations before
returning a response are outside these limits. Connections close after 30 seconds
without network read/write activity, including idle keep-alive connections and a
response write stalled by a client that stopped reading. A request waiting for
admission or a running handler is not interrupted by inactivity; its queue wait
and execution deadline bound it instead.
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

Other limits remain fixed. TLS, HTTP/2, streaming request bodies, observability integrations and a configurable shutdown grace period remain
future work.
