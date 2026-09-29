# Axiom

Axiom is an early Java API framework targeting Java 21. This repository currently
contains the Gradle build foundation from SPEC-0008; runtime APIs are the next milestone.

## Build

Install JDK 21, then run:

```sh
./gradlew clean check
./gradlew build
```

On Windows, use `./gradlew.bat`. The wrapper downloads Gradle 9.5.1 on first use.
JUnit Jupiter and AssertJ are configured for every Java module. `check` includes
module dependency boundary checks. Build and configuration caches are enabled.

## Modules

- `axiom-core`: transport-neutral public contracts
- `axiom-server`: execution and lifecycle
- `axiom-http`: HTTP transport implementation
- `axiom-json`: JSON adapter
- `axiom-test`: user-facing test utilities
- `axiom-bom`: aligned library versions
- `examples/hello`, `examples/rest-api`: example build scaffolding
- `benchmarks/http`: benchmark build scaffolding

Examples will become runnable as the public API and HTTP transport land. No
server or performance benchmark exists yet. Maven publication and consumer
compatibility tests belong to the release milestone; no artifacts are published.
The `io.axiom` namespace is provisional until ownership is validated.

See [build decisions](docs/build.md), [contributing](CONTRIBUTING.md), and
[security](SECURITY.md). Licensed under Apache-2.0.
