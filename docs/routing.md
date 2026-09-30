# Routing rules

## Templates

| Template | Matches | Captures |
| --- | --- | --- |
| `/users/me` | `/users/me` | none |
| `/users/:id` | `/users/42` | `id = "42"` |
| `/teams/:team/users/:user` | `/teams/a/users/b` | `team = "a"`, `user = "b"` |
| `/files/*path` | `/files/a/b.txt` | `path = "a/b.txt"` |
| `/files/*path` | `/files/` | `path = ""` |
| `/files/*path` | `/files//a` | `path = "/a"` |
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

No decoding, case folding, slash merging, dot-segment removal, or redirects occur.
`/users` and `/users/` are distinct. `%2F` remains part of one raw segment;
`ctx.path("id")` for `/users/a%2Fb` returns `a%2Fb`. `%2F` and `%2f` are distinct
literal spellings. Repeated slashes and Unicode are preserved.

`ctx.path()` returns the request path. `ctx.route()` returns the stable route
identity with its template. `ctx.path(name)` rejects undeclared names.
`ctx.pathParameters()` returns an immutable map in declaration order. Capture
strings and maps are created when requested and belong to that request's context.
Static matches need no capture-boundary arrays or per-request parameter maps.

These are in-memory matching rules. A future network adapter must define and test
its ingress validation policy consistently with routing and security. Request size
limits, percent-decoding, and typed parameter conversion are separate work.

## Implementation and verification

Startup builds an immutable segment trie and a lookup table for fully static paths.
Dynamic matching uses an explicit stack to try branches in precedence order. Both
compilation and lookup avoid recursive calls, including for deeply nested paths.
Backtracking can visit multiple branches; no constant-time or strict linear-time
bound is claimed for adversarial overlapping templates.

Tests cover raw paths, conflicts, method mismatches, HEAD, deep paths, 10,000 routes,
and concurrent captures. An independent exhaustive template scanner checks 1,020
method/path combinations to catch differences in matching and precedence.

The [JMH harness](../benchmarks/http/README.md) exercises the public in-memory
dispatcher. No timing threshold is enforced by CI.
