<p align="center">
  <img src="branding/logo.svg" alt="Axiom" height="96">
</p>

# Axiom

[![Build](https://github.com/matrixjnr/axiom/actions/workflows/build.yml/badge.svg?branch=main)](https://github.com/matrixjnr/axiom/actions/workflows/build.yml)
[![CodeQL](https://github.com/matrixjnr/axiom/actions/workflows/codeql.yml/badge.svg?branch=main)](https://github.com/matrixjnr/axiom/actions/workflows/codeql.yml)
[![OpenSSF Scorecard](https://api.scorecard.dev/projects/github.com/matrixjnr/axiom/badge)](https://scorecard.dev/viewer/?uri=github.com/matrixjnr/axiom)
[![License: Apache-2.0](https://img.shields.io/badge/license-Apache--2.0-blue.svg)](LICENSE)
[![Java 21](https://img.shields.io/badge/Java-21-orange.svg)](https://openjdk.org/projects/jdk/21/)
[![Documentation](https://img.shields.io/badge/docs-matrixjnr.github.io%2Faxiom-4f46e5.svg)](https://matrixjnr.github.io/axiom/)

**Documentation: <https://matrixjnr.github.io/axiom/>**

Axiom is an early, pre-release Java 21 framework for HTTP APIs. Applications register
handlers on compiled routes; an HTTP/1.1 listener built on Netty runs each request on
a virtual thread under a deadline and bounded admission, request bodies are bounded
and decoded by a strict JSON codec, and every error is an `application/problem+json`
response that never leaks internals. The same dispatcher runs in memory through
`TestClient`, so applications are tested without opening a port. Nothing is published
to Maven Central yet.

## Hello, world

<!-- snippet: examples/readme/src/main/java/Hello.java -->
```java
import com.jsgalactic.axiom.Axiom;

public class Hello {
    public static void main(String[] args) throws Exception {
        try (var app = Axiom.create()) {
            app.get("/", ctx -> "Hello, world!");
            app.listen(8080).termination().toCompletableFuture().join();
        }
    }
}
```

`listen(8080)` binds `127.0.0.1`; see [HTTP listeners](docs/http.md) for other addresses.

## A JSON API

Records as bodies, a path parameter, a query parameter, a route group with middleware,
an error handler and a validated body. It needs only the starter.

<!-- snippet: examples/readme/src/main/java/TasksApi.java -->
```java
import com.jsgalactic.axiom.Axiom;
import com.jsgalactic.axiom.application.Application;
import com.jsgalactic.axiom.context.BodyValidator;
import com.jsgalactic.axiom.context.Middleware;
import com.jsgalactic.axiom.error.NotFoundException;
import com.jsgalactic.axiom.error.Violation;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

public final class TasksApi {
    public record NewTask(String title) {}
    public record Task(String id, String title) {}

    static final BodyValidator<NewTask> VALID = task -> task.title() == null || task.title().isBlank()
            ? List.of(new Violation("title", "required")) : List.of();
    static final Middleware NO_SNIFF = (ctx, next) -> next.run().withHeader("X-Content-Type-Options", "nosniff");

    static Application create() {
        var tasks = new ConcurrentHashMap<String, Task>();
        var ids = new AtomicLong();
        var app = Axiom.create();
        app.error(NoSuchElementException.class, (ctx, e) -> { throw new NotFoundException("task_not_found"); });
        app.group("/tasks", api -> {
            api.use(NO_SNIFF);
            api.post("", ctx -> {                                  // POST /tasks, 422 on a blank title
                var task = new Task(Long.toString(ids.incrementAndGet()), ctx.validatedBody(NewTask.class, VALID).title());
                tasks.put(task.id(), task);
                return ctx.status(201).json(task).withLocation("/tasks/" + task.id());
            });
            api.get("", ctx -> {                                   // GET /tasks?q=milk
                var text = ctx.query("q").orElse("");
                return ctx.json(tasks.values().stream().filter(t -> t.title().contains(text)).toList());
            });
            api.get("/:id", ctx -> ctx.json(Optional.ofNullable(tasks.get(ctx.path("id"))).orElseThrow()));
        });
        return app;
    }

    public static void main(String[] args) throws Exception {
        create().listen(8080).termination().toCompletableFuture().join();
    }
}
```

Tested without a socket:

<!-- snippet: examples/readme/src/test/java/TasksApiTest.java#tested -->
```java
try (var client = TestClient.start(TasksApi.create())) {
    var created = client.post("/tasks", "application/json", "{\"title\":\"Buy milk\"}");
    assertThat(created.status()).isEqualTo(201);
    assertThat(client.get("/tasks/42").status()).isEqualTo(404);   // problem+json, code task_not_found
}
```

These three blocks are quoted from [`examples/readme`](examples/readme), which the build compiles and
tests; `checkReadmeSnippets` fails when the README and the source differ.
[`examples/rest-api`](examples/rest-api) is a complete version with tests.

## What works today

- **HTTP/1.1 listener on Netty** with keep-alive and pipelining; Netty stays behind the
  public API ([HTTP listeners](docs/http.md)).
- **Virtual-thread execution** with a per-request deadline (504) and cancellation
  ([execution](docs/execution.md)).
- **Bounded admission**: aggregate and per-route active limits with bounded waiting
  queues (503) and observable counters ([admission](docs/admission.md)).
- **Compiled routing** on an immutable segment trie: named parameters, terminal
  wildcards, deterministic precedence, ambiguous templates rejected at registration,
  HEAD fallback to GET, automatic OPTIONS, 405 with `Allow` ([routing](docs/routing.md)).
- **Query parameters** decoded as strict UTF-8 with bounds ([routing](docs/routing.md#query-parameters)).
- **Bounded request bodies** (Content-Length, chunked, `Expect: 100-continue`) and a
  strict Jackson JSON codec ([bodies](docs/bodies.md)).
- **Streaming responses and server-sent events**: `Response.stream` and `Response.sse` write
  the body from the handler's virtual thread with chunked encoding, backpressure from slow
  clients, a byte cap and the request deadline; a disconnect, cap or shutdown aborts the writer
  and closes the connection, and `TestClient.stream` reads them deterministically
  ([streaming](docs/streaming.md)).
- **Errors**: `application/problem+json` for every error, exceptions for common 4xx/5xx
  statuses, and a never-leak rule ([errors](docs/errors.md)).
- **Middleware, route groups and error handlers**, composed once at startup
  ([middleware](docs/middleware.md)).
- **Validation modules**: annotation-free rules and a Jakarta Validation adapter,
  reported as 422 field violations ([validation](docs/validation.md)).
- **`TestClient`**: in-memory requests through the real dispatcher, admission and
  deadlines ([programming model](docs/programming-model.md#testing-without-ports)).
- **Graceful drain**: closing a listener stops admission, lets running handlers finish
  within a grace period, then interrupts them ([HTTP listeners](docs/http.md#ownership-and-shutdown)).
- **TLS** on the HTTP/1.1 listener with the JDK's TLS: PEM or `SSLContext` key material validated at
  startup, TLS 1.2 minimum with modern suites, optional mutual TLS, certificate reload without a
  restart, ALPN `http/1.1`, and `Request.isSecure()` ([TLS](docs/tls.md)).
- **Security modules (opt-in)**: a request-scoped `SecurityIdentity`, an `Authenticator`
  SPI, `authenticated()`/`hasRole`/`hasPermission` policies (401 vs 403 problem responses),
  a strict JDK-only JWT authenticator, trusted-proxy client addresses, header redaction and
  secure default headers ([security](docs/security.md)).
- **Observability (opt-in registry)**: a dependency-free `Metrics` SPI instrumenting requests
  by route template and status class, latency, admission rejections and queue depth and wait;
  an in-memory registry with Prometheus text output; liveness and readiness routes that turn
  unready while draining; and a strictly parsed W3C `traceparent` on the context
  ([observability](docs/observability.md)).
- **Secure path handling**: raw paths with dot or empty segments, backslashes or encoded
  separators are rejected with 400, never normalized ([routing](docs/routing.md#raw-paths-and-ownership)).

## Not yet

- HTTP/2
- WebSocket
- Streaming request bodies (request bodies are buffered in memory)
- Sessions, cookie authentication, CSRF, CORS and OAuth flows; JWKS key fetching
- OpenTelemetry, tracing spans and metrics exporters other than Prometheus text
- Native transports (epoll, io_uring)
- Publication to Maven Central

Known limitations are tracked in [#13](https://github.com/matrixjnr/axiom/issues/13) and
remaining milestones in the [roadmap, #18](https://github.com/matrixjnr/axiom/issues/18).

## Modules

| Module | Artifact ID | Purpose |
| --- | --- | --- |
| `axiom-starter` | `axiom` | The one dependency for applications: core API at compile time; HTTP, server and JSON at run time |
| `axiom-core` | `axiom-core` | Application contracts, request/response values, errors and the bootstrap SPI |
| `axiom-server` | `axiom-server` | Lifecycle, compiled route dispatch, admission and the default runtime |
| `axiom-http` | `axiom-http` | HTTP/1.1 transport on Netty |
| `axiom-json` | `axiom-json` | Strict JSON codec on Jackson, loaded at run time |
| `axiom-test` | `axiom-test` | In-memory `TestClient` |
| `axiom-validation` | `axiom-validation` | Validator interface and annotation-free rules (opt-in) |
| `axiom-validation-jakarta` | `axiom-validation-jakarta` | Jakarta Validation through Hibernate Validator (opt-in) |
| `axiom-security` | `axiom-security` | Authenticator SPI, role and permission policies, trusted proxies, header redaction, security headers (opt-in) |
| `axiom-security-jwt` | `axiom-security-jwt` | Strict JWT bearer-token authenticator on the JDK only (opt-in) |
| `axiom-metrics` | `axiom-metrics` | Bounded in-memory metrics registry and Prometheus text output (opt-in) |
| `axiom-openapi` | `axiom-openapi` | OpenAPI 3.1 and Swagger 2.0 documents from route descriptions (opt-in) |
| `axiom-openapi-ui` | `axiom-openapi-ui` | Swagger UI served from the application, files packaged statically, no CDN (opt-in) |
| `axiom-bom` | `axiom-bom` | Bill of materials aligning all Axiom versions |
| `integration-tests` | not published | JSON contract tests with the real codec, in memory and over a live listener |
| `benchmarks/http` | not published | JMH microbenchmarks; no performance claims ([benchmarks](docs/benchmarks.md)) |
| `examples/hello`, `examples/rest-api` | not published | Runnable examples |

All artifacts use the group `com.jsgalactic.axiom`. Netty and Jackson never appear in
an Axiom API; core depends only on the JDK ([build decisions](docs/build.md)).

## Install

> **Not yet published to Maven Central.** These are the intended coordinates; they will
> not resolve until a first release. Publishing them is tracked in
> [#19](https://github.com/matrixjnr/axiom/issues/19) and [releasing](docs/releasing.md).

Gradle (Kotlin DSL):

```kotlin
dependencies {
    implementation(platform("com.jsgalactic.axiom:axiom-bom:VERSION"))
    implementation("com.jsgalactic.axiom:axiom")
    testImplementation("com.jsgalactic.axiom:axiom-test")
}
```

Gradle (Groovy DSL):

```groovy
dependencies {
    implementation platform('com.jsgalactic.axiom:axiom-bom:VERSION')
    implementation 'com.jsgalactic.axiom:axiom'
    testImplementation 'com.jsgalactic.axiom:axiom-test'
}
```

Maven:

```xml
<dependencyManagement>
  <dependencies>
    <dependency>
      <groupId>com.jsgalactic.axiom</groupId>
      <artifactId>axiom-bom</artifactId>
      <version>VERSION</version>
      <type>pom</type>
      <scope>import</scope>
    </dependency>
  </dependencies>
</dependencyManagement>
<dependencies>
  <dependency>
    <groupId>com.jsgalactic.axiom</groupId>
    <artifactId>axiom</artifactId>
  </dependency>
  <dependency>
    <groupId>com.jsgalactic.axiom</groupId>
    <artifactId>axiom-test</artifactId>
    <scope>test</scope>
  </dependency>
</dependencies>
```

Validation is opt-in: add `com.jsgalactic.axiom:axiom-validation`, or
`com.jsgalactic.axiom:axiom-validation-jakarta` for Jakarta annotations (versions from
the BOM). See [validation](docs/validation.md).

## Security

Security is opt-in too: add `com.jsgalactic.axiom:axiom-security`, or
`com.jsgalactic.axiom:axiom-security-jwt` for bearer JWTs (it brings `axiom-security`).
Neither adds a third-party dependency.

```java
var jwt = JwtAuthenticator.builder()
        .publicKey(JwsAlgorithm.RS256, issuerPublicKey)   // alg allow-list = registered keys
        .issuer("https://login.example.com")
        .audience("notes-api")
        .build();
var security = Security.of(jwt);

app.use(SecurityHeaders.defaults());                      // nosniff, frame and referrer policies, CSP
app.get("/me", ctx -> ctx.identity().orElseThrow().principal(), security.authenticated());
app.group("/admin", admin -> admin.use(security.hasRole("admin")));   // 401 anonymous, 403 otherwise
```

Missing credentials answer 401 with `WWW-Authenticate`; malformed, expired or forged
tokens 401 `invalid_token` (never echoing the token); a missing role or permission 403.
Forwarded client addresses are believed only from configured proxies
(`TrustedProxies.of("10.0.0.0/8").resolve(ctx.request())`). See [security](docs/security.md).

## Observability

Metrics, health checks and trace context need no extra dependency; `axiom-metrics` adds an
in-memory registry and a Prometheus renderer.

```java
var registry = MetricsRegistry.create();
var app = Axiom.create().metrics(registry);             // requests, latency, admission, queue depth
var health = Health.builder(app).readiness("database", () -> pool.isValid(1)).build();
health.register(app, security.hasRole("ops"));          // GET /health/startup, /health/live, /health/ready
app.get("/metrics", PrometheusText.handler(registry), security.hasRole("ops"));
// shutdown: app.closeOnJvmShutdown(Duration.ofSeconds(10)) drains readiness, waits, then closes
```

Tags are route templates and status classes, never raw paths or user input. **Do not expose
`/metrics` or the health routes publicly without authentication.** See
[observability](docs/observability.md).

## OpenAPI and Swagger

Describe a route with plain code (no annotations), then serve the generated documents and, if you
want it, Swagger UI (no CDN: the UI files are packaged). Nothing is served unless you register it.

<!-- snippet: examples/openapi/src/main/java/example/openapi/BooksApi.java#describe-route -->
```java
var add = app.post("/books", ctx -> {
    var request = ctx.validatedBody(NewBook.class, NEW_BOOK);
    var book = new Book(ids.incrementAndGet(), request.title(), request.author(), request.year());
    books.put(book.id(), book);
    return ctx.status(201).json(book);
});
app.describe(add, RouteDoc.summary("Add a book")
        .description("Stores a new book and returns it with its id.")
        .tags("books").operationId("addBook")
        .requestBody(NewBook.class)
        .response(201, "The stored book", Book.class)
        .response(422, "The book is not valid")
        .security("bearer"));
```

<!-- snippet: examples/openapi/src/main/java/example/openapi/BooksApi.java#serve -->
```java
if (docsAccess != null) {
    openApi.serve(app, "/openapi.json", docsAccess);        // OpenAPI 3.1
    openApi.serveSwagger(app, "/swagger.json", docsAccess); // Swagger 2.0
    SwaggerUi.builder()                                     // Swagger UI at /docs
            .spec("OpenAPI 3.1", "/openapi.json").spec("Swagger 2.0", "/swagger.json")
            .build().register(app, docsAccess);
}
```

Records, JavaBean and Lombok-style classes, enums and collections become schemas, and validation
rules become constraints. Treat the documentation as part of your API surface and protect or omit it
in production. See [OpenAPI and Swagger](docs/openapi.md).

## Build and test

JDK 21 and the committed Gradle wrapper (on Windows, `gradlew.bat`):

| Command | Runs |
| --- | --- |
| `./gradlew check` | Compilation, unit and integration tests, module boundary and API checks |
| `./gradlew unitTest` | Every module's `test` task (no sockets) |
| `./gradlew integrationTest` | Every module's `integrationTest` task (live listeners) |
| `./gradlew coverageReport` | Per-module and aggregated JaCoCo reports (`build/reports/jacoco/coverageReport`) |
| `./gradlew compatibilityTest` | Gradle Kotlin, Gradle Groovy and Maven consumer builds against locally published artifacts |
| `./gradlew :examples:hello:run` | Hello World on `http://127.0.0.1:8080/` |
| `./gradlew :examples:rest-api:run` | The notes JSON API on the same port |
| `./gradlew :examples:openapi:run` | The books API; set `DOCS_PASSWORD` to also serve `/openapi.json`, `/swagger.json` and Swagger UI at `/docs` |

Details are in [build decisions](docs/build.md).

## Documentation

- [Programming model](docs/programming-model.md), [routing](docs/routing.md),
  [request bodies and JSON](docs/bodies.md), [streaming and server-sent events](docs/streaming.md),
  [errors](docs/errors.md),
  [middleware](docs/middleware.md), [validation](docs/validation.md), [security](docs/security.md),
  [observability](docs/observability.md), [OpenAPI and Swagger](docs/openapi.md)
- [HTTP listeners](docs/http.md), [TLS](docs/tls.md), [execution and deadlines](docs/execution.md),
  [admission](docs/admission.md), [benchmarks](docs/benchmarks.md)
- [Build decisions](docs/build.md), [releasing](docs/releasing.md),
  [changelog](CHANGELOG.md)

## Contributing and security

See [CONTRIBUTING](CONTRIBUTING.md) and the [code of conduct](CODE_OF_CONDUCT.md).
Report vulnerabilities privately as described in [SECURITY](SECURITY.md). Issues and
ideas go to the [issue tracker](https://github.com/matrixjnr/axiom/issues).

## Support Axiom

If Axiom is useful to you, [buy me a coffee via PayPal](https://www.paypal.com/cgi-bin/webscr?cmd=_donations&business=jonnysimiyu%40gmail.com&item_name=Axiom%20open-source%20development&currency_code=USD)
to support its development. Choose any amount; payments go to `jonnysimiyu@gmail.com`.

## License

Licensed under the [Apache License 2.0](LICENSE).
