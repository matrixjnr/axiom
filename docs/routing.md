---
title: Routing
parent: Guides
nav_order: 1
---

# Routing rules

## Templates

| Template | Matches | Captures |
| --- | --- | --- |
| `/users/me` | `/users/me` | none |
| `/users/:id` | `/users/42` | `id = "42"` |
| `/teams/:team/users/:user` | `/teams/a/users/b` | `team = "a"`, `user = "b"` |
| `/files/*path` | `/files/a/b.txt` | `path = "a/b.txt"` |
| `/files/*path` | `/files/` | `path = ""` |
| `/*path` | `/` | `path = ""` |

Parameters occupy a whole segment, match a non-empty value, and do not consume `/`.
Wildcards occupy the final segment and may consume an empty remainder, but require
the preceding slash: `/files/*path` does not match `/files`.
Capture names use `[A-Za-z_][A-Za-z0-9_]*` and must be unique within a template.
Unnamed captures, malformed capture names, repeated names, and non-terminal
wildcards are rejected during registration. Literal colons or stars inside a
static segment (for example `/time/12:00`) retain their ordinary meaning.

## Precedence and method selection

The router chooses the most specific **complete path match**, independent of
registration order. At the first differing segment:

1. Static segment.
2. Named parameter.
3. Terminal wildcard.

A more specific branch that cannot match the complete path is skipped. For example,
with `/a/static/dead` and `/a/:id/end`, `/a/static/end` matches the parameter route.
With `/a/*rest` and `/:first/static`, `/a/static` matches `/a/*rest` because the
literal `a` at the first segment has higher precedence.

The method takes part in selection. The router visits complete path matches in
the precedence order above and executes the first one registered for the request
method. A more specific template without that method does not hide a less specific
one that has it: with `GET /users/me` and `POST /users/:id`, `POST /users/me` runs
the parameter route with `id = "me"`, and `GET /users/me` runs the static route.
Methods remain case-sensitive.

When no complete match is registered for the method, the response is **405**. Its
`Allow` header is the union of the methods registered on every template that
matches the complete path, plus `HEAD` wherever `GET` is and always `OPTIONS` (every
routed path answers it, with a route or [automatically](#automatic-options)), sorted
alphabetically: with only `POST /submit`, `PUT /submit` gets `Allow: OPTIONS, POST`. An unknown path returns 404, or
501 for a method the application does not recognize (see
[custom methods](#custom-methods)). All use `application/problem+json` bodies (see
[errors](errors.md)). OPTIONS is the
exception: when no matching template registered it, the application answers it
itself (see [automatic OPTIONS](#automatic-options)).

HEAD is served by an explicit HEAD route or, failing that, by the GET route on the
same template, checked template by template in precedence order. With
`GET /items/static` and `HEAD /items/:id`, `HEAD /items/static` runs the static GET
route. `ctx.method()` still reports `HEAD`, `resolve` returns the GET route (whose
admission policy applies), and `Allow` lists `HEAD` wherever `GET` is registered.
HEAD responses suppress bodies for successful matches and routing errors. A
successful HEAD response (2xx other than 204 and 205) keeps the representation length
instead: `app.handle` and `TestClient` return it with `Content-Length` set to the
body's encoded length, and the listener sends that header with no body bytes.

## Methods

This table is the reference for every method. "Routed" means at least one template
matches the complete path; "Allow" is the union of the methods registered on all
templates that match it, plus `HEAD` wherever `GET` is registered and `OPTIONS`, sorted
alphabetically.

| Method | Registration | Routed, a matching template has the method | Routed, no matching template has it | Not routed |
| --- | --- | --- | --- | --- |
| `GET`, `POST`, `PUT`, `PATCH`, `DELETE` | `get`, `post`, `put`, `patch`, `delete` or `route` | Handler runs | 405, `Allow` | 404 |
| `HEAD` | `head` or `route`; otherwise the `GET` route of the same template serves it | Handler runs; no body bytes, `Content-Length` of the representation | 405, `Allow`; no body | 404; no body |
| `OPTIONS` | `options` or `route` | Handler runs | 204, `Allow` plus `OPTIONS` ([automatic](#automatic-options)) | 404 |
| `OPTIONS *` | Not possible (`*` is not a template) | 204, `Allow` = every registered method, `HEAD` if `GET` is registered, `OPTIONS` ([details](#options-)) | | |
| `TRACE` | Refused: `IllegalArgumentException` ([why](#trace)) | | 405, `Allow` | 404 |
| `CONNECT` | Refused: `IllegalArgumentException` | | 501 ([why](#connect)); the listener closes the connection | 501, likewise |
| Extension method (`PROPFIND`, `REPORT`, `QUERY`, ...) | `route` | Handler runs | 405, `Allow` | 404 if registered on any route or declared with `recognizeMethods`, else 501 ([custom methods](#custom-methods)) |
| Any other token, including `get` and `Get` | `route` (then it is an extension method) | | 405, `Allow` | 501 |
| Not a token (`G(T`, `G T`, empty) | Refused: `IllegalArgumentException` | 400 from the listener, which closes the connection; `new Request` throws | | |

`Allow` on a 405 lists the registered methods, `HEAD` for `GET` and `OPTIONS` (which
every routed path answers, RFC 9110 section 15.5.6), and is the same list the
automatic OPTIONS answer sends. It never lists `TRACE` or `CONNECT`. Every error in the table is an
`application/problem+json` response (see [errors](errors.md)); 400 and the listener's
501 for CONNECT close the connection, the others keep it open. Request bodies are
accepted and limited the same way for every method (see
[request bodies](bodies.md#limits)), and method-override headers are
[ignored](#method-override-headers). `app.handle`, `TestClient` and the listener give
the same answers, except that a request the listener rejects with 400 cannot be built
in memory.

A method is an RFC 9110 token: one or more ASCII letters, digits and the characters
``!#$%&'*+-.^_`|~``.
`app.route(method, path, handler)` throws `IllegalArgumentException` ("Invalid HTTP
method") for anything else, including an empty method, spaces, separators such as
`(`, `/` or `:`, control characters and non-ASCII letters. `new Request(...)` applies
the same rule, so a listener answers a request line whose method is not a token with
400 and closes the connection without routing it, and `TestClient` callers get the
`IllegalArgumentException` when they build the request.

Methods are matched exactly. Nothing is upper-cased: `get` is a method distinct from
`GET`, may be registered on its own, and a `get` request never runs a `GET` route.

### Automatic OPTIONS

An OPTIONS request for a path with at least one complete match, none of which has an
OPTIONS route, is answered **204 No Content** with an `Allow` header and no body. No
handler runs. `Allow` is the 405 union above (every method registered on any template
matching the path, plus `HEAD` wherever `GET` is) with `OPTIONS` added, sorted
alphabetically: with `GET /users` and `POST /users`, `OPTIONS /users` gets
`Allow: GET, HEAD, OPTIONS, POST`.

An explicit OPTIONS route is selected like any other method, so it wins on its own
template and also serves paths whose more specific templates lack OPTIONS: with
`GET /users/me` and `OPTIONS /users/:id`, `OPTIONS /users/me` runs the OPTIONS route.
An unknown path is still 404.

A wildcard OPTIONS route such as `/*any` (a CORS preflight handler, for example)
serves every path, so it would hide the accurate `Allow` list of paths whose other
templates have different methods. The precedence rule stays as it is; instead the
route's handler returns `ctx.automaticOptions()` for the requests it does not handle
itself:

```java
app.options("/*any", ctx -> ctx.header("Access-Control-Request-Method").isPresent()
        ? preflight(ctx) : ctx.automaticOptions());
```

`automaticOptions()` is the same 204 answer with the same `Allow` list as above: the
union over every template matching the path, including the OPTIONS route that is
running, so `Allow` lists `OPTIONS` even where it is the only method. With
`GET /users` and `POST /users` beside the wildcard, `OPTIONS /users` gets
`Allow: GET, HEAD, OPTIONS, POST` and `OPTIONS /other` gets `Allow: OPTIONS`.
`OPTIONS *` never reaches a route.

`resolve` returns empty for an automatic answer, so
over HTTP and through `TestClient` it is admitted in the default bucket shared with
404 and 405 responses, under the application's default policy (see
[admission](admission.md)); it can be refused with 503 like any request. A request
body is read and limited as for any method (413 over `maxRequestBody`) and then
discarded. CORS preflight handling is not part of this answer.

### OPTIONS *

`OPTIONS *` (the asterisk-form request target, RFC 9110 section 9.3.7) asks about
the server as a whole. It is answered **204** with an `Allow` header listing every
method registered on any route, `HEAD` when `GET` is registered anywhere, and
`OPTIONS`, sorted alphabetically (`Allow: OPTIONS` for an application without
routes). No route is looked up and no handler runs, not even an OPTIONS route that
matches every path; `resolve` returns empty and admission uses the default bucket, as
for automatic OPTIONS. `*` is not a path and cannot be a route template.

`Request` accepts the target `*` only with the method `OPTIONS` and no query
(`Request.fromTarget("OPTIONS", "*")` or `new Request("OPTIONS", "*")`); its `path()`
is then `"*"`. Any other method with `*`, `*?query`, `**` or `*/a` throws
`InvalidRequestPathException`, which a listener answers with 400.

### Absolute-form targets

The listener accepts absolute-form targets (`GET http://host/path?q=1`), as an origin
server must (RFC 9112 section 3.2.2), for every method. The scheme and authority are
checked and dropped; routing, `ctx.path()` and `ctx.query` see only the path and query,
which pass the same [path rules](#raw-paths-and-ownership) as an origin-form target. The
checks are strict, so a request cannot be routed on one host and described by another:

- the scheme is `http` or `https`, in any case, and is otherwise ignored (it does not
  have to match the listener's TLS setting);
- the authority is a valid host with an optional port up to 65535 and no user information
  (`http://user@host/` is 400);
- the `Host` header, which HTTP/1.1 requires exactly once, must equal the authority,
  ignoring case and without normalizing default ports (`http://a:80/` with `Host: a` is
  400); an HTTP/1.0 request without `Host` is accepted on the authority alone;
- an empty path becomes `/`, so `OPTIONS http://host` asks about the root and is not
  the asterisk form;
- a fragment, another scheme, or an unsafe path (dot segments, `//`, encoded separators)
  is 400, and the connection closes as for any rejected target.

`Request.fromTarget` still rejects absolute-form targets; the listener reduces them
before calling it, and the test client takes paths, not targets.

### TRACE

TRACE routes cannot be registered: `app.route("TRACE", ...)` throws
`IllegalArgumentException`. A TRACE response echoes the request message, which
includes cookies and `Authorization` headers; together with a script that can send
TRACE, that leaks credentials that are otherwise hidden from scripts (cross-site
tracing), and an application has no safe use for the echo. A TRACE request is routed
like any other method that no route has: **405** with `Allow` for a path that has
routes and 404 otherwise, each with the usual problem body that contains no request
data. `Allow` never lists TRACE. Methods are case-sensitive, so `trace` is an
ordinary extension method and may be registered.

### CONNECT

CONNECT asks the server to open a tunnel to another host (RFC 9110 section 9.3.6),
which Axiom does not support for any target. CONNECT routes cannot be registered
(`IllegalArgumentException`), and every CONNECT request is answered **501 Not
Implemented** without routing, whatever its target. 501 rather than 405 because 405
states that the method is known but not allowed for this resource and requires an
`Allow` list for it, while a CONNECT target is usually an authority (`host:443`), not
a resource of the application. The listener rejects CONNECT as soon as its head
arrives and closes the connection after the response: bytes after a CONNECT head may
already be tunnel data, so they are discarded rather than parsed as further
requests. `app.handle` and `TestClient` also answer 501.

### Custom methods

Any other token can be registered with `app.route(method, path, handler)`, for
example WebDAV's `PROPFIND` and `REPORT` or `QUERY` (a safe method with a request
body, still an IETF draft). They are matched exactly like the built-in methods and
their bodies are read and limited as for any method. There is no `query(...)`
shortcut while the `QUERY` specification is not final; use
`app.route("QUERY", path, handler)`.

A method is **recognized** when it is one of the RFC 9110 methods (`GET`, `HEAD`,
`POST`, `PUT`, `DELETE`, `CONNECT`, `OPTIONS`, `TRACE`), `PATCH`, a method
registered on any route of the application, or a method declared with
`app.recognizeMethods(...)`. A request whose path matches no template
is answered 404 when its method is recognized and **501 Not Implemented** otherwise
(RFC 9110 section 15.6.2: the server does not support the method for any resource).
A request whose path does match is answered 405 with `Allow` whenever no matching
template has its method, recognized or not. With `PROPFIND /dav` registered:

| Request | Status |
| --- | --- |
| `PROPFIND /dav` | handler runs |
| `FOO /dav`, `get /dav` | 405, `Allow: PROPFIND` |
| `PROPFIND /missing`, `GET /missing` | 404 |
| `FOO /missing`, `get /missing` | 501 |

`app.recognizeMethods("MKCOL", "LOCK")` declares methods that no route has but the
application still treats as its own, for example because global middleware or a
gateway extension answers them: they get 404 rather than 501 on an unrouted path, so
`MKCOL /missing` is 404 and `FOO /missing` stays 501. Declaring is configuration
only: it must happen before `start()`, repeated calls add to the set, each method
must be a token (`IllegalArgumentException` otherwise; `CONNECT` is refused because
it is always 501), and a declared method is not advertised anywhere, so it never
appears in `Allow` or in the `OPTIONS *` list, and a request for it on a routed path
is 405 as before. Matching is case-sensitive: declaring `MKCOL` does not recognize
`mkcol`.

The 501 is a runtime error: over HTTP it keeps a keep-alive connection open, and its
problem body does not repeat the method.

### Method override headers

`X-HTTP-Method-Override`, `X-HTTP-Method` and `X-Method-Override` are not supported.
Routing, `resolve`, admission, automatic OPTIONS and `ctx.method()` always use the
method of the request line; these headers reach handlers as ordinary request headers
and change nothing. Honoring them would let any client that can send a POST, including
a cross-site form, reach a DELETE or PUT route, and would make the method that
proxies, logs and access rules see differ from the one the application executes. An
application that must serve clients limited to GET and POST can register an explicit
POST route that performs the action.

### Customising router answers

The 404, 405 and 501 answers the router produces itself can be replaced with
dedicated handlers, without middleware:

```java
app.notFound(ctx -> ctx.json("{\"error\":\"no such resource\"}"));
app.methodNotAllowed(ctx -> ctx.text("try another method"));
app.notImplemented(ctx -> ctx.text("unsupported method " + ctx.method()));
```

| Hook | Replaces | Status preset on the context |
| --- | --- | --- |
| `notFound` | 404: no template matches the path and the method is [recognized](#custom-methods) | 404 |
| `methodNotAllowed` | 405: a template matches the path but none has the method | 405 |
| `notImplemented` | 501: no template matches the path and the method is not recognized | 501 |

A hook is an ordinary handler. It runs as the innermost step of the global
[middleware](middleware.md) chain, which therefore still wraps the answer (as it does
the built-in ones), and group and route middleware do not run. The context's status is
already the hook's status, so `ctx.json(body)`, `ctx.text(text)` and a returned
`null` answer 404, 405 or 501; return a `Response` to choose another status. No route
matched: `ctx.route()` throws and `ctx.matchedRoute()` is empty; use `ctx.path()`,
`ctx.method()` and `ctx.execution().requestId()` for what the default problem body
would carry. A custom body is a normal response, so it is subject to `Accept`
negotiation (406) like any handler response, and nothing is escaped or restricted for
you: never echo request data into it unfiltered. Exceptions thrown by a hook are
mapped like those of any handler, by [error handlers](errors.md#error-handlers) or
into a problem response; throwing an `AxiomException` is the way to keep the problem
format with a different code.

For 405 the router sets `Allow` on the hook's response whatever the hook set, so a
custom answer cannot omit or contradict the list (see [Methods](#methods)). Over HEAD
the body is suppressed as always. Not customisable by these hooks: the automatic
[OPTIONS](#automatic-options) answer and `OPTIONS *` (use an explicit OPTIONS route
that returns `ctx.automaticOptions()` for what it does not handle), and answers
produced before routing: 400, 413 and CONNECT's 501. Each hook is set at most once
before `start()`; a second call replaces the first.

## Conflicts and startup

Identical method/template pairs fail at registration. So do distinct templates
with the same method and path shape, meaning they differ only in capture names:

```text
GET /users/:id
GET /users/:name   -> IllegalArgumentException: Ambiguous routes for GET: /users/:id and /users/:name
```

The error names both routes, and the first registration stays in place.
Equivalent wildcard templates (`/files/*path`, `/files/*rest`) follow the same rule.
Static/parameter/wildcard overlaps with defined precedence are allowed.

Different methods can use different capture names on the same shape. For example,
`GET /users/:id` and `PUT /users/:name` share the path node while each handler sees
its own declared capture names. Names belong to endpoints, not shared trie edges.

Compilation is atomic with respect to lifecycle changes. Should it fail, the
application stays `CONFIGURING`, registrations remain intact, and execution is
unavailable. Successful startup freezes
the table. Shutdown preserves the existing rule: accepted requests may finish.

## Raw paths and ownership

Paths that another component could resolve to a different resource are rejected,
not normalized. `new Request(...)` throws `InvalidRequestPathException` (an
`IllegalArgumentException`) for:

- an empty segment such as `//users` or `/a//b` (one trailing slash is allowed);
- a `.` or `..` segment;
- a backslash, NUL, space, or other character outside RFC 3986 path syntax
  (non-ASCII characters other than controls and spaces are allowed);
- a malformed percent-escape such as `%zz` or a trailing `%`;
- an encoded dot, slash, backslash, or NUL (`%2E`, `%2F`, `%5C`, `%00`, either case),
  anywhere in the path.

HTTP listeners answer these requests with 400 without routing them. Route templates
follow the same rules, so `//:id` and `/a%2F:id` fail at registration.

Accepted paths are matched verbatim. No decoding, case folding, or redirects occur.
`/users` and `/users/` are distinct, `%20` and a raw space are different spellings
(the latter is rejected), and `/a%20b` does not match `/a%2520b`. Unicode is preserved.

### Path policy

This is the long-term contract, not a stopgap, and it will not be relaxed by a
configuration switch. A path that two components could read as different resources
(`//`, dot segments, a backslash, NUL, encoded separators) is the raw material of
traversal, authorization-bypass and cache-poisoning attacks, so Axiom rejects it with 400
before routing instead of guessing which reading the client meant. Matching is exact for
the same reason: a router that folds case, trims slashes or redirects makes the set of
URLs that reach a handler larger than the set the application's authorization rules and
cache keys were written for. Routes that want more say so explicitly (see the migration
notes below), which keeps each leniency visible in the code that owns it.

Migrating from a router that normalized paths (or from an earlier Axiom build whose
wildcard saw the raw remainder):

| Previously | Now | What to do |
| --- | --- | --- |
| `/files//a`, `/a/./b`, `/a/../b` reached a wildcard with the remainder as sent | 400 before routing | Fix the client or proxy to send the canonical path. Never "normalize and continue" on the server: a wildcard remainder never contains an empty or dot segment, so containment checks on `ctx.pathDecoded("path")` can rely on that. |
| `%2F`, `%5C`, `%00`, `%2E` inside a segment decoded to `/`, `\`, NUL or `.` in the capture | 400 before routing | Use a query parameter or a request body for values that may contain these characters, and encode `/` inside a value in the body rather than the path. |
| `/users/` served by the `/users` handler | 404 (the paths are distinct) | Register both templates with one handler, for example `app.get("/users", h); app.get("/users/", h);`, or redirect explicitly from one to the other. |
| `/Users` served by the `/users` handler | 404 (matching is case-sensitive) | Register the other spelling, or fix the link. |
| Automatic redirect from `/users` to `/users/` or the reverse | None | Add the redirect on purpose: `app.get("/users/", ctx -> ctx.redirect(301, "/users"))`. |
| A wildcard `*path` receiving `""` for a bare directory | Unchanged: `/files/` captures `""`, while `/files` does not match | Register `/files` too if it must be served. |

Absolute-form targets, which carry the host in the request line, are accepted but reduced to their
path first (see [absolute-form targets](#absolute-form-targets)); the same policy applies to
the path they carry. The policy is covered by `PathPolicyTest` for matching and by
`HttpPathDecodingTest` and `HttpMethodsTest` for what listeners answer.

`ctx.path()` returns the request path. `ctx.route()` returns the stable route
identity with its template. `ctx.path(name)` returns the raw capture and rejects
undeclared names. `ctx.pathDecoded(name)` percent-decodes a capture once as strict
UTF-8, segment by segment. Malformed UTF-8 (for example `/users/%FF`) or a segment
that would decode to a `/`, backslash, NUL, `.` or `..` throws
`BadRequestException` with code `invalid_path_encoding`, so the client receives a 400
problem response that does not echo the capture; an undeclared name is still an
`IllegalArgumentException` (a 500 over HTTP, because it is a handler bug).
`Request` already rejects every path that could produce such a segment, so through
an application only the UTF-8 check can fail; the other checks stay in the decoder
deliberately, as defence in depth for `Context` implementations whose captures do
not come from a validated `Request`, and are tested directly.
`ctx.pathParameters()` returns an immutable map of raw captures in declaration order.
Captures are extracted into that map once, when a dynamic route matches; the match
holds no lazily initialized state. Fully static matches allocate no capture
boundaries or parameter maps. The `Context` itself is thread-confined to the
handler invocation; the captured values and map may be passed to other threads.

All captures are untrusted client input. A wildcard remainder spans several
segments and contains `/`; the rules above keep `..` and encoded separators out of
it, but resolving it against a file system still requires the application's own
containment check (for example, normalizing a `Path` and verifying its prefix).
Typed conversion is in [typed parameters](#typed-parameters); request body limits are
described in [request bodies](bodies.md).

## Query parameters

The query is everything after the first `?` of the request target, without the
`?`. It never takes part in routing. `Request.fromTarget` (used by the HTTP
listener and `TestClient`) keeps it raw as `request.query()`; an absent and an
empty query are both `""`, and the plain constructors create requests without one.
A request is rejected with `IllegalArgumentException` (400 over HTTP, before
routing) when its query

- contains a character outside RFC 3986 query syntax (`pchar`, `/` and `?`; non-ASCII
  characters other than controls and spaces are allowed), such as a space, `#`,
  backslash or control character;
- contains a malformed percent-escape such as `%zz` or a trailing `%`;
- has a name or value that does not decode to well-formed UTF-8 (including overlong
  forms, encoded surrogates and unpaired surrogates);
- is longer than `Request.MAX_QUERY_LENGTH` (4096) characters or has more than
  `Request.MAX_QUERY_PARAMETERS` (256) parameters.

Over HTTP the 4 KiB request-line limit (414) is reached before the length limit;
the length limit bounds requests built in memory. Rejection messages and error
responses never contain the query, which may carry credentials, and
`Request.toString()` omits it.

The query is a list of `&`-separated `name=value` pairs. Empty pairs (`a=1&&b=2`,
a trailing `&`) are ignored and not counted. A pair splits at its first `=`, so
`a=b=c` has the value `b=c`, and a pair without `=` has the value `""`. Names and
values are percent-decoded once as UTF-8: `%2541` is `%41`, not `A`. **`+` decodes
to a space**, following the form encoding that HTML forms and `URLSearchParams`
produce; send `%2B` for a literal plus. Names match exactly after decoding and are
case-sensitive.

`ctx.query(name)` returns the first value, `ctx.queryAll(name)` an immutable list
of every value in order (empty when absent); `Request` has the same methods. Values
are decoded on each call rather than cached, which the limits keep cheap. Typed
conversion is described next.

## Typed parameters

Query parameters and path captures convert to numbers and UUIDs with one small set of
accessors on `Context`:

| Source | `int` | `long` | `UUID` |
| --- | --- | --- | --- |
| Query parameter (first value) | `Optional<Integer> queryInt(name)` | `Optional<Long> queryLong(name)` | `Optional<UUID> queryUuid(name)` |
| Path capture | `int pathInt(name)` | `long pathLong(name)` | `UUID pathUuid(name)` |

```java
int page = ctx.queryInt("page").orElse(1);
UUID id = ctx.pathUuid("id");
```

An absent query parameter is an empty `Optional`; a path capture is always present
for a declared name, and an undeclared name is the same `IllegalArgumentException` as
for `ctx.path(name)` (a handler bug, 500 over HTTP). A repeated query parameter uses
its first value; read `ctx.queryAll(name)` to convert the others yourself.

Parsing is strict. Integers are an optional leading `-` and one to 19 ASCII digits
within the type's range; leading zeros are accepted. A leading `+`, spaces, digit
separators, exponents, hexadecimal, non-ASCII digits and out-of-range values are
rejected, and so is an empty value (`?page=`). A UUID is the canonical 36-character
`8-4-4-4-12` form with hexadecimal digits in either case; the lenient forms that
`UUID.fromString` takes (`1-1-1-1-1`) are rejected. A path capture is converted raw,
without percent-decoding, so `%31` is not the number 1.

A value that does not convert is the client's error: the accessor throws
`BadRequestException` and the client receives a **400** problem response (see
[errors](errors.md)) with code `invalid_query_parameter` or `invalid_path_parameter`.
The exception message and the response contain neither the value nor the parameter
name, and no `NumberFormatException` or parser output ever reaches the caller. Other
types (booleans, enums, dates) are left to the application; throw a
`BadRequestException` with your own code for a value it cannot accept.

## Implementation and verification

Startup builds an immutable segment trie and a lookup table for fully static paths.
Dynamic matching uses an explicit stack to try branches in precedence order. Both
compilation and lookup avoid recursive calls, including for deeply nested paths.
Backtracking can visit multiple branches; no constant-time or strict linear-time
bound is claimed for adversarial overlapping templates.

Tests cover raw and rejected paths, conflicts, method fallback and mismatches, HEAD, deep paths,
10,000 routes, and concurrent captures. An independent exhaustive template scanner
checks 1,600 method/path combinations to catch differences in matching and precedence:
ten methods (`GET`, `HEAD`, `POST`, `DELETE`, `OPTIONS`, `TRACE`, `CONNECT`, the
registered extension method `PROPFIND`, the unrecognized `FOO` and a lowercase `get`)
against every generated path, including explicit and automatic OPTIONS, 404 versus
501, 405 `Allow` contents and `OPTIONS *`. It also confirms that the generated paths
with empty segments are rejected.

The [JMH harness](../benchmarks/http/README.md) exercises the public in-memory
dispatcher. No timing threshold is enforced by CI.
