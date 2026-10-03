# Changelog

## Unreleased

- Add `Application.recognizeMethods(...)` to declare extension methods that no route has, so they are answered 404 rather than 501 on unrouted paths. **Binary incompatible (pre-release):** `Application` gained `recognizeMethods(String...)`.
- Add `Context.automaticOptions()` so an explicit OPTIONS route, such as a wildcard preflight handler, can answer the requests it does not handle with the automatic 204 and accurate `Allow` list.
- List `OPTIONS` in the `Allow` header of 405 responses, since every routed path answers it (RFC 9110 section 15.5.6); the list now equals the automatic OPTIONS answer.
- Add observability: a dependency-free `Metrics` SPI in core with `Application.metrics(...)`; the dispatcher records requests by method, route template and status class, latency, admission rejections, queue wait and active and queued depth (no raw paths or user input as tags); the opt-in `axiom-metrics` module with a bounded in-memory `MetricsRegistry` and `PrometheusText` renderer and handler; `Health` and `HealthCheck` for liveness and readiness with `/health/live` and `/health/ready` routes and `beginDrain()`; and a strictly parsed W3C `traceparent` as `Context.traceContext()`. **Binary incompatible (pre-release):** `Application` gained `metrics(Metrics)` and `metrics()`. See `docs/observability.md`.
- Add opt-in security modules: `axiom-security` (Authenticator SPI, `authenticate`/`authenticated`/`hasRole`/`hasAnyRole`/`hasPermission` policies answering 401 or 403, trusted-proxy client addresses, header redaction, secure default response headers) and `axiom-security-jwt` (strict JDK-only JWT bearer authenticator for HS, RS and ES algorithms). Core gains `SecurityIdentity`, `Context.identity()` and the set-once `Context.identity(SecurityIdentity)`.
- Expose the connection's peer as `Request.remoteAddress()` (set by the HTTP listener, `null` in memory) with `Request.withRemoteAddress`. `Request` gained a sixth record component; the five-argument constructor remains.
- Rename the Java packages and the Maven group from `io.axiom` to `com.jsgalactic.axiom` (the starter is now `com.jsgalactic.axiom:axiom`; artifact IDs are unchanged). Nothing had been published under `io.axiom`.
- Add `Context.validatedBody(type, validator)` with core's `BodyValidator`, which axiom-validation's `Validator` now extends.
- Map exceptions to responses with `app.error(type, handler)`; the nearest registered superclass wins, problem responses stay the default for AxiomException, and a failing error handler produces the generic 500.
- Add middleware (`app.use`, route-level arguments) and route groups with path prefixes and scoped middleware, composed once at startup; global middleware also wrap 404, 405, automatic OPTIONS and 501 answers. **Binary incompatible (pre-release):** `route`, `get`, `post`, `put`, `patch`, `delete`, `head` and `options` moved to the new `RouteGroup` interface and gained a trailing `Middleware...` parameter. Existing source compiles unchanged, but code compiled against the previous `Application` methods must be recompiled.
- Answer OPTIONS for routed paths and `OPTIONS *` automatically with 204 and Allow, refuse TRACE and CONNECT routes, answer CONNECT with 501 everywhere, answer unrecognized methods on unrouted paths with 501, and document every method in one table.
- Send no body with listener errors for HEAD requests.
- Add opt-in validation modules: annotation-free rules in axiom-validation and a Jakarta Validation adapter in axiom-validation-jakarta, reported as 422 field violations.
- Add the `io.axiom:axiom` starter, Maven publication (binary, sources, Javadoc, POM, Gradle module metadata) with optional signing, and the BOM covering all published modules.
- Add consumer compatibility builds (Gradle Kotlin, Gradle Groovy, Maven) behind `./gradlew compatibilityTest`, a scheduled compatibility workflow and a release workflow skeleton.
- Enforce Gradle dependency verification and add a release guide.
- Answer path captures that ctx.pathDecoded cannot decode with 400 invalid_path_encoding instead of 500.
- Send the GET representation's Content-Length on successful HEAD responses.
- Retain the request query and read decoded parameters with ctx.query(name) and ctx.queryAll(name).
- Read bounded request bodies (Content-Length, chunked, Expect: 100-continue) with a configurable limit.
- Add a codec SPI, a strict Jackson JSON codec in axiom-json, and Context.body/json.
- Answer errors with application/problem+json bodies and add exceptions for common 4xx/5xx statuses.
- Map transport failures to 408, 413, 414, 417, 431, 501 and 505.
- Send request bodies from TestClient and add a JSON notes API example.
- Compile route templates with named parameters, terminal wildcards, and deterministic precedence.
- Detect ambiguous route shapes at startup and expose raw captures through the request context.
- Add routing correctness coverage and an opt-in JMH dispatch benchmark.
- Add application creation, exact-path registration, lifecycle controls, and in-memory execution.
- Add request/response contracts, an in-memory test client, and a runnable Hello World example.
- Bootstrap the Java 21 Gradle multi-project build.
