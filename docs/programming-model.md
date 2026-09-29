# Application programming model

## Bootstrap and dependencies

`Axiom.create()` returns a new, independent `Application` in the `CONFIGURING`
state. Core discovers exactly one `ApplicationProvider` with JDK `ServiceLoader`
and the thread context class loader. A missing provider reports how to add the
runtime; multiple providers fail rather than selecting one based on classpath order.
The experimental provider SPI exists to keep the dependency from server to core.

Use `axiom-http` in the repository's examples, or `axiom-test` for the test client.
Both expose core contracts at compile time and include the server provider at
runtime. There is no external transport or codec dependency yet.

## Registration and matching

```java
var app = Axiom.create();
app.get("/health", ctx -> "ok");
app.post("/jobs", ctx -> ctx.status(202).text("accepted"));
app.route("PROPFIND", "/files", ctx -> "metadata");
```

`get`, `post`, `put`, `patch`, `delete`, `head`, and `options` delegate to `route`.
A `Route` is an immutable identity; `routes()` returns an immutable snapshot in
registration order. Duplicate method/path pairs fail without replacing a handler.

Matching is exact and case-sensitive. Paths are neither decoded nor normalized:
`/users`, `/users/`, and `/Users` are distinct, as are `/a%2Fb` and `/a/b`.
Request metadata contains a raw absolute path, not a URL or query string.
Parameter and wildcard templates fail explicitly until compiled routing is added.
This initial matcher must not be treated as a network path validation policy.

Unknown paths return 404. Known paths with an unregistered method return 405 and
an alphabetically ordered `Allow` header. HEAD must be registered explicitly;
it does not fall back to GET, and all HEAD responses omit the body. Automatic
OPTIONS behavior will be considered with the router.

## Lifecycle and concurrency

- `start()` freezes registration and enters `RUNNING`; repeated starts are harmless.
- `handle(Request)` requires `RUNNING` and invokes the handler on the calling thread.
- `close()` enters `CLOSED` permanently, releases registered handler references,
  and rejects new requests. It is safe to call repeatedly or before startup.

Registration, startup, and shutdown are serialized. Request acceptance occurs
under the lifecycle lock, which is released before invoking application code.
Handlers can run concurrently and each invocation receives a fresh context.
Shared business objects must provide their own thread safety.

Close does not wait for or cancel accepted requests. They may complete after
`close()` returns. This is an in-memory lifecycle, not graceful network shutdown.
There are no framework executors, listeners, deadlines, or admission limits yet.

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

Handler exceptions propagate unchanged to the caller. This makes failures visible
in tests; a future network adapter must map errors into safe protocol responses.
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

`TestClient` uses the actual dispatcher and owns the supplied application's lifecycle:
creating it starts the application, and closing it closes the application. It runs
synchronously and propagates handler exceptions. It does not simulate wire encoding.
