# Application programming model

## Bootstrap and dependencies

`Axiom.create()` returns a new, independent `Application` in the `CONFIGURING`
state. Core discovers exactly one `ApplicationProvider` with JDK `ServiceLoader`
and the thread context class loader. A missing provider reports how to add the
runtime; multiple providers fail rather than selecting one based on classpath order.
The experimental provider SPI exists to keep the dependency from server to core.

Use `axiom-http` in the repository's examples, or `axiom-test` for the test client.
Both expose core contracts at compile time and include the server provider at
runtime. Add `axiom-json` with `runtimeOnly` for the JSON codec. HTTP keeps Netty
and JSON keeps Jackson in their implementation dependencies; core uses only the JDK.

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
Equally shaped templates for the same method also fail at registration, regardless
of capture names. No partially compiled router is published on failure.

Routes can share a path prefix and middleware in a group, and any scope can add
middleware that runs around its handlers:

```java
app.use((ctx, next) -> next.run().withHeader("X-Content-Type-Options", "nosniff"));
app.group("/api/v1", api -> {
    api.use(requestLog);                          // every route of the group
    api.get("/notes/:id", getNote);               // GET /api/v1/notes/:id
    api.post("/notes", createNote, requireAuth);  // route-level middleware
});
```

Middleware run global first, then group (outer to inner), then route, then the
handler; `start()` composes each chain once. See [middleware](middleware.md).

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
same template, and omits the response body (a successful one keeps its length in
`Content-Length`); `Allow` includes HEAD wherever GET is
registered. Automatic OPTIONS behavior is not enabled.

`ctx.route()` returns the matched template identity. `ctx.path("id")` reads a raw
capture; `ctx.pathParameters()` returns an immutable map in template order. Values
are extracted when the route matches, without percent-decoding or normalization;
`ctx.pathDecoded("id")` decodes one capture as strict UTF-8 and answers 400
(`invalid_path_encoding`) for one it cannot decode safely. Captures are untrusted input.

Query strings are not part of `Request.path()` and do not affect matching.
`ctx.query("q")` returns the first decoded value as an `Optional`, like
`ctx.header(name)`, and `ctx.queryAll("tag")` every value in request order:

```java
app.get("/search", ctx -> {
    var term = ctx.query("q").orElse("");
    var tags = ctx.queryAll("tag");   // ?tag=a&tag=b -> [a, b]
    return search(term, tags);
});
```

Names and values are percent-decoded once as strict UTF-8 with `+` as a space.
The raw query is `ctx.request().query()`. Malformed or oversized queries are
rejected before routing, so lookups never fail; values are still untrusted input.
There are no typed conversions, matching the path capture accessors. See
[routing rules](routing.md) for edge cases and limits.

## Lifecycle and concurrency

- `start()` discovers body codecs, compiles and freezes registration, then enters
  `RUNNING`; repeated starts are harmless. Two codecs for one media type fail startup.
- `handle(Request)` requires `RUNNING` and invokes the handler on the calling thread.
- `close()` enters `CLOSED` permanently, releases registered handler references,
  and rejects new requests. It is safe to call repeatedly or before startup.

Registration, startup, and shutdown are serialized by a lifecycle lock. Startup
publishes one immutable snapshot of the router, frozen routes, admission
policies, codecs and the body limit; request acceptance, `resolve`, and `admissionPolicy(route)` read it
without taking the lock. `listen` discovers the transport and binds outside the
lock; if the application closes meanwhile, the new listener is closed and `listen`
throws `IllegalStateException`.
Handlers and middleware can run concurrently and each invocation receives a fresh
context, shared by the request's middleware and handler. One middleware instance
serves every request and must be thread-safe. A
context is thread-confined: use it only on the handler's thread and only until the
handler returns; pass `ctx.execution()`, `ctx.request()`, or extracted values to
other tasks instead.
Shared business objects must provide their own thread safety.

Close does not wait for accepted requests. In-memory calls may complete after
`close()` returns. Owned HTTP listeners close connections and interrupt network
handlers. Await each listener's `termination()` to join shutdown. See
[HTTP ownership, execution and limits](http.md).

## Execution metadata

`ctx.execution()` exposes the request ID and monotonic remaining budget.
HTTP and the test client use bounded virtual-thread execution with a configurable
deadline; direct `handle` calls run synchronously on the caller's thread.
See [execution semantics](execution.md) for cancellation and timeout boundaries,
and [admission](admission.md) for aggregate and route limits with bounded queues.

## Responses and failures

A handler returns an object or throws an exception:

- `Response`: used as supplied, taking precedence over context status settings.
- `String`: status 200 by default, with `text/plain; charset=utf-8`.
- `byte[]`: status 200 by default, with `application/octet-stream`; copied defensively.
- `null`: status 204 unless a status was explicitly set on the context.
- Other objects: retained without copying. `ctx.json(value)` marks a value for the
  JSON codec, which encodes it when the response is prepared; values without a codec
  for their Content-Type reach in-memory callers unchanged and the HTTP transport
  answers them with 500.

`ctx.status(201).text("created")` sets a status and returns a response snapshot.
`ctx.status(201).json(item).withLocation("/items/7")` creates a JSON 201 with a
validated Location, `ctx.redirect(303, "/next")` a redirect, and `ctx.noContent()`
a 204; [errors](errors.md#success-responses) lists the patterns per status. An explicit `Response.of(status, body)` can use
`withHeader(name, value)` to create a modified copy. Headers are immutable and
case-insensitive, with one value per name; repeated headers are not modeled yet.
Final statuses range from 200 through 599; 204, 205, and 304 reject non-null bodies.
After `ctx.status(204)` (or 205, 304), returning or mapping a body fails with an
`IllegalStateException` naming the route and status instead of a generic error.
`Response` compares by value (status, case-insensitive headers, body; byte arrays by content).

Handlers read bodies with `ctx.body(Type.class)` and headers with
`ctx.header(name)`; see [request bodies and JSON](bodies.md). A handler that
throws an `AxiomException` (for example `NotFoundException` or
`ValidationException`) gets an `application/problem+json` response with only
status, code, request ID and field violations, in memory and over HTTP; see
[errors](errors.md). Other handler exceptions propagate unchanged to in-memory
callers, and the HTTP transport maps them, and unencodable body objects, to a
generic 500. Exceptions thrown by middleware are handled the same way.
`app.error(type, handler)` maps exceptions to application responses first; see
[error handlers](errors.md#error-handlers).

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
future for holding capacity from tests. `post`, `put` and `patch` take a content
type and a `String` or `byte[]` body, so tests send raw JSON without a codec
dependency. Their targets may include a query (`client.get("/search?q=a")`), split
and validated by `Request.fromTarget` like the listener's; a target the listener
would answer with 400 throws `IllegalArgumentException` instead. The body limit (413), routing errors and `AxiomException` mapping
produce the same problem responses as a listener. Other handler exceptions
propagate rather than becoming 500 responses. Response bodies the transport cannot
send (anything other than `String` or `byte[]` after encoding, or over the size
limits) fail with `IllegalStateException` where a listener would answer 500; the
client and the listener read these rules from one shared definition.
Sockets, HTTP parsing and connection behavior (414, 431, Expect, pipelining)
are not simulated.
