# Axiom

Axiom is an early Java API framework targeting Java 21. The current implementation
supports route registration and synchronous in-memory execution. Network listeners
and transport support are still under development.

```java
import io.axiom.Axiom;
import io.axiom.http.Request;

try (var app = Axiom.create()) {
    app.get("/", ctx -> "Hello, world!");
    app.start();
    var response = app.handle(Request.get("/"));
    System.out.println(response.body());
}
```

## Build and run

Install JDK 21, then run:

```sh
./gradlew clean check
./gradlew build
./gradlew :examples:hello:run
```

On Windows, use `./gradlew.bat`. The wrapper downloads Gradle 9.5.1 on first use.
`check` includes unit tests, module dependency checks, and public API signature
checks. Build and configuration caches are enabled. The example prints
`Hello, world!` and exits; it does not open a TCP port.

## Modules

- `axiom-core`: application contracts, HTTP request/response values, and bootstrap SPI
- `axiom-server`: lifecycle, exact-path dispatch, and the default runtime provider
- `axiom-http`: dependency entry point for the future HTTP transport
- `axiom-json`: JSON adapter build scaffolding
- `axiom-test`: in-memory test client
- `axiom-bom`: aligned library versions
- `examples/hello`: runnable in-memory example
- `examples/rest-api`, `benchmarks/http`: build scaffolding

Core uses JDK service loading to discover the runtime. Applications depending on
`axiom-http` receive core on the compile classpath and server on the runtime
classpath. Core has no dependency on server or external libraries.

No artifacts are published yet. Maven publication and consumer compatibility tests
will accompany release engineering. The `io.axiom` namespace is provisional until
ownership is validated. No performance claims have been established.

See the [programming model](docs/programming-model.md),
[build decisions](docs/build.md), [contributing](CONTRIBUTING.md), and
[security](SECURITY.md). Licensed under Apache-2.0.
