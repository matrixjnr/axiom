# Axiom

Axiom is an early Java API framework targeting Java 21. The current implementation
supports compiled routing with parameters and wildcards, HTTP/1.1 listeners,
virtual-thread execution with request deadlines and bounded admission, bounded
request bodies with a strict JSON codec, problem+json error responses, and
in-memory testing through the same dispatcher, middleware and route groups. Bodies
are buffered in memory (no streaming), and there is no TLS or HTTP/2 yet.

```java
import io.axiom.Axiom;

try (var app = Axiom.create()) {
    app.get("/", ctx -> "Hello, world!");
    var server = app.listen(8080);
    server.termination().toCompletableFuture().join();
}
```

## Build and run

Install JDK 21, then run:

```sh
./gradlew clean check
./gradlew build
./gradlew :examples:hello:run
./gradlew :examples:rest-api:run
```

On Windows, use `./gradlew.bat`. The wrapper downloads Gradle 9.5.1 on first use.
`check` includes unit tests, module dependency checks, and public API signature
checks. Build and configuration caches are enabled. The example listens at
`http://127.0.0.1:8080/` until stopped. For a finite network smoke test, run
`./gradlew :examples:hello:run --args="--smoke"`. The rest-api example serves a small
JSON notes API on the same port.

## Modules

- `axiom-core`: application contracts, HTTP request/response values, and bootstrap SPI
- `axiom-server`: lifecycle, compiled route dispatch, and the default runtime provider
- `axiom-http`: HTTP/1.1 transport with Netty kept behind the public API
- `axiom-json`: strict JSON codec built on Jackson, added on the runtime classpath
- `axiom-test`: in-memory test client, sending raw request bodies
- `axiom-starter`: published as `io.axiom:axiom`, the single dependency for applications
- `axiom-validation`: validator interface and annotation-free rules reported as 422 field violations ([validation](docs/validation.md))
- `axiom-validation-jakarta`: Jakarta Validation annotations through Hibernate Validator, kept behind the validator interface
- `axiom-bom`: aligned library versions
- `integration-tests`: end-to-end JSON tests with the real codec, through `TestClient` and a live listener
- `examples/hello`: runnable HTTP server and network smoke example
- `examples/rest-api`: small JSON API with validation and error responses, and its tests
- `benchmarks/http`: JMH routing, JSON, negotiation, admission and problem benchmarks ([docs](docs/benchmarks.md))

Core uses JDK service loading to discover the runtime. Applications depending on
`axiom-http` receive core on the compile classpath and server on the runtime
classpath. Core has no dependency on server or external libraries; Netty and
Jackson stay inside `axiom-http` and `axiom-json`.

## Install (not yet published)

No artifacts are published yet; the snippets below show the intended coordinates and
will not resolve until a first release. The `io.axiom` namespace is provisional until
ownership is validated. `io.axiom:axiom` is a starter that brings the core API at
compile time and the HTTP server and JSON codec at run time. Publication and release
steps are in [releasing](docs/releasing.md). No performance claims have been established.

Gradle (Kotlin DSL):

```kotlin
dependencies {
    implementation(platform("io.axiom:axiom-bom:VERSION"))
    implementation("io.axiom:axiom")
}
```

Gradle (Groovy DSL):

```groovy
dependencies {
    implementation platform('io.axiom:axiom-bom:VERSION')
    implementation 'io.axiom:axiom'
}
```

Maven:

```xml
<dependencyManagement>
  <dependencies>
    <dependency>
      <groupId>io.axiom</groupId>
      <artifactId>axiom-bom</artifactId>
      <version>VERSION</version>
      <type>pom</type>
      <scope>import</scope>
    </dependency>
  </dependencies>
</dependencyManagement>
<dependencies>
  <dependency>
    <groupId>io.axiom</groupId>
    <artifactId>axiom</artifactId>
  </dependency>
</dependencies>
```

Validation is opt-in and not part of the starter: add `io.axiom:axiom-validation`, or
`io.axiom:axiom-validation-jakarta` for Jakarta annotations, alongside it (versions come
from the BOM). See [validation](docs/validation.md).

Consumer builds against locally published artifacts are exercised by
`./gradlew compatibilityTest`.

See the [programming model](docs/programming-model.md),
[routing rules](docs/routing.md), [request bodies and JSON](docs/bodies.md), [errors](docs/errors.md), [HTTP behavior and limits](docs/http.md), [execution and deadlines](docs/execution.md), [admission](docs/admission.md), [build decisions](docs/build.md), [releasing](docs/releasing.md), [contributing](CONTRIBUTING.md), and
[security](SECURITY.md). Licensed under Apache-2.0.

## Support Axiom

If Axiom is useful to you, [buy me a coffee via PayPal](https://www.paypal.com/cgi-bin/webscr?cmd=_donations&business=jonnysimiyu%40gmail.com&item_name=Axiom%20open-source%20development&currency_code=USD)
to support its development. Choose any amount; payments go to `jonnysimiyu@gmail.com`.
