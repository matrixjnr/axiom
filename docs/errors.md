# Errors

## Never-leak rule

Error responses produced by Axiom contain only values that cannot carry client
input or internal details: a status, a short code, the framework request ID and,
when present, field violations. Exception messages, causes, stack traces, class
names, parser output and request content never reach a response body, in
production or in tests. Exceptions that are not `AxiomException` and have no
[error handler](#error-handlers) become a generic **500** over HTTP and are logged
with the request ID. The one exception to the rule is the explicit, loopback-only
[development mode](#development-errors).

## Problem responses

Every framework and application error response uses an
[RFC 9457](https://www.rfc-editor.org/rfc/rfc9457) style body with media type
`application/problem+json`:

```json
{"status":422,"code":"validation_failed","requestId":"q3J0bW9yZS1yYW5k-1a",
 "violations":[{"field":"title","code":"required"}]}
```

| Member | Meaning |
| --- | --- |
| `status` | The HTTP status, repeated for clients that lose the status line |
| `code` | Machine-readable code: `[a-z][a-z0-9_.-]{0,63}`, validated when the exception is created |
| `requestId` | The `X-Request-ID` of the response, for correlating logs |
| `violations` | Present only when non-empty: `code` and, unless the violation concerns the whole value, `field` (property path such as `items[0].name`) |

`type`, `title`, `detail` and `instance` are omitted; RFC 9457 treats a missing
`type` as `about:blank`. The in-memory `app.handle`, `TestClient` and the HTTP
listener produce identical bodies for the same failure. HEAD responses keep the
status and headers without the body and, unlike successful HEAD responses, carry no
`Content-Length`.

## Throwing errors

Handlers throw a subtype of `com.jsgalactic.axiom.error.AxiomException`. Each has a no-argument
constructor using the default code and a constructor taking a custom safe code:

```java
throw new NotFoundException("note_not_found");
throw new ConflictException();
throw new ValidationException(List.of(new Violation("title", "required")));
throw new UnauthorizedException("Bearer realm=\"api\"");
throw new TooManyRequestsException(Duration.ofSeconds(30));
```

`Validation.require` in the optional validation modules throws `ValidationException`
from rules or Jakarta annotations; see [validation](validation.md).

| Status | Exception | Default code | Extra header |
| --- | --- | --- | --- |
| 400 | `BadRequestException`, `DecodeException` | `bad_request` | |
| 401 | `UnauthorizedException(challenge)` | `unauthorized` | `WWW-Authenticate` |
| 403 | `ForbiddenException` | `forbidden` | |
| 404 | `NotFoundException` | `not_found` | |
| 405 | `MethodNotAllowedException(methods)` | `method_not_allowed` | `Allow` |
| 406 | `NotAcceptableException` | `not_acceptable` | |
| 408 | `RequestTimeoutException` | `request_timeout` | |
| 409 | `ConflictException` | `conflict` | |
| 410 | `GoneException` | `gone` | |
| 411 | `LengthRequiredException` | `length_required` | |
| 412 | `PreconditionFailedException` | `precondition_failed` | |
| 413 | `PayloadTooLargeException` | `content_too_large` | |
| 414 | `UriTooLongException` | `uri_too_long` | |
| 415 | `UnsupportedMediaTypeException` | `unsupported_media_type` | |
| 422 | `UnprocessableContentException`, `ValidationException` | `unprocessable_content`, `validation_failed` | |
| 428 | `PreconditionRequiredException` | `precondition_required` | |
| 429 | `TooManyRequestsException([retryAfter])` | `too_many_requests` | `Retry-After` |
| 431 | `RequestHeaderFieldsTooLargeException` | `request_header_fields_too_large` | |
| 500 | `InternalServerErrorException` | `internal_server_error` | |
| 501 | `NotImplementedException` | `not_implemented` | |
| 502 | `BadGatewayException` | `bad_gateway` | |
| 503 | `ServiceUnavailableException([retryAfter])` | `service_unavailable` | `Retry-After` |
| 504 | `GatewayTimeoutException` | `gateway_timeout` | |

**411** is for endpoints, not the listener: a request with neither Content-Length nor
Transfer-Encoding has an empty body (RFC 9112 section 6.3), so the listener never
answers 411. A handler that accepts only bodies with a declared length, such as an upload
endpoint that refuses chunked content, checks the header itself:

```java
app.post("/upload", ctx -> {
    if (ctx.header("Content-Length").isEmpty()) { throw new LengthRequiredException(); }
    return ctx.status(201).json(store.save(ctx.request().body().bytes()));
});
```

A violation whose `field` is empty concerns the whole value (a rule across several
properties, or a `null` body); its problem entry has only a `code`, for example
`{"violations":[{"code":"end_before_start"}]}`.

Default codes are the RFC 9110 reason phrase in snake case (`HttpStatus.defaultCode`),
so 413 is `content_too_large` and 422 `unprocessable_content`. Subclass
`AxiomException` for other statuses from 400 to 599. `Allow`, `Retry-After` and
`WWW-Authenticate` are the only headers an exception can add; they are built from
typed values (a method set, a `Duration` rounded up to whole seconds and at most a
day, a challenge starting with an auth-scheme token) and validated to visible ASCII,
so CR/LF injection is rejected when the exception is created. Never build codes,
fields or challenges from request data. A cause attached with `initCause` is kept
for logs only. 5xx `AxiomException`s are logged with the request ID (see
[logging](#logging-of-failures)).

## Error handlers

`app.error(type, handler)` maps exceptions thrown by handlers and
[middleware](middleware.md) to responses:

```java
app.error(NoSuchElementException.class, (ctx, failure) -> { throw new NotFoundException("item_not_found"); });
app.error(QuotaExceededException.class, (ctx, failure) -> ctx.status(429).json(new Quota(failure.limit())));
```

- The registered class nearest to the exception's class in its superclass chain
  wins. One handler per class; registration ends at `start()`.
- Handlers run after every middleware has unwound, with the request's context
  (status reset to 200). Their responses are encoded by the codecs without an
  Accept check, like problem responses: **an error is never answered 406.** The
  client cannot be told about a failure it did not ask to see in that form, so the
  error is sent as its handler built it (a handler that wants to honor Accept
  reads `ctx.header("Accept")` itself); a successful response of the same type
  is still answered 406 when Accept excludes it.
- `AxiomException`s keep the problem responses above unless a handler is
  registered for `AxiomException` or a subclass: the built-in mapping counts as
  the handler for `AxiomException`, so a handler for `Exception` does not
  replace it.
- Throwing an `AxiomException` from an error handler answers with its problem
  response; it is not offered to error handlers again. Any other exception from
  an error handler, or a `null` result, is logged with the request ID and
  answered with the generic 500 problem body, in memory, in `TestClient` and over
  HTTP. That 500 carries `Connection: close` and the listener closes the
  connection after it, like the listener's own 500 for an unmapped exception.
- Router answers (404, 405, 501, automatic OPTIONS) are responses, not exceptions,
  and are never offered. Customize them with `app.notFound`, `app.methodNotAllowed`
  and `app.notImplemented` (see [customising router answers](routing.md#customising-router-answers)),
  or with global middleware. Exceptions those handlers throw are offered like any
  handler's. Requests rejected before routing (413, CONNECT) and listener errors are
  not offered either.
- A mapped failure is logged once; see [logging](#logging-of-failures).
- Only `Exception` subclasses can be mapped; `Error`s keep failing the request.
- Error handlers never run for a request that was cancelled or whose deadline
  expired (its outcome is discarded anyway), and `InterruptedException` and
  `CancellationException` are never offered to them. If an error handler itself
  is interrupted, the interrupt flag is restored and the generic 500 is used.
- Exceptions without a handler behave as before: `AxiomException`s become problem
  responses, others propagate from `app.handle` and `TestClient` and become the
  generic 500 over HTTP (unless [development errors](#development-errors) are on).
  `TestClient.start(app)` keeps the propagating behavior, which helps debugging;
  `TestClient.startMappingFailures(app)` answers them like the listener (see
  [testing](#testing-failures)).

What an error handler returns reaches the client unchanged: never copy exception
messages, class names or stack traces into it. Headers that middleware add after
`next.run()` are not added by that code for error handler or problem responses,
because the exception passed through the middleware; middleware that must decorate
them override `Middleware.afterError`, which runs for every such response (problem
responses, error handler responses, the generic 500 and the 406; see
[middleware](middleware.md#middleware)). `SecurityHeaders` and `Cors` do.

### Decorating the standard problem response

`ctx.problem(exception)` builds the response the runtime sends for an `AxiomException` when no
handler is registered: its status, the typed headers it carries (`Allow`, `Retry-After`,
`WWW-Authenticate`), `application/problem+json` and the body with only status, code, request ID
and violations. A handler for `AxiomException` can add to it instead of replacing it, and any
handler can answer with the standard problem for a status of its choosing:

```java
app.error(AxiomException.class, (ctx, failure) -> ctx.problem(failure).withHeader("Cache-Control", "no-store"));
app.error(PaymentGatewayDown.class, (ctx, failure) -> ctx.problem(new ServiceUnavailableException("payments_down")));
```

It builds a response only: it neither logs nor changes the context's status.

### Group-scoped handlers

`group.error(type, handler)` registers a handler for the routes of a [group](middleware.md#registration)
and its nested groups, with the same rules as `app.error` (one handler per class in a scope; the
built-in problem mapping of `AxiomException` stays unless a handler for `AxiomException` or a
subclass is registered). Resolution is inner scope first: the group that owns the matched route is
searched, then each enclosing group, then the application, and the first scope with a handler for the
exception's class or a superclass wins, even when an outer scope registered a nearer class.

```java
app.error(Exception.class, (ctx, failure) -> ctx.problem(new InternalServerErrorException()));
app.group("/api/v1", api -> {
    api.error(QuotaExceeded.class, (ctx, failure) -> ctx.status(429).json(new Quota(failure.limit())));
    api.get("/orders", listOrders);
});
```

All exceptions of a request, including those of global middleware, use the scopes of the route that
matched. A request no route serves has no group, so only the application's handlers apply to
exceptions of the custom `notFound`, `methodNotAllowed` and `notImplemented` handlers and global
middleware. Like group routes, a group's handlers are removed if its configuration callback throws.

### Testing failures

`TestClient.start(app)` lets an exception that nothing maps propagate to the test, and fails
a call whose response the transport could not send with `IllegalStateException`. A test can
therefore pass where a client would get a 500. `TestClient.startMappingFailures(app)` answers
exactly as the listener does: the generic 500 problem response (status, code and request ID,
`Connection: close`) for such an exception or response, logged at ERROR with its request ID, in
`execute`, `submit` and `stream` (before the head). Interruption, cancellation and a stream
aborted after its head still fail the call, because the listener sends no response for them.
`AxiomException`s, error handlers, middleware and admission behave identically in both modes.

### Development errors

For local debugging, `app.developmentErrors()` (before startup; there is no flag to pass, so
configuration cannot turn it on by accident) adds a `debug` member to the problem responses the
runtime builds for failures:

```json
{"status":500,"code":"internal_server_error","requestId":"...",
 "debug":{"type":"java.lang.IllegalStateException","message":"no such order 7",
          "stack":["com.example.Orders.load(Orders.java:41)","..."],
          "causes":[{"type":"java.io.IOException","message":"..."}]}}
```

It covers an `AxiomException`'s problem response, the generic 500 of a failing error handler
(describing the handler's failure), and an exception that nothing maps, which is then answered
with a 500 carrying `Connection: close` instead of propagating from `app.handle` and `TestClient`.
At most 64 stack frames and 8 causes are included. Responses built by your own error handlers
and failures the listener answers itself are unchanged. The `debug` member can expose internals
and request content, so the mode is guarded:

- off unless the call is made, and the default responses never contain exception details;
- `listen` throws `IllegalStateException` for any address that is not a loopback address
  (including the wildcard address and unresolved names) while it is on;
- a warning is logged at startup.

A reverse proxy or tunnel in front of the loopback listener would still forward the details, so
do not run it that way.

### Logging of failures

Failures are logged server-side only, never in a response, through the logger named
`com.jsgalactic.axiom.failures` at WARNING by default. `app.failureLog(logger, level)` chooses
another `System.Logger` and level before startup (`Level.OFF` silences the entries). Each
failure is logged once per request, with the request ID and the exception:

- a 5xx `AxiomException` answered by its built-in problem response;
- an exception an error handler mapped, whatever the answer's status, unless it is an
  `AxiomException` below 500 answered below 500 (an expected client error). A translation
  by throwing an `AxiomException` logs the original exception only, with the status of the
  translation, so a translated 5xx is not logged twice;
- a failing error handler (or a `null` result) is a defect and is always logged at ERROR on
  the same logger, with the original failure suppressed into it, whatever the level.

Exceptions that propagate unmapped from `app.handle` are the caller's to log; the HTTP
listener logs them itself.

## Framework statuses

The [method table](routing.md#methods) shows which of these each HTTP method receives.

| Status | When | Where |
| --- | --- | --- |
| 400 | Malformed request line or headers, a method that is not a token, invalid Host, rejected path or query, an absolute-form target, `*` with a method other than OPTIONS, malformed Content-Length, both Content-Length and Transfer-Encoding, Transfer-Encoding on HTTP/1.0, a coding list not ending in `chunked` or naming it twice, more than one Transfer-Encoding line (whatever the values, to avoid ambiguous framing) | Listener |
| 400 | Empty body or codec failure in `ctx.body` | Runtime |
| 400 | Query parameter or path capture that a typed accessor (`ctx.queryInt`, `queryLong`, `queryUuid`, `pathInt`, `pathLong`, `pathUuid`) cannot convert; code `invalid_query_parameter` or `invalid_path_parameter` (see [typed parameters](routing.md#typed-parameters)) | Runtime |
| 400 | Path capture that `ctx.pathDecoded` cannot decode safely (malformed UTF-8, or a decoded separator, backslash, NUL or dot segment); code `invalid_path_encoding` | Runtime |
| 404 | No route matches the path and the method is recognized (see [custom methods](routing.md#custom-methods)) | Runtime |
| 405 | Route exists for other methods, including every TRACE request to a routed path; `Allow` lists them | Runtime |
| 406 | Accept excludes the codec response's media type, decided after the handler ran (see [negotiation](bodies.md#accept-negotiation-406)) | Runtime |
| 408 | Request head not complete within ten seconds, or body not complete by the request deadline | Listener |
| 413 | Body over `maxRequestBody` | Listener and runtime |
| 414 | Request line longer than 4 KiB (`maxRequestLine`) | Listener |
| 415 | Missing, unsupported or non-UTF-8 Content-Type in `ctx.body` | Runtime |
| 417 | An `Expect` value other than `100-continue` | Listener |
| 431 | Header section larger than 8 KiB (`maxHeaderBytes`) | Listener |
| 500 | Unexpected handler or middleware exception without an error handler, unencodable or oversized response | Listener (in memory: the exception propagates, except from `TestClient.startMappingFailures`) |
| 500 | Failing [error handler](#error-handlers) | Runtime |
| 501 | CONNECT (also from `app.handle` and `TestClient`), Upgrade, or a single Transfer-Encoding line applying another coding before a final `chunked` (RFC 9112 section 6.1; see [wire behavior](http.md#wire-behavior)) | Listener |
| 501 | No route matches the path and the method is not recognized | Runtime |
| 503 | No execution capacity, queue wait expired, listener draining, more than eight outstanding pipelined requests (`maxPipelinedRequests`) or their bodies over the connection's share | Listener and `TestClient` |
| 504 | Request deadline expired while queued or running | Listener and `TestClient` |
| 505 | HTTP version other than 1.0 or 1.1 | Listener |

Errors the listener generates itself (the rows marked Listener, including its 500,
503 and 504) close the connection because the request framing or connection state
may be unusable. So does every **500 the framework generates**: an unmapped handler
exception, an unsendable response, and the generic 500 after a failing error handler
(which carries `Connection: close`, also in memory), since the application is in an
unknown state. Runtime errors (404, 405, 406, 415, 501 for an unrecognized method, 400 from decoding) and
`AxiomException`s thrown by handlers or error handlers, whatever their status (including
an `InternalServerErrorException`), keep a keep-alive connection open. A listener never sends an error ahead of an
earlier pipelined response: earlier requests complete and are answered in order, then
the error is sent and the connection closes (see
[errors on pipelined requests](http.md#errors-on-pipelined-requests)).

## Success responses

| Status | Pattern |
| --- | --- |
| 200 | Return a value, `ctx.text(...)` or `ctx.json(value)` |
| 201 | `ctx.status(201).json(created).withLocation("/items/" + id)` |
| 202 | `ctx.status(202).json(ticket)` or `ctx.status(202).response(null)` |
| 204 | Return `null` or `ctx.noContent()`; also the [automatic OPTIONS](routing.md#automatic-options) answer, with `Allow` |
| 304 | `Response.of(304, null)` with the validators your application computes |
| 301, 302, 303, 307, 308 | `ctx.redirect(303, "/orders/7")` |

`withLocation` and `redirect` accept URI references of at most 2048 visible ASCII
characters valid under RFC 3986; spaces, quotes, angle brackets, backslashes and
control characters (so CR/LF injection) are rejected. They do not judge whether a
target is safe: never redirect to a client-supplied URL without checking it.

## Not covered

Some statuses are deliberately not modeled: 1xx other than the listener's own
`100 Continue` (no 101 upgrades or 103 hints), 206 and 416 (no range requests),
conditional-request evaluation (applications may still return 304, 412 and 428
themselves), 300 and 305, WebDAV statuses (207, 423, 424, 507 and others), 418,
421, 425, 451 and 511. Applications can use any final status with
`Response.of(status, body)` or a custom `AxiomException` subclass.
