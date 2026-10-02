# Build decisions

Axiom uses Gradle Kotlin DSL with Java 21 toolchains and `--release 21`, without
preview features. The wrapper pins Gradle 9.5.1 and verifies its distribution
checksum. Convention plugins share compilation, JUnit Platform, sources/Javadoc
archives, and reproducible archive settings.

The version catalog contains only dependencies in use. HTTP uses Netty 4.2 with
its BOM to align implementation modules. JSON uses jackson-databind 2.22 with the
Jackson BOM, as an `implementation` dependency of `axiom-json` only; no Jackson
type appears in an Axiom API, and applications add `axiom-json` with `runtimeOnly`.
The 2.x line was chosen because its package names and exceptions are stable across
the ecosystem; moving to Jackson 3 would only change `axiom-json` internals.
Telemetry dependencies will be selected when their implementations land.
JPMS, signing, publication, consumer compatibility builds, dependency verification,
and stress suites are later work. The BOM constrains all five libraries.

Module checks enforce dependency direction across production, annotation processor
and test configurations, fail with a clear message for modules missing from the
allowed-dependency table, and confine external production dependencies to the
module that adapts them: Netty (`io.netty`) to `axiom-http`, Jackson
(`com.fasterxml.jackson*`) to `axiom-json`, and nothing in core or server. The test
client may not depend on `axiom-json`; tests send raw bodies. Public signature tests scan the exported core and test-client
classes, including generic types, to reject implementation and third-party types.
These run as part of `check` alongside behavior and lifecycle tests.

Core discovers the default runtime through a JDK service provider. HTTP and test
client consumers receive core through `api`. HTTP uses server through
`implementation` for its protocol-neutral execution dispatcher; the test client
uses it through `implementation` to admit requests through the same dispatcher. Neither exposes server on consumer compile classpaths.
The server depends on core; core has no reverse dependency on the runtime.

## Hygiene and deferred items

Compilation runs with `-Xlint:all -Werror`; the build is warning-free. Archives
ignore file timestamps, use a stable entry order and normalized permissions, and
Javadoc omits its generation timestamp, so repeated builds produce identical jars.

`axiom-json` provides the JSON codec as a `BodyCodec` service; it has no public
API package. Its tests exercise the codec directly; end-to-end JSON behavior over a
listener and through `TestClient` is tested in `examples/rest-api`.

Deferred: Gradle dependency verification metadata (needs network access and
maintainer decisions on trust), and pinning GitHub Actions to full commit SHAs
(workflow actions currently use major version tags).
