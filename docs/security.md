# Security

Two opt-in modules add authentication and HTTP security helpers. Both use only the JDK and
the Axiom API; versions come from the BOM.

| Module | Package | Contents |
| --- | --- | --- |
| `axiom-security` | `com.jsgalactic.axiom.security` | `Authenticator`, policies (`Security`), `TrustedProxies`, `HeaderRedaction`, `SecurityHeaders` |
| `axiom-security-jwt` | `com.jsgalactic.axiom.security.jwt` | `JwtAuthenticator`: strict JWT bearer tokens (HMAC, RSA, ECDSA) |

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

`SecurityIdentity(principal, roles, permissions)` is an immutable record (core,
`com.jsgalactic.axiom.context`). The sets are unmodifiable copies; names are 1 to 256
characters without control characters and compare exactly. `toString()` shows the principal
and grant counts, not the grants.

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
6. Signature: HMAC tags compared in constant time; RSA PKCS#1 v1.5; ECDSA in the JWS
   fixed-length format (DER signatures are rejected).
7. Claims (only parsed after the signature verified): `exp` required; `exp`, `nbf`, `iat`
   are integer NumericDates checked with the clock skew (30 s by default, 0 to 5 min);
   `iss` must equal a configured issuer; `aud` (string or array of strings) must contain a
   configured audience; `sub` must be a non-empty string.

| Algorithm | Key | Requirement |
| --- | --- | --- |
| HS256, HS384, HS512 | `hmacKey(alg, secret)` | secret of at least 32, 48, 64 bytes |
| RS256, RS384, RS512 | `publicKey(alg, rsaKey)` | RSA, at least 2048 bits |
| ES256, ES384, ES512 | `publicKey(alg, ecKey)` | EC on P-256, P-384, P-521 respectively |

Each key method has an overload with a key ID. Issuer and audience are mandatory: the
builder refuses to build without at least one key, issuer and audience. The identity's
principal is `sub`; roles come from `roles` and permissions from `scope` (configurable
with `rolesClaim` and `permissionsClaim`), each a space-separated string or an array of
strings, at most 256 entries. `clock(Clock)` makes time checks deterministic in tests.

The authenticator is immutable and thread-safe; it holds its keys (including HMAC
secrets) for its lifetime. `verify(token)` is public for tokens that do not arrive in
`Authorization`.

## Client address and trusted proxies

`Request.remoteAddress()` is the transport peer: the HTTP listener sets it from the
socket; it is `null` for in-memory requests unless a test sets it with
`withRemoteAddress`. Behind a reverse proxy it is the proxy.

```java
static final TrustedProxies PROXIES = TrustedProxies.of("10.0.0.0/8", "fd00::/8");
var origin = PROXIES.resolve(ctx.request());      // Optional<ClientOrigin>(address, scheme, forwarded)
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

- Keys are configured statically: no JWKS fetching or rotation
  ([#118](https://github.com/matrixjnr/axiom/issues/118)).
- Only HS, RS and ES algorithms; no PS256 or EdDSA, no encrypted tokens (JWE)
  ([#119](https://github.com/matrixjnr/axiom/issues/119)).
- JWT claims other than `sub` and the grant claims are not exposed, and there is no
  revocation or replay check (`jti`) ([#120](https://github.com/matrixjnr/axiom/issues/120)).
- Only `X-Forwarded-For` and `X-Forwarded-Proto` are read; not RFC 7239 `Forwarded` or
  `X-Forwarded-Host`/`-Port` ([#121](https://github.com/matrixjnr/axiom/issues/121)).
- Security headers and other middleware headers are missing on problem responses
  ([#96](https://github.com/matrixjnr/axiom/issues/96)).
- No sessions, cookies, CSRF protection, CORS or OAuth flows; authentication is
  per-request credentials only ([#122](https://github.com/matrixjnr/axiom/issues/122)).
