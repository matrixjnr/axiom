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
JPMS and stress suites are later work. The BOM constrains all eleven published libraries
(core, server, http, json, test, the starter, validation, validation-jakarta, security,
security-jwt and metrics).

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
`axiom-security` depends on core only and `axiom-security-jwt` on `axiom-security`; neither
may declare an external production dependency, so JWT parsing and signature verification
use the JDK. Both may use `axiom-test` in test configurations only. They are published and
constrained by the BOM but not part of the starter.
`axiom-metrics` depends on core only, declares no external dependency (the registry and the
Prometheus text renderer use the JDK) and may use `axiom-test` in test configurations only.
The Metrics SPI itself lives in core.

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

The checks themselves have JUnit tests in `build-logic/src/test/kotlin` (`./gradlew
:build-logic:test`), and the root `check` runs them through the included build's `check`. They
cover the pure `problems()` function (agreement, a missing BOM entry, a missing boundary entry, a
module present in only one set, the test-only exemption) and `CheckModuleBoundaries.verify` (allowed,
forbidden, unlisted and test-only dependencies, and the external-dependency confinement rules).

## Unit and integration tests

Tests are split into two Gradle tasks per module, both part of `check`:

- `test` runs the fast tests: pure unit tests, Netty embedded channels and the in-memory
  `TestClient`. It opens no real socket.
- `integrationTest` runs the test classes tagged `@Tag("integration")`: every class that
  opens a real socket or starts a live listener (`ServerSocket`, `Socket`, `NettyServer.bind`,
  `app.listen`, `HttpClient`). It exists only in modules that apply the
  `axiom.integration-test` convention: `axiom-http`, `integration-tests`,
  `examples/readme` and `examples/rest-api`. It runs from the same test source set and classpath as `test`.

The tag is set per class, so a class that mixes embedded-channel and live-listener cases cannot
be split by tag. Such classes are kept in two: `HttpLingerTest` and `HttpPipelineErrorTest` hold the
embedded cases and run in `test`; `HttpLingerLiveTest` and `HttpPipelineErrorLiveTest` hold the
cases that bind a listener and connect a raw socket and run in `integrationTest`. `NettyServerTest`
stays whole in `integrationTest`: every one of its cases binds a real listener (it only feeds
embedded channels to it). The `integration-tests` module is integration as a whole: its
`integrationTest` runs every class there, including the `TestClient` half of the JSON
contract, and its `test` task is disabled. A module whose tests carry the tag without
applying `axiom.integration-test` fails `check` (`checkIntegrationTags`) instead of
silently running those tests nowhere.

TLS tests (`TlsListenerTest`) generate their certificates with the JDK's own `keytool` from
`java.home` and write the PEM files themselves, so they need a JDK rather than a JRE and no
third-party library; each test removes its temporary directory.

The root project has two aggregates: `./gradlew unitTest` runs every module's `test`, and
`./gradlew integrationTest` runs every module's `integrationTest`. A single module runs as
`./gradlew :axiom-http:test` or `./gradlew :axiom-http:integrationTest`.

Test classes / tests per task (counted from the JUnit reports of `./gradlew clean check` after the
mixed HTTP classes were split; the split moved 49 embedded cases from `integrationTest` to `test` and lost none: axiom-http
ran 3 / 31 and 12 / 193 immediately before it, 5 / 80 and 12 / 144 after, 224 tests both times):

| Module | `test` | `integrationTest` |
| --- | --- | --- |
| axiom-core | 15 / 224 | - |
| axiom-server | 25 / 276 | - |
| axiom-http | 5 / 80 | 12 / 144 |
| axiom-json | 2 / 66 | - |
| axiom-test | 12 / 46 | - |
| axiom-validation | 6 / 37 | - |
| axiom-validation-jakarta | 3 / 16 | - |
| axiom-security | 3 / 48 | - |
| axiom-security-jwt | 2 / 27 | - |
| axiom-metrics | 2 / 11 | - |
| integration-tests | disabled | 5 / 66 |
| examples/readme | 1 / 2 | 1 / 1 |
| examples/rest-api | 1 / 3 | 1 / 1 |
| total | 77 / 836 | 19 / 212 |

`build-logic` has its own `test` task (2 / 20) that the root `check` runs through the included build.

## Coverage

The `axiom.java-test` convention applies the Gradle `jacoco` plugin with the JaCoCo version
pinned in the version catalog (`jacoco`). The agent is attached to a test task (`test` and
`integrationTest`) only when the requested build contains a JaCoCo report task
(`coverageReport`, a module's `jacocoTestReport`, `testCodeCoverageReport` and so on); such a
task then writes `build/jacoco/<task>.exec` in its module. Plain `check`, `test`,
`unitTest` and `integrationTest` runs (local, the CI test jobs and the per-commit job) run
without the agent, so timing- and allocation-sensitive tests are not instrumented. Decision:
the agent is a coverage-only concern; the `quality` job is where it runs. Test tasks are
cached separately with and without the agent, so a coverage run executes the tests once under
the agent even when the same tests passed without it.

- Per module: `./gradlew :axiom-http:jacocoTestReport` runs the module's test tasks and writes
  `build/reports/jacoco/test/html/` and `build/reports/jacoco/test/jacocoTestReport.xml` from
  the execution data of both. It covers the module's classes with the module's own tests only.
- Aggregated: `./gradlew coverageReport` (root) runs the tests it needs, writes every
  per-module report, and writes `build/reports/jacoco/coverageReport/html/` and
  `build/reports/jacoco/coverageReport/coverageReport.xml`. The root applies Gradle's
  `jacoco-report-aggregation` plugin over the ten library modules and `integration-tests`,
  so a class is covered by any test of any of them (for example, codec classes exercised by
  `integration-tests`). The `axiom.integration-test` convention publishes the
  `integrationTest` execution data as a variant with the test suite name `integrationTest`;
  the root merges the `test` and `integrationTest` aggregates (also available on their own
  as `testCodeCoverageReport` and `integrationTestCodeCoverageReport`). The BOM has no code;
  examples and benchmarks are not library code and are not aggregated.

Examples are deliberately not in the coverage report: they are usage samples, not shipped
code, and including them would dilute the numbers of the libraries without testing more of
them. Their own tests (`TestClient` cases in `test`, live-listener cases in `integrationTest`)
still run in `check`.

No class is excluded from coverage. Coverage is reported, not enforced: there is no
minimum yet. Per-module floors will be introduced later, starting from the baseline below,
and then raised.

Baseline, measured on 2026-10-03 with JaCoCo 0.8.15 by `./gradlew coverageReport` on the
commit that introduced coverage (parent `bf8e50b`), as covered lines and branches:

| Module | Line, aggregated | Branch, aggregated | Line, own tests | Branch, own tests |
| --- | --- | --- | --- | --- |
| axiom-core | 92.8% (544/586) | 89.5% (418/467) | 87.4% (512/586) | 88.4% (413/467) |
| axiom-server | 97.3% (695/714) | 89.4% (454/508) | 96.9% (692/714) | 88.4% (449/508) |
| axiom-http | 98.2% (389/396) | 83.9% (292/348) | 98.2% (389/396) | 83.9% (292/348) |
| axiom-json | 94.0% (142/151) | 81.2% (69/85) | 94.0% (142/151) | 81.2% (69/85) |
| axiom-test | 93.8% (45/48) | 70.0% (14/20) | 93.8% (45/48) | 70.0% (14/20) |
| axiom-validation | 97.6% (248/254) | 91.1% (224/246) | 97.6% (248/254) | 91.1% (224/246) |
| axiom-validation-jakarta | 90.7% (78/86) | 73.9% (65/88) | 90.7% (78/86) | 73.9% (65/88) |
| total | 95.8% (2141/2235) | 87.2% (1536/1762) | | |

"Aggregated" is the module's share of `coverageReport` (all tests of all aggregated
modules); "own tests" is the module's `jacocoTestReport`. `axiom-starter` has no classes.
Of the total, `test` alone covers 90.7% of lines and 81.8% of branches, `integrationTest`
alone 68.3% and 54.6%.

## Code style

Checkstyle (version in `gradle/libs.versions.toml`) is applied to every Java module by the
`axiom.java-base` convention plugin. `checkstyleMain` and `checkstyleTest` are part of each
module's `check` and fail the build on any violation (`maxWarnings = 0`); run one module with
`./gradlew :axiom-core:checkstyleMain :axiom-core:checkstyleTest`. The single shared ruleset is
`config/checkstyle/checkstyle.xml`. It encodes only conventions the tree already follows: no tabs,
trailing whitespace or star imports, a final newline, no unused or redundant imports, imports in
one ASCII-sorted block with static imports first, 4-space indentation, lines of at most 200
characters, standard naming, `final` classes without a visible constructor (or a hidden one for
utility classes), required braces for multi-line statements, and Javadoc on every public type and
method of library main sources. Tests, examples and benchmarks are exempt from the Javadoc rule,
and the README examples from the utility-class rule, by filters inside the ruleset itself; there
is no separate suppression file. A rule the code does not satisfy is removed or tuned rather than
the code reformatted. The Checkstyle dependencies have checksums in
`gradle/verification-metadata.xml` like every other tool dependency.

## README examples

The README's Hello world, tasks API and `TestClient` snippet are quoted from compiled sources in
`examples/readme` (`Hello.java`, `TasksApi.java`, and the `tested` region of `TasksApiTest.java`).
`TasksApiTest` runs the snippet through `TestClient`; `TasksApiLiveTest` (tagged `integration`)
starts `TasksApi` on a real socket (on an ephemeral port: its `main` binds 8080). A README block is
tied to its source by a marker line directly before the fence:

```
<!-- snippet: examples/readme/src/main/java/TasksApi.java -->
<!-- snippet: examples/readme/src/test/java/TasksApiTest.java#tested -->
```

The first form quotes a whole file; the second the lines between `// region tested` and
`// endregion tested`, dedented. The root `checkReadmeSnippets` task (part of `check`) fails,
naming the README line, when a block differs from its source, the source or region is missing,
or no marker exists at all. Its logic has unit tests in `build-logic`. To change an example,
edit the source and copy it into the README.

## Allocation-based tests

The no-copy tests measure allocation per thread through `com.sun.management.ThreadMXBean`
and are skipped (JUnit assumption) on a JVM that cannot measure it. Passing
`-Daxiom.requireAllocationTests=true` to Gradle turns that skip into a failure; the Build
workflow sets it, and the `axiom.java-test` convention forwards it to every test JVM
(`test` and `integrationTest` alike).
Locally it is off by default so a different JDK does not break `check`. Every allocation-based
test honors the flag, so none of them can be skipped (or pass vacuously) in CI: the `axiom-json`
read-only-view test, the `axiom-server` `CodecViewTest` case and the `axiom-http`
`HttpConnectionTest` allocation case. The `unit`, `integration`, `quality` and per-commit jobs
pass the flag on Temurin 21, which supports the measurement.

## Hygiene and deferred items

Compilation runs with `-Xlint:all -Werror`; the build is warning-free. Archives
ignore file timestamps, use a stable entry order and normalized permissions, and
Javadoc omits its generation timestamp, so repeated builds produce identical jars.

`axiom-json` provides the JSON codec as a `BodyCodec` service; it has no public
API package. Its tests exercise the codec directly.

GitHub Actions are pinned to full commit SHAs with the version in a trailing comment
(`uses: actions/checkout@<sha> # v7`). The SHAs were read from the upstream repositories
with `git ls-remote --tags` (for annotated tags, the commit the tag points at). Dependabot
(`.github/dependabot.yml`) proposes weekly updates for Actions and Gradle dependencies,
grouped by family (Netty, Jackson, validation, test tools), and keeps the pins current.

## Starter artifact

`axiom-starter` is published as `com.jsgalactic.axiom:axiom`. It has `api` on `axiom-core` and
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
that constrains the ten published library modules. Archives stay reproducible.

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
./gradlew --write-verification-metadata sha256 clean check coverageReport \
    publishAllPublicationsToCompatRepository --rerun-tasks --no-build-cache --no-configuration-cache
```

Run it with an empty Gradle home (`GRADLE_USER_HOME` pointing at a new directory), so every
artifact is downloaded and checksummed rather than taken from a cache. `coverageReport` is
included because the JaCoCo agent, report and aggregation configurations are resolved only
when coverage runs.

The checksums were taken from the repositories as served at generation time and
are trust-on-first-use; signature verification is off. A modified checksum makes the
build fail.

The Gradle consumers in `compatibility/` are standalone builds, each with its own
`gradle/verification-metadata.xml`. Artifacts in group `com.jsgalactic.axiom` are trusted
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

## Continuous integration

The Build workflow (`.github/workflows/build.yml`, `contents: read`) runs on pushes to
`main` and on pull requests. A new push to a pull request cancels the run for its previous
head; runs on `main` are never cancelled. Jobs run in parallel, each on a fresh runner with
the Gradle cache of `gradle/actions/setup-gradle`:

| Job | Runs | Timeout |
| --- | --- | --- |
| `build` | `./gradlew assemble testClasses javadoc check -x test -x integrationTest`: compilation (main, test and benchmark sources), jars, Javadoc, `architectureTest`, `checkstyleMain` and `checkstyleTest` of every module, `checkPublicationCoverage`, `checkIntegrationTags`, the benchmark harness compile and dependency verification of everything it resolves | 20 min |
| `matrix` | `./gradlew -q unitTestMatrix`: prints the modules that have unit tests as the `unit` job's matrix | 10 min |
| `unit` | `./gradlew <module>:test -Daxiom.requireAllocationTests=true`, one job per module listed by `matrix`; uploads the module's test report on failure | 20 min |
| `integration` | `./gradlew integrationTest -Daxiom.requireAllocationTests=true` (axiom-http, integration-tests, examples/rest-api); uploads the test reports on failure | 30 min |
| `quality` | `./gradlew coverageReport`, uploads `build/reports/jacoco/coverageReport/` (XML and HTML) as the `coverage-report` artifact, then the hello smoke run `./gradlew :examples:hello:run --args=--smoke` | 30 min |
| `check` | needs `build`, `matrix`, `unit`, `integration` and `quality` and fails unless each succeeded (it runs even when one failed or was cancelled) | 5 min |
| `commits` | pull requests only: `./gradlew clean check` on each commit (below) | 90 min |

Branch protection requires the status check named `check`; the aggregate job keeps that
name, so the required check stays valid. `commits` is not part of `check`, as before. The
`unit` matrix is generated by the root `unitTestMatrix` task (modules whose `test` task is
enabled and that have a test class not tagged `integration`), so a new module with tests is
picked up automatically; `integration` likewise picks up every `integrationTest` task through
the root aggregate. If the matrix job fails or prints nothing, `unit` does not run and `check`
fails. The workflow graph is only verifiable on GitHub. The scheduled Compatibility workflow and the
tag-triggered release workflow are separate.

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
depend on a codec or on Jackson. Its tests run in its `integrationTest` task (part of
`check`) and finish in a few seconds; `examples/rest-api` remains a usage example with its own tests. The module
is not published and the BOM does not constrain it.

## Documentation site

The site (see [the site guide](site.md)) is generated from `README.md` and `docs/*.md` plus an
aggregated Javadoc, and published to GitHub Pages by `.github/workflows/docs.yml`.

**Generator choice.** The generator is a small converter written in Kotlin in `build-logic`
(`MarkdownHtml`, `DocsSite`) with a page template and one stylesheet, and no dependency at all.
The options weighed were a Markdown library on the build-logic classpath, a static-site tool run
in CI, and this. A library adds a published artifact (plus its transitive ones) to every
build, needs checksums and verification metadata kept up to date, and has a release cadence to
follow. A static-site tool adds an installed runtime and, for Python tools, a pinned and hashed
requirements file with its own update path, and it still needs a converter for GitHub-style
anchors and relative links. Our documents use a small, regular subset of Markdown (headings,
lists, fenced code, tables, links, emphasis), which a few hundred lines convert exactly; the
subset and its exclusions are listed in the site guide, and raw HTML is escaped rather than
passed through, so a document can never inject markup. The conversion has unit tests in
`build-logic`, and the real documents are converted and link-checked by `check`, so a construct
the converter mishandles shows up as a broken link or in review of the preview artifact. The
supply-chain surface is therefore the repository's own source, the JDK and Gradle that already
build everything else; the Javadoc tool is the JDK's. The Pages workflow adds only three more
official actions, pinned to commit SHAs like the others.

**Link checking.** `checkDocsLinks` is part of `check` and `checkSiteLinks` runs in `docsSite`.
They read the generated HTML and fail on a missing target file, a missing `#anchor`, a duplicate
id or a site-absolute (`/...`) path, which would break under the `/axiom/` project path. Links
from documents to other repository files are rewritten to GitHub and must exist. External URLs
are not fetched, so the build stays offline and deterministic.

**Javadoc.** `aggregateJavadoc` documents the main sources of every module that applies
`maven-publish` (the same set `checkPublicationCoverage` uses), excluding `internal` packages.
Its classpath is a root configuration with the published modules, resolved under the committed
dependency verification metadata like every other configuration. Doclint is off for the
aggregate because each module's own `javadoc` task, part of the Build workflow, is the gate.

The Docs workflow is separate from the Build workflow and not part of its `check` job.
Pull requests build and check the site and keep it as an artifact; only pushes to `main` deploy,
from a job that alone holds `pages: write` and `id-token: write`.
