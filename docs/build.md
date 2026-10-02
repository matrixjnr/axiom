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
JPMS and stress suites are later work. The BOM constrains all six published libraries
(core, server, http, json, test and the starter).

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

Deferred: pinning GitHub Actions to full commit SHAs (workflow actions use major
version tags; see docs/releasing.md).

## Starter artifact

`axiom-starter` is published as `io.axiom:axiom`. It has `api` on `axiom-core` and
`runtimeOnly` on `axiom-http`, `axiom-server` and `axiom-json`, so one dependency is
enough to compile a Hello World or a JSON API and to run it. The jar itself is empty
apart from the manifest; sources and Javadoc jars are published empty as well.

Facts from `./gradlew :axiom-starter:dependencies --configuration runtimeClasspath`
at 0.1.0-SNAPSHOT (Netty 4.2.18.Final, Jackson 2.22.3):

- Compile classpath of a consumer: `axiom`, `axiom-core`.
- Runtime classpath: 17 jars. 5 are Axiom (`axiom`, `axiom-core`, `axiom-http`,
  `axiom-server`, `axiom-json`), 9 are Netty (common, buffer, transport, resolver,
  codec-base, codec-compression, codec-http, handler, transport-native-unix-common)
  and 3 are Jackson (databind, core, annotations).
- Axiom jar sizes in bytes: core 49,562; server 44,317; http 24,685; json 8,432;
  starter 261 (total 127,257). Third-party jars total 5,963,045 bytes: Netty
  3,574,454 and Jackson 2,388,591.
- No other libraries (logging, annotation, or test libraries) are on the runtime
  classpath.

## Publication

Every library module applies the `axiom.publish` convention (through
`axiom.java-library`); the BOM applies it directly. Each publishes a binary jar, a
sources jar, a Javadoc jar, a POM (name, description, URL, Apache-2.0 license, SCM,
issue tracker, developer) and Gradle module metadata. The BOM is a `java-platform`
that constrains the six published modules. Archives stay reproducible.

Targets: `build/compat-repo` (a file repository used by the compatibility tests) and,
only when `axiom.publish.url` is given, one remote repository whose credentials come
from `axiom.publish.username` and `axiom.publish.password`. Signing is configured only
when the `signingInMemoryKey` property (optionally `signingInMemoryKeyId` and
`signingInMemoryKeyPassword`) is present, normally through
`ORG_GRADLE_PROJECT_*` environment variables, so builds without keys are unaffected.
With `-Paxiom.release=true`, publishing to the remote target fails while the
`axiom.pom.*` placeholders in `gradle.properties` remain or no signing key is set.
Signing was exercised once locally with a throwaway key (signature files were
produced); it has not been exercised with a real key.

## Consumer compatibility

`./gradlew compatibilityTest` publishes all modules into `build/compat-repo` and
builds the projects in `compatibility/`: a Gradle Kotlin DSL consumer that imports
the BOM and uses the unversioned starter, a Gradle Groovy DSL consumer that uses the
starter with an explicit version, and a Maven consumer that imports the BOM. Each
compiles and runs `compatibility/shared/consumer/Smoke.java`, which serves a plain
route and a JSON route over HTTP. The Kotlin consumer also checks that the compile
classpath holds only `axiom` and `axiom-core`, that Netty and Jackson are on the
runtime classpath, and that the starter was resolved from Gradle module metadata.
The Maven task is skipped when `mvn` is not on the path. The task is not part of
`check`; the Compatibility workflow and the release workflow run it.

## Dependency verification

`gradle/verification-metadata.xml` holds SHA-256 checksums for every external
artifact resolved by the main build and by `build-logic` (including Gradle plugins),
generated with:

```sh
./gradlew --write-verification-metadata sha256 clean check publishAllPublicationsToCompatRepository \
    --rerun-tasks --no-build-cache --no-configuration-cache
```

The checksums were taken from the repositories as served at generation time and
are trust-on-first-use; signature verification is off. A modified checksum makes the
build fail. The standalone consumer projects in `compatibility/` are not covered.
Dependency locking was evaluated and not enabled: versions are already exact
through the catalog and the Netty and Jackson BOMs, and checksums pin the contents.
