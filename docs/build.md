# Build decisions

Axiom uses Gradle Kotlin DSL with Java 21 toolchains and `--release 21`, without
preview features. The wrapper pins Gradle 9.5.1 and verifies its distribution
checksum. Convention plugins share compilation, JUnit Platform, sources/Javadoc
archives, and reproducible archive settings.

The version catalog contains only dependencies in use. HTTP uses Netty 4.2 with
its BOM to align implementation modules. JSON uses jackson-databind 2.22 with the
Jackson BOM, plus the `jackson-datatype-jsr310` (java.time) and `jackson-datatype-jdk8`
(`Optional`) modules aligned by the same BOM, as `implementation` dependencies of
`axiom-json` only; no Jackson
type appears in an Axiom API, and applications add `axiom-json` with `runtimeOnly`.
The 2.x line was chosen because its package names and exceptions are stable across
the ecosystem; moving to Jackson 3 would only change `axiom-json` internals.
Telemetry dependencies will be selected when their implementations land.
JPMS and stress suites are later work. The BOM constrains all eight published libraries
(core, server, http, json, test, the starter, validation and validation-jakarta).

Validation uses Hibernate Validator 9.1 (the Jakarta Validation 3.1 reference
implementation, actively maintained, Java 17+, with container-element constraints and
validation of record components) and `jakarta.validation-api` 3.1, both as `implementation`
dependencies of `axiom-validation-jakarta` only. Applications declare
`jakarta.validation-api` themselves to annotate their types; no Jakarta or Hibernate
type appears in an Axiom API. Expression Language (`jakarta.el`) is deliberately not
a dependency: the adapter never interpolates messages (see [validation](validation.md)).
Hibernate Validator brings `jboss-logging` and `classmate` transitively. The
annotation-free `axiom-validation` module has no external dependencies. Both
validation modules are published and constrained by the BOM, but the starter does not
include them: validation is opt-in, so an application adds `axiom-validation` (or
`axiom-validation-jakarta`, which brings it) next to the starter.

Module checks enforce dependency direction across production, annotation processor
and test configurations, fail with a clear message for modules missing from the
allowed-dependency table, and confine external production dependencies to the
module that adapts them: Netty (`io.netty`) to `axiom-http`, Jackson
(`com.fasterxml.jackson*`) to `axiom-json`, and nothing in core or server. The test
client may not depend on `axiom-json`; tests send raw bodies. Public signature tests scan the exported core and test-client
classes, including generic types, to reject implementation and third-party types.
These run as part of `check` alongside behavior and lifecycle tests.
Jakarta Validation and Hibernate Validator (`jakarta.validation`, `org.hibernate.validator`)
are confined to `axiom-validation-jakarta`, which depends on `axiom-validation`, which
depends on core only. Both validation modules may use `axiom-test` in test configurations
only, for end-to-end tests; a signature test keeps provider types out of their public API.

Core discovers the default runtime through a JDK service provider. HTTP and test
client consumers receive core through `api`. HTTP uses server through
`implementation` for its protocol-neutral execution dispatcher; the test client
uses it through `implementation` to admit requests through the same dispatcher. Neither exposes server on consumer compile classpaths.
The server depends on core; core has no reverse dependency on the runtime.

`checkPublicationCoverage` (root project, part of `check`) compares three sets: the
library modules that apply the publish convention, the project constraints in
`axiom-bom`, and the keys of the allowed-dependency map in
`build-logic/.../ModuleBoundaries.kt` (test-only modules excluded). Any difference
fails the build and names the module and the file to update. To see it fire, remove
a `api(project(...))` line from `axiom-bom/build.gradle.kts` or a module from the map
and run `./gradlew checkPublicationCoverage`.

## Allocation-based tests

The no-copy tests measure allocation per thread through `com.sun.management.ThreadMXBean`
and are skipped (JUnit assumption) on a JVM that cannot measure it. Passing
`-Daxiom.requireAllocationTests=true` to Gradle turns that skip into a failure; the Build
workflow sets it, and the `axiom.java-test` convention forwards it to every test JVM.
Locally it is off by default so a different JDK does not break `check`. Currently only
the `axiom-json` test (`decodesFromAReadOnlyViewWithoutCopyingIt`) honors the flag; the
`axiom-server` `CodecViewTest` case still skips silently (tracked as a limitation).

## Hygiene and deferred items

Compilation runs with `-Xlint:all -Werror`; the build is warning-free. Archives
ignore file timestamps, use a stable entry order and normalized permissions, and
Javadoc omits its generation timestamp, so repeated builds produce identical jars.

`axiom-json` provides the JSON codec as a `BodyCodec` service; it has no public
API package. Its tests exercise the codec directly.

Deferred: pinning GitHub Actions to full commit SHAs (workflow actions use major
version tags; an owner action, see docs/releasing.md). Dependabot
(`.github/dependabot.yml`) proposes weekly updates for Actions and Gradle dependencies,
grouped by family (Netty, Jackson, validation, test tools).

## Starter artifact

`axiom-starter` is published as `io.axiom:axiom`. It has `api` on `axiom-core` and
`runtimeOnly` on `axiom-http`, `axiom-server` and `axiom-json`, so one dependency is
enough to compile a Hello World or a JSON API and to run it. The jar itself is empty
apart from the manifest; sources and Javadoc jars are published empty as well.

Facts from `./gradlew :axiom-starter:dependencies --configuration runtimeClasspath`
at 0.1.0-SNAPSHOT (Netty 4.2.18.Final, Jackson 2.22.3):

- Compile classpath of a consumer: `axiom`, `axiom-core`.
- Runtime classpath: 19 jars. 5 are Axiom (`axiom`, `axiom-core`, `axiom-http`,
  `axiom-server`, `axiom-json`), 9 are Netty (common, buffer, transport, resolver,
  codec-base, codec-compression, codec-http, handler, transport-native-unix-common)
  and 5 are Jackson (databind, core, annotations, datatype-jsr310, datatype-jdk8).
- Axiom jar sizes in bytes: core 49,793; server 44,337; http 24,685; json 15,163;
  starter 261 (total 134,239). Third-party jars total 6,135,959 bytes: Netty
  3,574,454 and Jackson 2,561,505.
- No other libraries (logging, annotation, validation or test libraries) are on the
  runtime classpath. The validation modules are deliberately not part of the starter.

## Publication

Every library module applies the `axiom.publish` convention (through
`axiom.java-library`); the BOM applies it directly. Each publishes a binary jar, a
sources jar, a Javadoc jar, a POM (name, description, URL, Apache-2.0 license, SCM,
issue tracker, developer) and Gradle module metadata. The BOM is a `java-platform`
that constrains the eight published library modules. Archives stay reproducible.

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
The Kotlin consumer also depends on `axiom-validation`, `axiom-validation-jakarta`
(versions from the BOM) and `jakarta.validation-api`, and runs
`compatibility/shared-validation/consumer/ValidationSmoke.java` and `JakartaSmoke.java`:
each starts the application and checks, over HTTP, a valid request (201) and an invalid
one (422 with only field paths and codes, no echoed input). This checks the publication
metadata of both validation modules from a consumer's point of view. The Groovy and Maven
consumers do not use the validation modules.
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
build fail.

The Gradle consumers in `compatibility/` are standalone builds, each with its own
`gradle/verification-metadata.xml`. Artifacts in group `io.axiom` are trusted
(`<trusted-artifacts>`) because they come from the locally published repository and
change with every build; every other artifact, including POMs and module files, is
checksummed. Regenerate a consumer's file from an empty Gradle home, so every artifact
is downloaded and nothing is taken from a stale cache. Seed the file with the
`<trusted-artifacts>` block, publish with `./gradlew publishCompatRepo`, then in the
consumer directory run:

```sh
../../gradlew -g "$(mktemp -d)" --no-daemon -PaxiomRepo=file://$PWD/../../build/compat-repo/ \
    -PaxiomVersion=0.1.0-SNAPSHOT --write-verification-metadata sha256 compatibilityCheck
```

Maven Central may answer HTTP 429 when many artifacts are fetched at once. Retry the
whole command a bounded number of times with a growing pause, and restore the seed
file if all attempts fail; never commit a half-written file. Regenerate the consumers
in the same commit as a change to their dependencies, and when the Jakarta Validation
or Hibernate Validator versions change.

The Maven consumer has no committed verification metadata. The compatibility task runs
`mvn --strict-checksums`, which fails when a downloaded file does not match the checksum
the repository publishes. That detects corrupted or inconsistent downloads but, unlike
committed metadata, not a change of content that is published together with its checksum.

Dependency locking was evaluated again and is still not enabled: versions are already
exact through the catalog and the Netty and Jackson BOMs (consumers declare fixed
versions or import the BOM), and checksums pin the contents. Locking would add lock
files to regenerate with every update without catching anything the checksums do not;
revisit it if dynamic versions or version ranges are ever introduced.

## Every commit builds

Rule: checksums go in the same commit as the dependency. A commit that adds or changes
a dependency or plugin without the matching entries in `gradle/verification-metadata.xml`
does not build, even if a later commit repairs it, and it breaks `git bisect`.

The `commits` job of the Build workflow (pull requests only, `contents: read`, no
secrets) checks out the PR head with full history and runs
`./gradlew clean check` on each non-merge commit between the base and the head, oldest
first, through `.github/scripts/check-each-commit.sh`. It stops at the first failing
commit. Up to 20 commits are all built. A longer series is bounded: only commits that
touch build files (`*.gradle(.kts)`, `gradle/`, `build-logic/`, `gradle.properties`,
the wrapper) are built, the newest 20 of them, so a non-build commit in a long series
can still be unbuilt. The script's selection logic was exercised locally against a
scratch repository; the workflow itself only runs on GitHub.

## Integration tests

Server, HTTP and test-client tests use small stand-in codecs, because those modules
may not depend on `axiom-json`. The `integration-tests` module is where the real
Jackson codec meets them: it runs one JSON contract (round trips, strictness
failures, limits, 400/406/413/415 problem documents and their shape, charset
handling) twice, through `TestClient` and over a live listener on a raw socket.

The module is deliberately listed in the boundary rules as **test-only**: it has no
production sources, may declare only test-scope dependencies (`axiom-test` to compile
against, `axiom-http` and `axiom-json` at test runtime, so tests see only Axiom's API
as an application would), and no other module may depend on it. Adding it therefore
does not loosen any production rule: core, server and the test client still cannot
depend on a codec or on Jackson. Its tests run in `check` and finish in a few
seconds; `examples/rest-api` remains a usage example with its own tests. The module
is not published and the BOM does not constrain it.
