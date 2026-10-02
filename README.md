# Axiom

Axiom is an early Java API framework targeting Java 21. The current implementation
supports compiled routing with parameters and wildcards, HTTP/1.1 listeners,
and synchronous in-memory testing. The HTTP transport supports text and byte responses.

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
```

On Windows, use `./gradlew.bat`. The wrapper downloads Gradle 9.5.1 on first use.
`check` includes unit tests, module dependency checks, and public API signature
checks. Build and configuration caches are enabled. The example listens at
`http://127.0.0.1:8080/` until stopped. For a finite network smoke test, run
`./gradlew :examples:hello:run --args="--smoke"`.

## Modules

- `axiom-core`: application contracts, HTTP request/response values, and bootstrap SPI
- `axiom-server`: lifecycle, compiled route dispatch, and the default runtime provider
- `axiom-http`: HTTP/1.1 transport with Netty kept behind the public API
- `axiom-json`: JSON adapter build scaffolding
- `axiom-test`: in-memory test client
- `axiom-bom`: aligned library versions
- `examples/hello`: runnable HTTP server and network smoke example
- `examples/rest-api`: build scaffolding
- `benchmarks/http`: JMH routing and dispatch benchmarks

Core uses JDK service loading to discover the runtime. Applications depending on
`axiom-http` receive core on the compile classpath and server on the runtime
classpath. Core has no dependency on server or external libraries.

No artifacts are published yet. Maven publication and consumer compatibility tests
will accompany release engineering. The `io.axiom` namespace is provisional until
ownership is validated. No performance claims have been established.

See the [programming model](docs/programming-model.md),
[routing rules](docs/routing.md), [HTTP behavior and limits](docs/http.md), [build decisions](docs/build.md), [contributing](CONTRIBUTING.md), and
[security](SECURITY.md). Licensed under Apache-2.0.

## Support Axiom

If Axiom is useful to you, [buy me a coffee via PayPal](https://www.paypal.com/cgi-bin/webscr?cmd=_donations&business=jonnysimiyu%40gmail.com&item_name=Axiom%20open-source%20development&currency_code=USD)
to support its development. Choose any amount; payments go to `jonnysimiyu@gmail.com`.
