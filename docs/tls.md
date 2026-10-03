---
title: TLS
parent: Guides
nav_order: 7
---

# TLS

An HTTP listener serves HTTPS when its `ListenerOptions` carry `TlsOptions`. TLS is configured per
listener, terminates in the listener, and uses only the JDK's TLS implementation (JSSE); Axiom adds no
TLS or certificate library. A TLS listener speaks HTTPS and nothing else on its port: a client that
sends plain HTTP is disconnected.

```java
var tls = TlsOptions.pem(Path.of("/etc/axiom/chain.pem"), Path.of("/etc/axiom/key.pem"));
var options = ListenerOptions.builder().tls(tls).build();
var server = app.listen(new InetSocketAddress("0.0.0.0", 8443), options);
```

`TlsOptions` lives in `axiom-core` and names no transport type. The `axiom-http` listener implements it
with the JDK `SSLEngine`; another transport can implement the same options.

## Key material

Exactly one source is configured.

- **PEM files**: `certificateChain` holds the server certificate first and then its intermediate
  issuers; `privateKey` holds the matching key as unencrypted PKCS#8 (`BEGIN PRIVATE KEY`) with an RSA,
  EC or Ed25519 key. To convert other formats:
  `openssl pkcs8 -topk8 -nocrypt -in key.pem -out key.pk8.pem`.
- **`sslContext(Supplier<SSLContext>)`**: for keys that live in a keystore, a hardware module or a
  secret manager. The supplier is called at startup and on every reload. The listener still applies
  its own protocol, cipher, ALPN and client-authentication settings to every connection.

### Fail fast

The material is validated before the listener binds a socket or starts a thread. `listen` throws a
`TlsConfigurationException` (an `IOException`) for:

- a missing, unreadable or oversized (over 1 MiB) file, a file without the expected PEM block, a block
  that is not a valid certificate or key, more than one private key in the key file, an encrypted or
  traditional-format (`RSA PRIVATE KEY`, `EC PRIVATE KEY`) key;
- a private key that does not belong to the first certificate (checked by signing and verifying a
  random challenge);
- any certificate of the chain, or of the client trust file, that is expired or not yet valid, judged by
  `TlsOptions.Builder.clock` (the system clock by default);
- a minimum protocol or list of cipher suites that leaves nothing the JVM supports;
- an `sslContext` supplier that throws or returns null.

Messages name the file or the problem and never contain key bytes, certificate bytes, passwords or a
parser's own message, and the exception has no cause that could. A supplier's exception is reported by
class name only.

## Protocols, ciphers and ALPN

| Setting | Default | Notes |
| --- | --- | --- |
| `minimumProtocol` | TLS 1.2 | TLS 1.3 is used whenever the client supports it. SSLv3, TLS 1.0 and 1.1 cannot be enabled. Set `TLS_1_3` to refuse TLS 1.2 clients |
| `cipherSuites` | `TlsOptions.DEFAULT_CIPHER_SUITES` | TLS 1.3 suites plus ECDHE with AES-GCM or ChaCha20-Poly1305 for TLS 1.2; no CBC, RC4, 3DES, static RSA or DHE. The server's order wins. Suites the JVM lacks are skipped; at least one must remain |

The listener advertises ALPN `http/1.1` and nothing else. A client that offers ALPN protocols without
`http/1.1` (for example only `h2`) is refused with a `no_application_protocol` alert; a client that
offers none is served HTTP/1.1. The JVM-wide `jdk.tls.disabledAlgorithms` security property still
applies on top of these settings.

## Client certificates (mutual TLS)

Client certificates are not requested by default. `requireClientCertificates(Path)` takes a PEM file of
the certificates a client certificate must chain to (a client certificate itself or the authority that
issued it); a client without a certificate, or with one that does not chain to it, fails the handshake
and never reaches admission or a handler. With an `sslContext` supplier use
`requireClientCertificates()` and the context's trust managers decide. Only the required mode exists.

## Reloading certificates

`Server.reloadTls()` re-reads the files (or calls the supplier) and swaps the new material in
atomically. Connections accepted afterwards use it; connections that are already open keep the
certificate they negotiated and continue unaffected. A reload that fails (missing file, expired
certificate, key mismatch, a half-written renewal) throws the `TlsConfigurationException` and leaves the
previous material in use.

For deployments that replace files without a signal, `reloadInterval(Duration)` (1 second to 1 day, PEM
files only) checks the files at that interval and reloads when their content changed. A rejected change
is logged and counted once, not on every check, and is tried again when the files change again. There
is no file-system watch: polling compares a digest of the file contents, so replacing a file by rename
or in place both work. Renew the key and the certificate together; a check that lands between the two
writes is rejected as a mismatch and the next check picks up the finished pair.

## Request attributes and proxies

`Request.isSecure()` is true when the request arrived over a TLS connection to this server and
`Request.scheme()` is then `https`. It is set by the listener, never read from a header, and is false
for in-memory requests (`TestClient` is unchanged and sends plain requests; use
`request.withTls(true)` to simulate HTTPS in a test).

`TrustedProxies.resolve` uses it: from an untrusted peer the scheme is the connection's, and from a
trusted proxy `X-Forwarded-Proto` (exactly `http` or `https`) wins, falling back to the connection's.
Behind a proxy that terminates TLS the listener is plain HTTP, so `isSecure()` is false there and
`TrustedProxies` is what tells the client-facing scheme ([security](security.md#client-address-and-trusted-proxies)).
For HSTS see [security headers](security.md#security-headers).

## Handshake limits

`ListenerOptions.handshakeTimeout` (default 10 seconds, 1 millisecond to 1 day) bounds the handshake of
each connection. A connection that is slow, silent, sends garbage or plain text, or fails certificate
checks is closed, and releases its connection slot at once.

- A connection holds a connection slot from `accept` on, so handshakes in progress count against
  `maxConnections` until they finish or time out; the cap and the timeout bound how many can be
  stalled and for how long. Further connections over the cap are closed immediately, as for plain HTTP.
- TLS runs before any HTTP parsing, so a handshake never occupies admission capacity, a queue place or a
  handler, and `AdmissionSnapshot` does not change for failed handshakes.
- After the handshake, the head, idle and response timeouts apply as on a plain listener.

## Metrics

Tags are fixed and low-cardinality: nothing a client sends (protocol, cipher, server name, certificate
subject, address) becomes a tag.

| Metric | Type | Tags | Meaning |
| --- | --- | --- | --- |
| `axiom.http.tls.handshakes` | counter | `outcome` | One per connection: `completed`, `failed` (certificate or protocol failure), `timeout`, `plaintext` (not TLS), `closed` (the peer left first) |
| `axiom.http.tls.reloads` | counter | `outcome` | `completed` or `failed` (previous material kept), from `reloadTls()` and polling |

## Non-goals

These are outside what this module does, not planned work:

- HTTP/2 and ALPN `h2`; the listener is HTTP/1.1 only.
- OCSP stapling, certificate revocation checking and certificate transparency.
- Encrypted private keys and traditional-format PEM keys; convert to unencrypted PKCS#8 or use an
  `sslContext` supplier.
- Optional client certificates (request but do not require); session tickets and resumption tuning;
  TLS 1.0 and 1.1; SNI-based certificate selection among several certificates.
- Obtaining or renewing certificates (ACME); renew with your tooling and call `reloadTls()` or poll.
- File-system watch services.
- TLS in `TestClient`, which stays in memory.
