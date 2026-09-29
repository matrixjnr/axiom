# Build decisions

Axiom uses Gradle Kotlin DSL with Java 21 toolchains and `--release 21`, without
preview features. The wrapper pins Gradle 9.5.1 and verifies its distribution
checksum. Convention plugins share compilation, JUnit Platform, sources/Javadoc
archives, and reproducible archive settings.

The version catalog contains only dependencies in use. Transport, JSON, benchmark,
and telemetry dependencies will be selected when their implementations land.
JPMS, signing, publication, consumer compatibility builds, dependency verification,
and stress suites are later work. The BOM constrains all five libraries.

Module checks enforce dependency direction and prohibit external production
dependencies in core. Public signature tests scan the exported core and test-client
classes, including generic types, to reject implementation and third-party types.
These run as part of `check` alongside behavior and lifecycle tests.

Core discovers the default runtime through a JDK service provider. HTTP and test
client consumers receive core through `api` and server through `runtimeOnly`.
The server depends on core; core has no reverse dependency on the runtime.
