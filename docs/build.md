# Build decisions — SPEC-0008 / PR 1

Source: https://docs.google.com/document/d/17DZ5SIWqlD45czCsEqPwcFQq-hg6B9JIQAL4CKQspyc/edit

This implements milestone 0 and section 134: repository bootstrap only.
Java 21 toolchains and `--release 21` set the baseline, without preview features.
Gradle 9.5.1 is pinned by the wrapper. Kotlin DSL conventions share compilation,
JUnit Platform, sources/Javadoc archives, and reproducible archive settings.

The version catalog contains only dependencies used now. Netty, JSON adapters,
JMH, and telemetry dependencies will be selected when their implementations land.
Examples and benchmarks have build files but no placeholder runtime classes.
JPMS, signing, publication, consumer compatibility, dependency verification, and
stress suites are later milestones. The BOM already constrains all five libraries.

Dependency checks enforce the initial inward module graph and prevent external
production dependencies in core. Signature-level leakage tests must be added
with the public programming model; there are no production signatures in PR 1.
