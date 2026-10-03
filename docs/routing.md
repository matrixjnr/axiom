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
matches the complete path, sorted alphabetically. An unknown path returns 404. Both
use `application/problem+json` bodies (see [errors](errors.md)). OPTIONS is the
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
An unknown path is still 404. `resolve` returns empty for an automatic answer, so
over HTTP and through `TestClient` it is admitted in the default bucket shared with
404 and 405 responses, under the application's default policy (see
[admission](admission.md)); it can be refused with 503 like any request. A request
body is read and limited as for any method (413 over `maxRequestBody`) and then
discarded. CORS preflight handling is not part of this answer.

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
Typed parameter conversion is separate work; request body limits are described in
[request bodies](bodies.md).

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
conversion is left to the application, as for path captures.

## Implementation and verification

Startup builds an immutable segment trie and a lookup table for fully static paths.
Dynamic matching uses an explicit stack to try branches in precedence order. Both
compilation and lookup avoid recursive calls, including for deeply nested paths.
Backtracking can visit multiple branches; no constant-time or strict linear-time
bound is claimed for adversarial overlapping templates.

Tests cover raw and rejected paths, conflicts, method fallback and mismatches, HEAD, deep paths,
10,000 routes, and concurrent captures. An independent exhaustive template scanner
checks 640 method/path combinations (including HEAD) to catch differences in matching and precedence,
and confirms that the generated paths with empty segments are rejected.

The [JMH harness](../benchmarks/http/README.md) exercises the public in-memory
dispatcher. No timing threshold is enforced by CI.
