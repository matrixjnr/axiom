# Errors

## Never-leak rule

Error responses produced by Axiom contain only values that cannot carry client
input or internal details: a status, a short code, the framework request ID and,
when present, field violations. Exception messages, causes, stack traces, class
names, parser output and request content never reach a response body, in
production or in tests. Exceptions that are not `AxiomException` become a generic
**500** over HTTP and are logged with the request ID.

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
status and headers without the body.

## Throwing errors

Handlers throw a subtype of `io.axiom.error.AxiomException`. Each has a no-argument
constructor using the default code and a constructor taking a custom safe code:

```java
throw new NotFoundException("note_not_found");
throw new ConflictException();
throw new ValidationException(List.of(new Violation("title", "required")));
throw new UnauthorizedException("Bearer realm=\"api\"");
throw new TooManyRequestsException(Duration.ofSeconds(30));
```

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

## Framework statuses

| Status | When | Where |
| --- | --- | --- |
| 400 | Malformed request line or headers, invalid Host, rejected path or query, malformed Content-Length, both Content-Length and Transfer-Encoding, Transfer-Encoding on HTTP/1.0, a coding list not ending in `chunked`, more than one Transfer-Encoding line | Listener |
| 400 | Empty body or codec failure in `ctx.body` | Runtime |
| 404 | No route matches the path | Runtime |
| 405 | Route exists for other methods; `Allow` lists them | Runtime |
| 406 | Accept excludes the codec response's media type, decided after the handler ran (see [negotiation](bodies.md#accept-negotiation-406)) | Runtime |
| 408 | Request head not complete within ten seconds, or body not complete by the request deadline | Listener |
| 413 | Body over `maxRequestBody` | Listener and runtime |
| 414 | Request line longer than 4 KiB | Listener |
| 415 | Missing, unsupported or non-UTF-8 Content-Type in `ctx.body` | Runtime |
| 417 | An `Expect` value other than `100-continue` | Listener |
| 431 | Header section larger than 8 KiB | Listener |
| 500 | Unexpected handler exception, unencodable or oversized response | Listener (in memory: exception propagates) |
| 501 | CONNECT, Upgrade, or a transfer coding other than `chunked` before it | Listener |
| 503 | No execution capacity, queue wait expired, listener draining, more than eight outstanding pipelined requests or their bodies over the connection's share | Listener and `TestClient` |
| 504 | Request deadline expired while queued or running | Listener and `TestClient` |
| 505 | HTTP version other than 1.0 or 1.1 | Listener |

Errors the listener generates itself (the rows marked Listener, including its 500,
503 and 504) close the connection because the request framing or connection state
may be unusable. Runtime errors (404, 405, 406, 415, 400 from decoding) and
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
| 204 | Return `null` or `ctx.noContent()` |
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
