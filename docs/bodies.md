# Request bodies and JSON

A handler reads the request body through its context:

```java
record NewNote(String title, String text) { }

app.post("/owners/:owner/notes", ctx -> {
    var note = ctx.body(NewNote.class);          // decoded by the installed codec
    var saved = store.save(ctx.pathDecoded("owner"), note);
    return ctx.status(201).json(saved).withLocation("/notes/" + saved.id());
});
```

`ctx.validatedBody(NewNote.class, validator)` decodes and then checks the value,
answering violations with 422 (see [validation](validation.md#validated-request-bodies)).

Add `axiom-json` to the **runtime** classpath (`runtimeOnly`) to install the JSON
codec; application code never compiles against it. See the
[rest-api example](../examples/rest-api) for a complete API with tests.

## Limits

`app.maxRequestBody(bytes)` sets the largest accepted body before startup. The
default is 1 MiB; accepted values are 0 to 64 MiB, and zero rejects every non-empty
body. Bodies are buffered completely in memory before the handler runs; there is no
streaming API. Larger bodies receive **413** and the handler is never invoked:

- **Content-Length**: a declared length over the limit is answered before any body
  byte is read, and the connection closes.
- **Chunked**: chunks are counted as they arrive; the moment the running total
  exceeds the limit the listener answers 413 and closes the connection, without
  waiting for the rest.
- **In memory**: `app.handle(...)` and `TestClient` check the body length and answer
  the same 413 problem response.

`Transfer-Encoding` must be a single field line whose value is exactly `chunked`.
Another coding before `chunked` on that line (for example `gzip, chunked`) receives
**501**; more than one Transfer-Encoding line (whatever the values), `chunked` listed
twice, a coding list that does not end in `chunked`, both Content-Length and
Transfer-Encoding, or Transfer-Encoding on an HTTP/1.0 request receives **400**. All of
these close the connection, and nothing sent after the request is read as a request
(see [wire behavior](http.md#wire-behavior) for why the two statuses differ). Content-Length must be a single value of 1 to 18
digits (surrounding whitespace is allowed, as for any field); signs, empty values,
lists and other characters receive **400**. A request with neither Content-Length
nor Transfer-Encoding has an empty body (RFC 9112 section 6.3), whatever its method;
the listener never answers 411 (a handler can require a declared length itself; see
[errors](errors.md#throwing-errors)).

Bodies are framed, read and limited the same way for every method. A GET, HEAD,
DELETE or OPTIONS request may carry a body (RFC 9110 gives it no defined meaning for
these methods, but does not forbid it): the listener reads it within
`maxRequestBody`, answers 413 above it as for POST, and passes it to the handler as
`ctx.request().body()`. An OPTIONS request answered automatically (see
[automatic OPTIONS](routing.md#automatic-options)) has its body read within the limit
and then discarded, so the connection stays usable for the next request. A HEAD
response never carries body bytes, whether it comes from a handler, a routing error or
a listener error such as 413.

### Memory per connection

With `L` = `maxRequestBody`, the request body bytes the listener holds for one
connection are:

| Held | Bound |
| --- | --- |
| Body of the request whose handler is running | `L` |
| Bodies of pipelined requests waiting behind it, plus the body being received | `2 × L` together |

so at most `3 × L` per connection (3 MiB at the default). A declared Content-Length
is reserved in full against the `2 × L` share when its head arrives, although its
array only grows as bytes arrive; a chunked body is counted as it arrives. While an
array doubles, the old and new arrays briefly coexist. A request that would exceed the
`2 × L` share is not executed: it is answered 503 after the earlier responses and the
connection closes. Handler-side memory is additional: `ctx.body(...)` passes the
codec a read-only view of the running body, so the JSON codec reads it without a
copy, but a codec that implements only the array method receives one copy (up to
`L`); the decoded value lives until the handler drops it, and `Body.bytes()` copies
on every call. [Handler-side cost by access pattern](#handler-side-cost) lists each.

On top of the per-connection bounds, each listener has a **budget of request body bytes held at
once across all its connections**: `maxInFlightBodyBytes` in the [listener options](http.md#listener-options),
64 MiB by default. A request reserves its body when its head is accepted (a declared
Content-Length in full, so a slow uploader holds its whole announced share from the start) or, for a
chunked body, as the array grows, and returns the reservation when its response is final or its
connection ends. That covers the body being received, the bodies of pipelined requests waiting
behind a running one, and the running request's body, so the number of buffered pipelined bodies is
bounded by the budget as well as by `maxPipelinedRequests` and the `2 × L` share above. A request
that does not fit is not executed: it is answered 503 after the earlier responses on its connection
and the connection closes, deterministically and without waiting. With the defaults, a listener
therefore never holds more than 64 MiB of request bodies, not the 384 MiB (`128 × 3 × L`) that the
per-connection bounds alone would allow at 128 connections and 1 MiB bodies. The budget counts body
array capacity; during growth the old and new arrays briefly coexist, and a handler that ignores
cancellation after a timeout still references its body until it returns, although the reservation is
returned when the request's outcome is final. A listener refuses to start when its budget is smaller
than the application's `maxRequestBody` (the default budget equals the largest permitted body, 64 MiB).

The budget belongs to one listener; each additional listener has its own. Size `L`, the budget, the
request timeout and the heap together.

## Rejected bodies

A 413 (or another error) on a pipelined request is sent after the responses to
earlier requests, which still run; see
[errors on pipelined requests](http.md#errors-on-pipelined-requests). After the 413
the listener discards the rest of the body (for up to two seconds and 16 MiB, less once
the client pauses for 500 milliseconds) instead
of resetting the connection, so a client still sending reads the response; the same
applies after any response that ends a connection; see
[wire behavior](http.md#wire-behavior). Clients that send `Expect: 100-continue` and
wait avoid sending the body at all.

## Timing

The request deadline (`app.requestTimeout`, ten seconds by default) starts when the
request head has been parsed, so the time spent receiving the body counts against
it. A body still incomplete when the deadline expires is answered **408** and the
connection closes; trickling bytes does not extend it. The ten-second request-head
timeout applies only to the request line and headers. The 30-second inactivity
timeout still closes a connection on which no bytes move, including in the middle
of a body.

## Expect: 100-continue

A request with `Expect: 100-continue` whose headers are otherwise acceptable and
whose declared length is within the limit receives an interim `100 Continue`, then
the body is read. If earlier pipelined responses are still outstanding, the interim
response waits until they have been written, so it never overtakes them; if the
client sends the body without waiting, no interim response is sent. A request whose
declared length exceeds the limit gets 413 instead and the connection closes; any
other expectation gets **417**. Either rejection is sent without an interim response
and, on a busy connection, after the earlier responses. HTTP/1.0 requests never
receive `100 Continue`.

## Body ownership

`Request.body()` is an immutable `Body`: its bytes are copied when it is created,
`bytes()` returns a fresh copy, and `asReadOnlyBuffer()` gives a read-only view.
Bodies are safe to share with other threads and their `toString` never shows
content. The listener copies each received network buffer into a private array as
it arrives and releases the buffer at once, so no Netty buffer outlives its read.
The array is allocated when the first body byte arrives, starts at 8 KiB (or the
body size if that is smaller) and doubles as bytes arrive, so a client that declares a
large Content-Length and then stalls holds only what it has sent. A declared body's
array is capped at the declared length and ends exactly full; a chunked body's array
is capped at the limit and trimmed once at the end. The finished array is handed to the
`Body` without another copy, through `Body.Builder`, which owns the array until
`build()` and never exposes it. The body keeps the request's
`Content-Type`; `mediaType()` and `charset()` parse it. Request headers are
available through `ctx.header(name)` (one value per name; repeated fields are joined
with `", "`). `Request.toString` omits header values.

### Handler-side cost

What each way of reading a request body costs beyond the listener's bound above
(`L` = `maxRequestBody`, `n` = the body's length):

| Access | Extra memory | Notes |
| --- | --- | --- |
| `ctx.body(Type.class)` with a codec that overrides `decode(ByteBuffer, Class)` (the JSON codec) | the decoded value only | Reads a read-only view of the body; no copy of the bytes. |
| `ctx.body(Type.class)` with an array-only codec | `n` for the call, plus the value | The default `decode(ByteBuffer, Class)` copies the bytes once per call. Override it to avoid this. |
| `ctx.body(...)` called again | the same again | Each call decodes anew; keep the first value instead of decoding twice. |
| `body.asReadOnlyBuffer()`, `length()`, `isEmpty()`, `contentType()` | a few small objects | Never copies the content. |
| `body.bytes()` | `n` per call | Always a fresh copy, so call it once and keep the array. Prefer the view. |
| The decoded value | its own size, until the handler drops it | Additional to the connection bound, and held until the handler returns if it keeps a reference. |

A test measures the difference: building a body from a buffer allocates its array once,
`asReadOnlyBuffer()` allocates under a kilobyte for any size, and `bytes()` allocates the
whole content (`BodyTest`, `CodecViewTest`). A cached view is not
needed because the view is already a constant-size wrapper, and sharing one buffer between
callers would share its position.

## Decoding

`ctx.body(Type.class)` checks, in order:

| Condition | Status | Code |
| --- | --- | --- |
| Missing or empty body | 400 | `empty_body` |
| Missing or malformed Content-Type | 415 | `missing_content_type` |
| `charset` parameter other than `utf-8` | 415 | `unsupported_charset` |
| No codec for the media type | 415 | `unsupported_media_type` |
| Codec rejects the content | 400 | codec code, see below |

### Charset policy

Codecs consume UTF-8 only. A Content-Type without a `charset` parameter is read as
UTF-8, `charset=utf-8` (any case, optionally quoted) is accepted, and any other
charset, including aliases such as `utf8`, is 415 before the codec runs. The JSON
codec decodes strict UTF-8: malformed byte sequences fail with `invalid_encoding`,
UTF-16 and UTF-32 are not detected, and a leading UTF-8 byte order mark is ignored
as RFC 8259 permits.

## JSON codec

`axiom-json` registers a Jackson-based codec for `application/json` through the
`com.jsgalactic.axiom.codec.spi.BodyCodec` service. No Jackson type appears in Axiom's API.
Decoding is strict:

| Input | Code |
| --- | --- |
| Unknown property | `unknown_field` (field: path of the enclosing object, if any) |
| Duplicate key in any object, at any depth | `duplicate_field` |
| Content after the value | `trailing_content` |
| Wrong type, string for a number, number or boolean for a string, malformed date or UUID, unknown enum constant, an integer too large for its field, float for an integer, null or missing primitive, a number too large for a `double` or `float` field (for example `1e400`, which would otherwise become infinity) | `type_mismatch` (field: property path) |
| Record constructor rejects the values | `invalid_value` (field: property path) |
| Syntax error at any depth, comments, single quotes | `malformed_json` |
| Nesting deeper than 64, strings over 1 Mi characters, names over 1024, numbers over 256 digits, documents over 64 Mi characters | `limit_exceeded` |
| Invalid UTF-8 | `invalid_encoding` |
| `null` | `null_body` |

Records are supported. Numbers decoded into `Object`, `List` or `Map` targets
become `BigDecimal` (decimals) or `Integer`/`Long`/`BigInteger`, never an infinite
double. `NaN` and `Infinity` literals are not JSON and fail as `malformed_json`.
Missing reference-type components become `null`; validate
them in the record or the handler. Field paths use declared property names and
indexes (`items[0].quantity`) and stop at the first map, because map keys are
client input. Parser messages, input fragments and unknown property names are never
reported.

Codes are chosen from exception types and document structure, never from Jackson's
message text. Trailing content is found by reading one more token after the value.

### Which problem is reported

A rejected document reports **the first problem in document order**, whatever kind it
is. The decoder stops at the first failure, so an integer overflow at `quantity`
followed later by a duplicate key is `type_mismatch` with field `quantity`, and the
same two problems in the other order are `duplicate_field`. Details that follow from
this rule:

- A constructor rejection (`invalid_value`) is positioned where its object ends, and
  `trailing_content` after the value.
- Invalid UTF-8 is positioned at the offending byte: an earlier syntax or type
  problem is reported instead of `invalid_encoding`, and an earlier valid document
  is not affected by bytes that follow a failure.
- When a failure surfaces from the parser (syntax, encoding, limits, duplicate keys),
  possibly wrapped by the data binder, the codec reads the document's tokens again
  with its own duplicate tracking, up to the position where the first failure was
  detected, and reports the first token-level problem found there. This second pass
  runs only for rejected bodies and is bounded by the same limits.

### Supported types

| Java type | JSON form | Notes |
| --- | --- | --- |
| Records, classes with a Jackson-visible constructor or setters | object | unknown properties rejected |
| `String` | string | numbers and booleans are not read as text |
| `int`, `long`, `Integer`, ... | integer | no floats, no strings, no overflow |
| `double`, `float` and boxes | number | finite values only |
| `BigDecimal`, `BigInteger` | number | exact; strings rejected; written with `BigDecimal.toString()` (may use an exponent such as `1E+3`) |
| `boolean`, `Boolean` | `true`/`false` | |
| enums | string | exact constant name; unknown names, other case, numbers and numeric strings rejected; written by name |
| `UUID` | string | canonical `8-4-4-4-12` hexadecimal form only (any case) |
| `byte[]` | string | base64; arrays of numbers rejected |
| `Optional<T>` | `T` or `null` | missing or `null` reads as `Optional.empty()`, which is written as `null` |
| `List`, `Set`, arrays | array | |
| `Map<String, T>` | object | keys are client input and never appear in errors |
| `Instant` | string | ISO-8601 with `Z` or an offset, for example `2024-02-29T10:15:30Z` |
| `LocalDate`, `LocalTime`, `LocalDateTime` | string | ISO-8601, for example `2024-02-29` |
| `OffsetDateTime`, `ZonedDateTime` | string | ISO-8601; the offset is kept as sent, not adjusted to UTC |
| `Duration`, `Period` | string | ISO-8601, for example `PT1H30M` |
| other `java.time` types | string | ISO-8601 text form |
| `Object` | any | objects become `Map`, arrays `List`, decimals `BigDecimal` |

Every `java.time` value is an ISO-8601 **string** in both directions. Numeric
timestamps (`1700000000`), array forms (`[2024,2,29]`), empty strings and
impossible dates (`2024-02-30`) fail with `type_mismatch` and the property path;
the input value and parser text are never reported. An unknown enum constant is
also a `type_mismatch` without echoing the value. `java.util.Date` and `Calendar`
are not recommended; use `java.time`. Type information is never read from input:
default typing is not enabled, so `{"@class": ...}` is an ordinary property.
`BigDecimal` and `BigInteger` values are bounded to 256 characters, but a short
literal such as `1e999999999` has a huge exponent; check scale or magnitude before
arithmetic that expands it (`toBigInteger()`, `toPlainString()`).

### Migrating from lenient JSON

The codec is stricter than a default Jackson `ObjectMapper`, and the rules are fixed:
there is no switch to relax them. Code and clients moving from a default mapper
(or from hand-written JSON that happens to work there) meet these changes. Each is
rejected with `400` and the code shown.

| Input | Default Jackson | Axiom |
| --- | --- | --- |
| `{"name": 42}` (or `true`) into a `String` field | `"42"` / `"true"` | `type_mismatch`, field `name` |
| `{"count": "7"}` or `1.5` into an `int` field | `7` / `1` | `type_mismatch`, field `count` |
| `{"status": 0}` or `"0"` for an enum | the first constant | only the exact constant name, such as `"ACTIVE"`; numbers and numeric strings are `type_mismatch` |
| A shortened or unhyphenated string for a `UUID` | accepted in several forms | only `8-4-4-4-12` hexadecimal; otherwise `type_mismatch` |
| `{"data": [1, 2, 3]}` for `byte[]` | accepted as a byte list | base64 string only |
| `{"at": 1700000000}`, `[2024,2,29]` or `""` for a `java.time` type | a timestamp, an array or `null` | ISO-8601 string only, else `type_mismatch` |
| `1.10` or `1e400` into an `Object`, `List` or `Map` | `Double` (infinity for `1e400`) | `BigDecimal`, exact; a `double` or `float` field rejects values that overflow |
| The same key twice in an object | the last value wins | `duplicate_field` |
| Content after the value | ignored | `trailing_content` |
| UTF-16 or UTF-32 bytes | detected and decoded | `invalid_encoding` or `malformed_json`; a non-UTF-8 `charset` is `415 unsupported_charset` |

Responses follow the same rules in the other direction: dates are ISO-8601 strings
with the offset kept as sent, `BigDecimal` is written with `toString()`, and an
`Optional.empty()` is `null`. The type table above states the strict rule for every
supported type. To accept a lenient shape for one field, declare it as `String` or
`Object` and convert it in the handler.

## Encoding

`ctx.json(value)` returns a response with `Content-Type: application/json`. The
value is encoded when the response is prepared, after the handler returns, on the
request's virtual thread. A `String` or `byte[]` passed to `json` is sent verbatim
as already-encoded JSON. Text and byte responses are not negotiated.

### Accept negotiation (406)

A response whose Content-Type has an installed codec is checked against the
request's `Accept` header as RFC 9110 section 12.5.1 describes. Media ranges are
parsed with their parameters (quoted strings included) and weights. For the
response's media type, the **most specific** matching range decides:
`application/json` with parameters, then `application/json`, then `application/*`,
then `*/*`; among equally specific ranges the first listed wins. A weight of
`q=0` excludes the type, so `application/json;q=0, */*` is **406** while
`*/*;q=0.1, application/json` is accepted. JSON is always UTF-8, so a range with
parameters matches only when every parameter is `charset=utf-8`. An absent, blank
or malformed Accept header (for example an invalid weight such as `q=2`, or an
unterminated quoted string) is treated as if it were not sent, never as an error.

**Limits.** At most 64 media ranges are parsed (empty list elements do not count);
a header with 65 or more is treated like a malformed one, as if absent, so the
representation is served. The bound keeps parsing cost per request constant, and
real clients stay far below it: browsers send 5 to 10 ranges (Chrome's navigation
header has 8, with `v=b3` and `q` parameters), `fetch`, curl and most libraries send
`*/*` or two or three ranges, and axios sends `application/json, text/plain, */*`.
Ignoring an unusable header rather than answering 406 follows RFC 9110's guidance
that recipients tolerate what they cannot interpret, and cannot make a working
client fail on a typo; a client that wants a 406 must send a well-formed header of
64 ranges or fewer. The boundary (64 parsed, 65 ignored) and the real-client
headers above are covered by tests.

**Only safe methods are negotiated.** The representation is known only once the handler
returns, so a 406 is decided after the handler has run. For the safe methods `GET`, `HEAD`,
`OPTIONS` and `TRACE` (RFC 9110 section 9.2.1) that is harmless: the handler changes no
state, and a client that retries gets the same answer. For every other method (`POST`, `PUT`,
`PATCH`, `DELETE` and extension methods) `Accept` is **advisory**: the handler's response is
always sent, encoded by its own codec, whatever the client accepts. This prevents a `POST`
from creating a resource and then answering 406, which would invite a retry that creates it
again. Route declarations do not list representations, so negotiating before the handler
runs is not possible without them; a handler that must refuse a client that cannot be served
checks `ctx.header("Accept")` itself and throws `NotAcceptableException` before it acts.
A value the codec cannot encode, or `json` without a JSON codec installed, is a server error
(500 over HTTP).

## Codecs

Codecs implement the experimental `BodyCodec` SPI and declare exact lowercase media
types. They are discovered once with `ServiceLoader` (thread context class loader)
during `start()`. See [errors](errors.md) for how failures reach clients.

**Registry rules.**

- A codec is found by the exact `type/subtype` it declares. There is no priority
  order, no wildcard (`application/*`, `*/*`) and no structured-suffix fallback: a
  codec for `application/json` does not serve `application/vnd.api+json`, and
  `application/problem+json` is produced by the framework itself. A codec that serves
  several types lists each one.
- Any number of codecs may be installed if their media types are disjoint, in any
  order. Two codecs declaring the same type fail `start()` with `IllegalStateException`
  naming the type and both classes, and a declaration that is not an exact lowercase
  `type/subtype` (wildcards, parameters, upper case) fails the same way; the
  application stays configurable. `ServiceLoader` order is not a contract, so a tie is
  never broken silently. To replace the JSON codec, leave `axiom-json` off the
  runtime classpath and install your own.

**Lifecycle and threading.** The runtime instantiates each codec through its public
no-argument constructor, never closes it, and keeps one instance for the application's
lifetime. That instance serves every request, concurrently and on virtual threads:
codecs must be thread-safe, hold no per-request state in fields and be immutable after
construction. The SPI Javadoc states the same contract.

The runtime decodes through `decode(ByteBuffer, Class)`, passing a read-only view of
the request body that the codec must not retain. Its default implementation copies
the bytes once and calls `decode(byte[], Class)`, so codecs written against the array
method keep working; codecs that can stream from a buffer override it to avoid the
copy, as the JSON codec does.
