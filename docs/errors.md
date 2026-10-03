# Errors

## Never-leak rule

Error responses produced by Axiom contain only values that cannot carry client
input or internal details: a status, a short code, the framework request ID and,
when present, field violations. Exception messages, causes, stack traces, class
names, parser output and request content never reach a response body, in
production or in tests. Exceptions that are not `AxiomException` and have no
[error handler](#error-handlers) become a generic **500** over HTTP and are logged
with the request ID.

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
| `violations` | Present only when non-empty: `field` (property path such as `items[0].name`) and `code` |

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

Default codes are the RFC 9110 reason phrase in snake case (`HttpStatus.defaultCode`),
so 413 is `content_too_large` and 422 `unprocessable_content`. Subclass
`AxiomException` for other statuses from 400 to 599. `Allow`, `Retry-After` and
`WWW-Authenticate` are the only headers an exception can add; they are built from
typed values (a method set, a `Duration` rounded up to whole seconds and at most a
day, a challenge starting with an auth-scheme token) and validated to visible ASCII,
so CR/LF injection is rejected when the exception is created. Never build codes,
fields or challenges from request data. A cause attached with `initCause` is kept
for logs only. 5xx `AxiomException`s are logged at WARNING with the request ID.

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
  Accept check, like problem responses.
- `AxiomException`s keep the problem responses above unless a handler is
  registered for `AxiomException` or a subclass: the built-in mapping counts as
  the handler for `AxiomException`, so a handler for `Exception` does not
  replace it.
- Throwing an `AxiomException` from an error handler answers with its problem
  response; it is not offered to error handlers again. Any other exception from
  an error handler, or a `null` result, is logged with the request ID and
  answered with the generic 500 problem body, in memory, in `TestClient` and over
  HTTP. That 500 keeps a keep-alive connection open.
- Router answers (404, 405, 501, automatic OPTIONS) are responses, not exceptions,
  and are never offered. Customize them with `app.notFound`, `app.methodNotAllowed`
  and `app.notImplemented` (see [customising router answers](routing.md#customising-router-answers)),
  or with global middleware. Exceptions those handlers throw are offered like any
  handler's. Requests rejected before routing (413, CONNECT) and listener errors are
  not offered either.
- A mapped failure is logged at WARNING with the request ID and the exception,
  server-side only, unless it is an `AxiomException` below 500 answered below
  500 (an expected client error). Translations by throwing an `AxiomException`
  log the original exception the same way.
- Only `Exception` subclasses can be mapped; `Error`s keep failing the request.
- Error handlers never run for a request that was cancelled or whose deadline
  expired (its outcome is discarded anyway), and `InterruptedException` and
  `CancellationException` are never offered to them. If an error handler itself
  is interrupted, the interrupt flag is restored and the generic 500 is used.
- Exceptions without a handler behave as before: `AxiomException`s become problem
  responses, others propagate from `app.handle` and `TestClient` and become the
  generic 500 over HTTP.

What an error handler returns reaches the client unchanged: never copy exception
messages, class names or stack traces into it. Headers that middleware add after
`next.run()` are not on error handler or problem responses, because the exception
passed through the middleware; a middleware that must decorate them catches the
exception itself.

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
| 414 | Request line longer than 4 KiB | Listener |
| 415 | Missing, unsupported or non-UTF-8 Content-Type in `ctx.body` | Runtime |
| 417 | An `Expect` value other than `100-continue` | Listener |
| 431 | Header section larger than 8 KiB | Listener |
| 500 | Unexpected handler or middleware exception without an error handler, unencodable or oversized response | Listener (in memory: exception propagates) |
| 500 | Failing [error handler](#error-handlers) | Runtime |
| 501 | CONNECT (also from `app.handle` and `TestClient`), Upgrade, or a single Transfer-Encoding line applying another coding before a final `chunked` (RFC 9112 section 6.1; see [wire behavior](http.md#wire-behavior)) | Listener |
| 501 | No route matches the path and the method is not recognized | Runtime |
| 503 | No execution capacity, queue wait expired, listener draining, more than eight outstanding pipelined requests or their bodies over the connection's share | Listener and `TestClient` |
| 504 | Request deadline expired while queued or running | Listener and `TestClient` |
| 505 | HTTP version other than 1.0 or 1.1 | Listener |

Errors the listener generates itself (the rows marked Listener, including its 500,
503 and 504) close the connection because the request framing or connection state
may be unusable. Runtime errors (404, 405, 406, 415, 501 for an unrecognized method, 400 from decoding) and
`AxiomException`s thrown by handlers, whatever their status, keep a keep-alive
connection open. A listener never sends an error ahead of an
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
