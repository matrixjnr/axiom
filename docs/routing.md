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

After choosing the path, the router selects its registered HTTP method. With
`POST /users/new` and `GET /users/:id`, `GET /users/new` returns **405**, with
`Allow: POST`. It never executes the parameter route. This keeps method handling
from bypassing a more specific resource. Methods remain case-sensitive.

The `Allow` header contains only methods registered on the selected path shape,
sorted alphabetically. An unknown path returns 404. HEAD routes are explicit,
and HEAD responses suppress bodies for successful matches and routing errors.

## Conflicts and startup

Identical method/template pairs fail at registration. Distinct templates with the
same method and path shape fail when `start()` compiles the table:

```text
GET /users/:id
GET /users/:name
```

The error names both routes. Equivalent wildcard templates follow the same rule.
Static/parameter/wildcard overlaps with defined precedence are allowed.

Different methods can use different capture names on the same shape. For example,
`GET /users/:id` and `PUT /users/:name` share the path node while each handler sees
its own declared capture names. Names belong to endpoints, not shared trie edges.

Compilation is atomic with respect to lifecycle changes. On failure, the application
stays `CONFIGURING`, registrations remain intact, and execution is unavailable.
Close it and create an application with corrected routes. Successful startup freezes
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
UTF-8, segment by segment, and throws `IllegalArgumentException` for malformed UTF-8
or for a segment that would decode to a `/`, backslash, NUL, `.` or `..`.
`ctx.pathParameters()` returns an immutable map of raw captures in declaration order.
Capture strings and maps are created when requested and belong to that request's
context. Static matches need no capture-boundary arrays or per-request parameter maps.

All captures are untrusted client input. A wildcard remainder spans several
segments and contains `/`; the rules above keep `..` and encoded separators out of
it, but resolving it against a file system still requires the application's own
containment check (for example, normalizing a `Path` and verifying its prefix).
Request size limits and typed parameter conversion are separate work.

## Implementation and verification

Startup builds an immutable segment trie and a lookup table for fully static paths.
Dynamic matching uses an explicit stack to try branches in precedence order. Both
compilation and lookup avoid recursive calls, including for deeply nested paths.
Backtracking can visit multiple branches; no constant-time or strict linear-time
bound is claimed for adversarial overlapping templates.

Tests cover raw and rejected paths, conflicts, method mismatches, HEAD, deep paths,
10,000 routes, and concurrent captures. An independent exhaustive template scanner
checks 480 method/path combinations to catch differences in matching and precedence,
and confirms that the generated paths with empty segments are rejected.

The [JMH harness](../benchmarks/http/README.md) exercises the public in-memory
dispatcher. No timing threshold is enforced by CI.
