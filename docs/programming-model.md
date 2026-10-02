# Application programming model

## Bootstrap and dependencies

`Axiom.create()` returns a new, independent `Application` in the `CONFIGURING`
state. Core discovers exactly one `ApplicationProvider` with JDK `ServiceLoader`
and the thread context class loader. A missing provider reports how to add the
runtime; multiple providers fail rather than selecting one based on classpath order.
The experimental provider SPI exists to keep the dependency from server to core.

Use `axiom-http` in the repository's examples, or `axiom-test` for the test client.
Both expose core contracts at compile time and include the server provider at
runtime. HTTP keeps Netty in its implementation dependencies; core uses only the JDK.

## Registration and matching

```java
var app = Axiom.create();
app.get("/users/me", ctx -> "current user");
app.get("/users/:id", ctx -> "user " + ctx.path("id"));
app.get("/files/*path", ctx -> ctx.path("path"));
```

`get`, `post`, `put`, `patch`, `delete`, `head`, and `options` delegate to `route`.
A `Route` is an immutable identity; `routes()` returns an immutable snapshot in
registration order. Duplicate method/template pairs fail without replacing a handler.
At startup, equally shaped templates for the same method also fail, regardless of
capture names. No partially compiled router is published on failure.

Matching is case-sensitive and preserves the raw path. Paths with empty or dot
segments, backslashes, NUL, malformed percent-escapes, or encoded dots, slashes,
backslashes or NUL are rejected with `InvalidRequestPathException` (400 over HTTP). Whole-segment `:name`
parameters capture one non-empty segment; terminal `*name` wildcards capture the
remaining path. At the first differing segment, static segments take precedence
over parameters, then wildcards. Branches that cannot match the complete path are
skipped, and so are complete matches not registered for the request method: with
`GET /users/me` and `POST /users/:id`, `POST /users/me` runs the parameter route.

Unknown paths return 404. When no matching template has the requested method, the
response is 405 with a sorted `Allow` header listing every method registered on a
matching template. HEAD uses an explicit HEAD route or else the GET route on the
same template, and omits the response body; `Allow` includes HEAD wherever GET is
registered. Automatic OPTIONS behavior is not enabled.

`ctx.route()` returns the matched template identity. `ctx.path("id")` reads a raw
capture; `ctx.pathParameters()` returns an immutable map in template order. Values
are materialized on access, without percent-decoding or normalization;
`ctx.pathDecoded("id")` decodes one capture as strict UTF-8. Captures are untrusted input. Query strings
are not part of `Request.path()`. See [routing rules](routing.md) for edge cases.

## Lifecycle and concurrency

- `start()` compiles and freezes registration, then enters `RUNNING`; repeated starts are harmless.
- `handle(Request)` requires `RUNNING` and invokes the handler on the calling thread.
- `close()` enters `CLOSED` permanently, releases registered handler references,
  and rejects new requests. It is safe to call repeatedly or before startup.

Registration, startup, and shutdown are serialized by a lifecycle lock. Startup
publishes one immutable snapshot of the router, frozen routes, and admission
policies; request acceptance, `resolve`, and `admissionPolicy(route)` read it
without taking the lock. `listen` discovers the transport and binds outside the
lock; if the application closes meanwhile, the new listener is closed and `listen`
throws `IllegalStateException`.
Handlers can run concurrently and each invocation receives a fresh context.
Shared business objects must provide their own thread safety.

Close does not wait for accepted requests. In-memory calls may complete after
`close()` returns. Owned HTTP listeners close connections and interrupt network
handlers. Await each listener's `termination()` to join shutdown. See
[HTTP ownership, execution and limits](http.md).

## Execution metadata

`ctx.execution()` exposes the request ID and monotonic remaining budget.
HTTP uses bounded virtual-thread execution with a configurable deadline;
direct calls and the test client retain synchronous caller-thread execution.
See [execution semantics](execution.md) for cancellation and timeout boundaries,
and [admission](admission.md) for aggregate and route limits with bounded queues.

## Responses and failures

A handler returns an object or throws an exception:

- `Response`: used as supplied, taking precedence over context status settings.
- `String`: status 200 by default, with `text/plain; charset=utf-8`.
- `byte[]`: status 200 by default, with `application/octet-stream`; copied defensively.
- `null`: status 204 unless a status was explicitly set on the context.
- Other objects: retained as body values without serialization or copying.

`ctx.status(201).text("created")` sets a status and returns a response snapshot.
`ctx.noContent()` returns 204. An explicit `Response.of(status, body)` can use
`withHeader(name, value)` to create a modified copy. Headers are immutable and
case-insensitive, with one value per name; repeated headers are not modeled yet.
Final statuses range from 200 through 599; 204, 205, and 304 reject non-null bodies.

Handler exceptions propagate unchanged to in-memory callers. The HTTP transport
maps exceptions and unsupported body objects to generic 500 responses.
No JSON encoding, body parsing, middleware, or error mapping is implied by this API.

## Testing without ports

```java
var app = Axiom.create();
app.get("/", ctx -> "Hello, world!");

try (var client = TestClient.start(app)) {
    assertThat(client.get("/").status()).isEqualTo(200);
    assertThat(client.get("/").body()).isEqualTo("Hello, world!");
}
```

`TestClient` owns the supplied application's lifecycle: creating it starts the
application, and closing it closes the application. Requests go through a private
dispatcher using the application's admission policies and request timeout, so
overload (503) and deadline expiry (504) are testable; `submit(request)` returns a
future for holding capacity from tests. Handler exceptions propagate rather than
becoming 500 responses. Response bodies the transport cannot send (anything other
than `String` or `byte[]`, or over the size limits) fail with `IllegalStateException`
where a listener would answer 500. Sockets, HTTP parsing and connection behavior
are not simulated.
