# Security

Two opt-in modules add authentication and HTTP security helpers. Both use only the JDK and
the Axiom API; versions come from the BOM.

| Module | Package | Contents |
| --- | --- | --- |
| `axiom-security` | `com.jsgalactic.axiom.security` | `Authenticator`, policies (`Security`), `TrustedProxies`, `HeaderRedaction`, `SecurityHeaders`, `Cors` |
| `axiom-security-jwt` | `com.jsgalactic.axiom.security.jwt` | `JwtAuthenticator`: strict JWT bearer tokens (HMAC, RSA, RSA-PSS, ECDSA, EdDSA) |

Core contributes the identity itself: `SecurityIdentity` and two `Context` methods, so
handlers read the caller without depending on a security module.

```java
var jwt = JwtAuthenticator.builder()
        .publicKey("2026-10", JwsAlgorithm.RS256, issuerKey)
        .issuer("https://login.example.com")
        .audience("notes-api")
        .build();
var security = Security.of(jwt);

app.use(SecurityHeaders.defaults());
app.use(security.authenticate());                        // identity if credentials are sent
app.get("/me", ctx -> ctx.identity().orElseThrow().principal(), security.authenticated());
app.group("/admin", admin -> {
    admin.use(security.hasRole("admin"));               // 401 anonymous, 403 without the role
    admin.delete("/notes/:id", deleteNote, security.hasPermission("notes:delete"));
});
```

## Identity

`SecurityIdentity(principal, roles, permissions, attributes)` is an immutable record (core,
`com.jsgalactic.axiom.context`). The sets and the attribute map are unmodifiable copies;
names are 1 to 256 characters without control characters and compare exactly. Attributes are
further verified facts as text (a tenant, an e-mail address): at most 64, values up to 2,048
characters without control characters, read with `identity.attribute(name)`; no policy
reads them. The three-argument constructor creates an identity without attributes.
`toString()` shows the principal and the counts, not the grants or attributes.

| Method | Meaning |
| --- | --- |
| `ctx.identity()` | `Optional<SecurityIdentity>`; empty means anonymous |
| `ctx.identity(identity)` | Attaches the verified identity; **at most once per request** (`IllegalStateException` otherwise) |

Lifecycle and ownership: the identity lives in the request's context. It is created for
the request, never shared with another request, visible to every middleware and handler
that runs after it was set and to error handlers, and gone when the request ends. Set-once
means code later in the chain (a route middleware, the handler) cannot replace a verified
identity, for example to escalate privileges. The default methods on `Context` keep
application test doubles compiling: they report anonymous and refuse identities.

## Authenticators

```java
public interface Authenticator {
    Optional<SecurityIdentity> authenticate(Request request);
    String challenge();          // WWW-Authenticate for 401 without credentials
}
```

| Request | Authenticator | Answer |
| --- | --- | --- |
| No credentials for this scheme | returns empty | anonymous; a policy needing an identity answers 401 `unauthorized` with `challenge()` |
| Valid credentials | returns the identity | continues |
| Invalid credentials | throws `UnauthorizedException(challenge, code)` | 401 problem+json with that `WWW-Authenticate` |
| Any other failure | throws | like a handler exception (500 over HTTP) |

An authenticator is shared by every request and must be thread-safe. It never puts the
credential into a code or challenge; diagnostic detail goes into the exception's cause,
which only logs see.

## Policies

`Security.of(authenticator)` validates the challenge once and returns middleware:

| Middleware | Without identity | Identity without the grant |
| --- | --- | --- |
| `authenticate()` | continues anonymously | - |
| `authenticated()` | 401 `unauthorized` | - |
| `hasRole(role)` | 401 | 403 `forbidden` |
| `hasAnyRole(roles...)` | 401 | 403 when none matches |
| `hasPermission(permission)` | 401 | 403 |

- Every policy first uses an identity already in the context, otherwise runs the
  authenticator and attaches the result; stacked policies authenticate once per request.
- Invalid credentials are 401 even under `authenticate()`: a broken token is reported,
  not silently treated as anonymous.
- A 403 never names the missing role or permission.
- 401 and 403 are ordinary `AxiomException`s (`UnauthorizedException`,
  `ForbiddenException`): `application/problem+json` with status, code and request ID,
  mappable with error handlers ([errors](errors.md)).
- Policies protect the scope they are registered on: global, group or route. Group
  middleware never run for 404/405, so a group's policies cannot answer for paths it does
  not own ([middleware](middleware.md#rejected-alternatives)). Global policies also wrap
  router answers, so an anonymous request to an unknown path receives 401 rather than
  404 when the policy is global.

## JWT bearer tokens

`JwtAuthenticator` reads `Authorization: Bearer <token>`. No header, or another scheme,
is "no credentials". Every rejection is **401, code `invalid_token`,
`WWW-Authenticate: Bearer realm="api", error="invalid_token"`**, whatever the reason, so
clients cannot probe which check failed; the reason is the exception's cause for logs.

Checks, in order:

1. Length at most `maxTokenLength` (8 KiB by default, 256 to 65,536), before decoding.
2. Exactly three non-empty parts of unpadded, canonical base64url (non-zero unused bits
   are rejected, so a token has one encoding). JWE (five parts) is rejected.
3. The header is strict JSON: well-formed UTF-8, no duplicate member names (which defeats
   parser differentials such as `{"alg":"HS256","alg":"none"}`), nesting at most 16.
4. `alg` must be in the allow-list, which is exactly the set of algorithms keys were
   registered for. `none` is not an algorithm Axiom knows; names are case-sensitive. A
   `crit` or `enc` member is rejected; `typ`, if present, is `JWT` or `at+jwt`.
   Header-embedded keys (`jwk`, `jku`, `x5u`, `x5c`) are never used or fetched.
5. Key selection: by `kid` when present, otherwise the key registered without a key ID for
   the token's algorithm. The key must be registered for exactly that algorithm, so a
   public key can never be used as an HMAC secret (algorithm confusion).
6. Signature: HMAC tags compared in constant time; RSA PKCS#1 v1.5; RSASSA-PSS with the
   hash, MGF1 hash and salt length RFC 7518 fixes for the algorithm; ECDSA in the JWS
   fixed-length format (DER signatures are rejected); Ed25519 with exactly 64 signature
   bytes.
7. Claims (only parsed after the signature verified): `exp` required; `exp`, `nbf`, `iat`
   are integer NumericDates checked with the clock skew (30 s by default, 0 to 5 min);
   `iss` must equal a configured issuer; `aud` (string or array of strings) must contain a
   configured audience; `sub` must be a non-empty string.

| Algorithm | Key | Requirement |
| --- | --- | --- |
| HS256, HS384, HS512 | `hmacKey(alg, secret)` | secret of at least 32, 48, 64 bytes |
| RS256, RS384, RS512 | `publicKey(alg, rsaKey)` | RSA, at least 2048 bits |
| PS256, PS384, PS512 | `publicKey(alg, rsaKey)` | RSA, at least 2048 bits; salt length equals the digest length |
| ES256, ES384, ES512 | `publicKey(alg, ecKey)` | EC on P-256, P-384, P-521 respectively |
| EdDSA | `publicKey(alg, edKey)` | Ed25519 only; Ed448 keys are refused |

An RSA key registered for RS256 is not usable for PS256 (and the reverse): the registration
names exactly one algorithm, and a token whose `kid` selects a key of another algorithm is
refused.

Each key method has an overload with a key ID. Issuer and audience are mandatory: the
builder refuses to build without at least one key, issuer and audience. The identity's
principal is `sub`; roles come from `roles` and permissions from `scope` (configurable
with `rolesClaim` and `permissionsClaim`), each a space-separated string or an array of
strings, at most 256 entries. `clock(Clock)` makes time checks deterministic in tests.

### Key sets (JWKS)

```java
var jwt = JwtAuthenticator.builder()
        .jwks(JwksSource.url(URI.create("https://login.example.com/.well-known/jwks.json")),
              JwksOptions.defaults().refreshInterval(Duration.ofMinutes(15)))
        .issuer("https://login.example.com").audience("notes-api")
        .build();
jwt.refreshKeys();   // optional: fetch now, e.g. at startup; false if the endpoint or set is unusable
```

The **application** configures the source (a URL, a file, or any `JwksSource`, which is also
how tests inject a key set without a network); a token can never choose it. Keys embedded
in tokens (`jku`, `x5u`, `jwk`, `x5c`) stay unused.

| Option (`JwksOptions`) | Default | Meaning |
| --- | --- | --- |
| `maxBytes` | 64 KiB (1 KiB to 1 MiB) | longest accepted document; at most 100 keys |
| `refreshInterval` | 10 min (1 s to 24 h) | the next token after this age triggers a refetch; the old set keeps serving meanwhile |
| `minRefreshInterval` | 30 s (1 s up to the refresh interval) | shortest time between fetch attempts, successful or not |
| `maxStale` | 24 h (refresh interval to 7 days) | after this long without a successful fetch no key of the set is used (fail closed) |
| `defaultAlgorithm` | none | algorithm for keys that declare no `alg` |

- **Fetching.** `JwksSource.url` uses `java.net.http`: `https` only (`http` only to
  loopback, for development), no user information, **redirects are never followed** (any
  3xx is a failure), status 200 with `application/json` or `application/jwk-set+json`
  only. The body is read incrementally and abandoned beyond `maxBytes`; a 5 s default
  timeout (100 ms to 60 s) covers connecting, the head and the whole body. No credentials or
  cookies are sent. `JwksSource.file` reads a regular file with the same size bound.
- **Caching and rotation.** The first token fetches the set (nothing is fetched at build
  time); concurrent first requests wait for one fetch. A token whose `kid` is unknown
  triggers a refetch, but never more often than `minRefreshInterval`, so a stream of random
  key IDs causes one fetch per interval, and a failing endpoint is retried at that rate. A
  retired key stops verifying once a fetch returns a set without it.
- **Failure handling.** A fetch that fails, times out, is oversized or malformed, repeats
  a key ID, or yields no usable key keeps the last good set (until `maxStale`). Rejected
  tokens carry the fetch failure as the exception's cause for logs; clients only see
  `invalid_token`.
- **Keys are bound to their algorithm** exactly like static ones. A JWK must have a `kid`
  and `kty` `RSA`, `EC` or `OKP`; its `alg` names the one algorithm it verifies (a PS256 key
  cannot verify RS256, and no key of a set is ever an HMAC secret: `oct` keys are skipped).
  Keys without `alg` are skipped unless `defaultAlgorithm` applies and fits the key type.
  `use` must be `sig` and `key_ops` must contain `verify` when present. RSA keys need at
  least 2048 (at most 8192) bits and an odd exponent, EC keys full-length coordinates on
  the algorithm's curve and on the curve (invalid-curve points are rejected), OKP keys are
  Ed25519 only. Individual unusable keys are skipped without discarding the others.
- A statically registered key with the same key ID wins over the set's. Tokens without a
  `kid` are verified only with static keys.
- The document is fetched on the request thread that needs it (a virtual thread over
  HTTP), at most one at a time per authenticator.

### Further claims, revocation and replay

Only `sub`, the roles claim and the permissions claim reach the identity by default.

```java
JwtAuthenticator.builder()
        ...
        .exposeClaims("tenant", "email", "email_verified")   // string, number, boolean claims as text
        .attributes(claims -> Map.of("groups", String.join(",", claims.strings("groups"))))
        .tokenCheck(claims -> !revoked.contains(claims.id().orElse("")))      // revocation
        .tokenCheck(claims -> seenJti.add(claims.id().orElseThrow()))        // replay, last
        .build();
// handler: ctx.identity().orElseThrow().attribute("tenant")
```

- `exposeClaims` copies named scalar claims; an array or object claim, or a value that is
  not a valid attribute, makes the token invalid (401), and absent or `null` claims are
  skipped. `attributes(fn)` maps claims with application code through `JwtClaims`, whose
  typed accessors (`string`, `strings`, `number`, `bool`, `text`, `id`, `subject`, `issuer`,
  `expiresAt`) expose no JSON library type; a claim of the wrong type is an
  `IllegalArgumentException`, answered 401.
- `tokenCheck(TokenCheck)` runs **after** signature, expiry, issuer, audience and subject
  verification and before attributes are built. `false` is 401 `invalid_token` with no
  hint to the client. Checks run in registration order and stop at the first rejection, so
  register one that records a `jti` last. Any exception other than
  `IllegalArgumentException` propagates (500) so an unreachable revocation store fails
  closed rather than accepting the token.
- Axiom keeps no revocation or replay state: the application owns the store, its size
  and its pruning (drop a `jti` after `claims.expiresAt()` plus the clock skew), and
  shares it between instances if it runs more than one.

The authenticator is thread-safe; statically registered keys (including HMAC secrets) are
fixed for its lifetime. A configured key set is its only mutable state. `verify(token)` is public for tokens that do not arrive in
`Authorization`.

### Encrypted tokens (JWE) are a non-goal

Axiom authenticates signed tokens only. A five-part compact JWE, a header with `enc`, and
key-management algorithms (`dir`, `RSA-OAEP`, `A256KW`, `ECDH-ES`, ...) are rejected with the
same 401 `invalid_token` as any other invalid token; tests cover each. Decrypting needs
private key handling, content-encryption algorithms and a second parser surface that a
bearer-token verifier should not carry. If tokens must be confidential, decrypt them at a
gateway, or use opaque tokens and introspection, and hand Axiom the signed JWT.

## Client address and trusted proxies

`Request.remoteAddress()` is the transport peer: the HTTP listener sets it from the
socket; it is `null` for in-memory requests unless a test sets it with
`withRemoteAddress`. Behind a reverse proxy it is the proxy.

```java
static final TrustedProxies PROXIES = TrustedProxies.of("10.0.0.0/8", "fd00::/8");
var origin = PROXIES.resolve(ctx.request());      // Optional<ClientOrigin>(address, scheme, forwarded, host, port)
```

- Forwarding headers are believed only when the **peer** is a configured proxy.
  Otherwise the client is the peer and the scheme is that of the connection (`https` on a TLS
  listener, else `http`); headers are ignored, so a client
  cannot spoof its address by sending `X-Forwarded-For`.
- From a trusted peer, `X-Forwarded-For` is walked right to left; trusted hops are skipped
  and the first untrusted entry is the client (all trusted: the leftmost). The walk stops
  at an entry that is not an IP literal and after 32 entries, keeping the last vouched
  address. Entries are parsed as literals only, never resolved through DNS; ports and
  brackets are accepted.
- `X-Forwarded-Proto` is used only when it is exactly `http` or `https`; without it the scheme
  is that of the connection (`Request.isSecure()`, true only on a TLS listener).
- `X-Forwarded-Host` gives the original host (`ClientOrigin.host()`, lower case) and
  `X-Forwarded-Port` the port (`ClientOrigin.port()`). Each is believed only as a single
  value (a comma-separated list is ignored: nothing says which proxy to believe); the host
  must be a DNS name, an IPv4 literal or a bracketed IPv6 literal, optionally with a port,
  and the port 1 to 65535. A port inside the host wins over `X-Forwarded-Port`. Spaces,
  paths, `@`, empty labels and out-of-range ports are ignored, never repaired. No default
  port is inferred from the scheme.
- **One header family at a time.** The default family is `X-Forwarded-*`;
  `PROXIES.reading(ForwardedHeaders.FORWARDED)` selects RFC 7239 `Forwarded` instead. The
  other family is ignored entirely, so a client cannot mix a spoofed header of one family
  with a genuine one of the other.
- `Forwarded` is parsed strictly: tokens and quoted strings only (a value with `:` or `[`
  must be quoted, `for="[2001:db8::7]:4711"`), no whitespace around `=` or `;`, a repeated
  parameter makes its element invalid, and an unterminated quote ignores the header. The
  elements are walked right to left with the same trust rule and the 32-hop bound. `for`
  must be an IPv4 literal or a bracketed IPv6 literal (port or obfuscated port allowed);
  `unknown`, obfuscated identifiers (`_hidden`) and names stop the walk. `proto` and `host`
  come from the element that vouches for the client address, since that proxy saw the
  client's request.
- Host and port are only as trustworthy as the proxy that sets them. Never build
  password-reset or redirect URLs from them without an allow-list of your own hosts.
- Ranges are IP literals or CIDR blocks with zero host bits; anything else is rejected at
  configuration time.

## Header redaction

`HeaderRedaction.defaults().redact(headers)` returns a copy with the values of
`Authorization`, `Proxy-Authorization`, `Cookie`, `Set-Cookie`, `X-Api-Key`,
`X-Auth-Token`, `X-Csrf-Token`, `X-Xsrf-Token` and `X-Amz-Security-Token` replaced by
`[redacted]`; `and(names...)` adds more. Use it before logging request or response
headers. Axiom itself never logs header values, and `Request.toString()` prints header
names only.

## Security headers

`SecurityHeaders.defaults()` is middleware adding `X-Content-Type-Options: nosniff`,
`X-Frame-Options: DENY`, `Content-Security-Policy: default-src 'none'; frame-ancestors 'none'`,
`Referrer-Policy: no-referrer` and `Cross-Origin-Resource-Policy: same-origin`. Headers the
response already has are kept, so a handler can loosen one. `with(name, value)` and
`without(name)` return copies; `Strict-Transport-Security` is not a default because it is only
meaningful, and only safe, where the whole host is served over HTTPS and stays that way: a
browser that has seen the header refuses plain HTTP for the host until `max-age` runs out.
Add it with `with("Strict-Transport-Security", "max-age=31536000")` once the listener has
[TLS](tls.md) (or sits behind a proxy that terminates it) and every client of the host can
speak HTTPS; start with a short `max-age`, and add `includeSubDomains` or `preload` only when
every subdomain is ready. Browsers ignore the header on plain HTTP, and
`Request.isSecure()` tells a handler which kind of connection it is on.

Registered globally it also decorates router answers (404, 405, OPTIONS, 501). It does
**not** decorate responses mapped from exceptions, including the 401 and 403 of policies,
because exceptions pass through middleware before they become responses (tracked in
[#96](https://github.com/matrixjnr/axiom/issues/96); see the workaround in
[middleware](middleware.md#middleware)).

## CORS

`Cors` is global middleware that answers browser preflights on top of the router's
automatic `OPTIONS` (204 with `Allow`) and decorates cross-origin responses.

```java
app.use(Cors.builder()
        .allowOrigin("https://app.example.com")
        .allowMethods("GET", "POST", "DELETE")      // default: GET, HEAD
        .allowHeaders("Content-Type", "Authorization")
        .exposeHeaders("X-Request-ID")
        .allowCredentials()
        .maxAge(Duration.ofMinutes(10))             // default; 0 omits the header
        .build());
```

- **Misconfiguration fails at `build()`**: no origin; the wildcard (`anyOrigin()`) together
  with credentials or with listed origins; an origin that is not an exact lower-case
  `http(s)://host[:port]` (no `*`, path, user information, `null`); `*` or invalid tokens as
  methods or headers.
- **Origins match exactly** against the request's `Origin` value, so a different scheme,
  port, sub-domain suffix or a list of origins never matches. The matching origin is echoed
  in `Access-Control-Allow-Origin` with `Vary: Origin` (`anyOrigin()` answers `*` and adds no
  `Vary`).
- **Preflight** is `OPTIONS` with `Origin` and `Access-Control-Request-Method`. The rest of
  the chain runs first; a successful answer is decorated only when the origin is allowed, the
  method is configured and in the route's `Allow`, and every requested header is configured
  (at most 64). Anything else keeps the plain answer without CORS headers, so the browser
  fails the preflight. An unrouted path stays 404. `Access-Control-Allow-Methods` lists the
  configured methods the route allows; `Access-Control-Allow-Headers` echoes the requested,
  validated headers. An application's own `OPTIONS` route (for example a wildcard route
  returning `ctx.automaticOptions()` for what it does not handle) is decorated the same way
  when its answer is a 2xx.
- A request from another origin is **not rejected**: CORS is enforced by the browser and is
  not authentication. It is answered without CORS headers. Keep authenticating every request.
- Register it with `app.use` globally: group middleware never see router answers, so a
  group-scoped instance would miss preflights. Like other middleware, it does not decorate
  responses mapped from exceptions, such as a 401 from a policy; a browser then reports a
  CORS failure instead of the 401 for such responses
  ([#96](https://github.com/matrixjnr/axiom/issues/96)).

## Rejected alternatives

- A generic attribute map on `Context`: more API and untyped; the identity is the one
  value every security feature needs, and set-once semantics are easier to state for it.
- Policies that take the challenge as a parameter: the authenticator knows its scheme, so
  binding policies to it (`Security.of`) keeps 401 challenges consistent.
- Parsing JWTs with the application's JSON codec: core and security must not depend on a
  codec, and a dedicated strict parser can reject duplicate names and bound nesting.
- Trusting `X-Forwarded-For` by position (for example "the second from the right"):
  breaks silently when the proxy chain changes; trust is decided per address instead.
- A middleware that rewrites the request's address: requests are immutable values;
  `TrustedProxies.resolve` is a pure function applications call where they need it.

## Limitations

- Security headers and other middleware headers are missing on problem responses
  ([#96](https://github.com/matrixjnr/axiom/issues/96)).
- No sessions, cookies, CSRF protection or OAuth flows; authentication is
  per-request credentials only ([#122](https://github.com/matrixjnr/axiom/issues/122)).
