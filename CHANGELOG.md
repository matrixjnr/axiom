# Changelog

## Unreleased

- Read bounded request bodies (Content-Length, chunked, Expect: 100-continue) with a configurable limit.
- Add a codec SPI, a strict Jackson JSON codec in axiom-json, and Context.body/json.
- Answer errors with application/problem+json bodies and add exceptions for common 4xx/5xx statuses.
- Map transport failures to 408, 413, 414, 417, 431, 501 and 505.
- Send request bodies from TestClient and add a JSON notes API example.
- Compile route templates with named parameters, terminal wildcards, and deterministic precedence.
- Detect ambiguous route shapes at startup and expose raw captures through the request context.
- Add routing correctness coverage and an opt-in JMH dispatch benchmark.
- Add application creation, exact-path registration, lifecycle controls, and in-memory execution.
- Add request/response contracts, an in-memory test client, and a runnable Hello World example.
- Bootstrap the Java 21 Gradle multi-project build.
