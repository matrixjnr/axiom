# Microbenchmarks

This module uses [OpenJDK JMH](https://github.com/openjdk/jmh) 1.37 with annotation
processing in a separate source set. `check` compiles the harness; benchmarks run
only when explicitly requested.

```sh
./gradlew :benchmarks:http:jmh
```

Benchmarks cover routing (`RoutingBenchmark`, described below), JSON decode and
encode with the Jackson codec (`JsonCodecBenchmark`), Accept negotiation
(`NegotiationBenchmark`), dispatcher admission with free capacity and with a full
queue (`AdmissionBenchmark`) and problem+json construction (`ProblemBenchmark`).
See [docs/benchmarks.md](../../docs/benchmarks.md) for what each measures, how to
run them and the methodology. No performance claims are made.

## Routing

The default run uses three one-second warmup iterations, five one-second measurement
iterations, and two forked JVMs per case. Cases cover static, parameter, wildcard,
backtracking, method mismatch, and missing-path requests. `routeCount` controls the
number of parameterized routes (100 or 10,000); four supporting routes are also
registered. Each benchmark reuses an already constructed request and consumes the
returned response through JMH.

Measurements include the public application's lifecycle-lock acquisition, routing,
context construction, handler execution where matched, and response construction.
They exclude request construction, startup, network IO, and serialization. This is
not an isolated trie lookup or an end-to-end HTTP server benchmark.

For a harness smoke check:

```sh
./gradlew :benchmarks:http:jmh --args="-wi 0 -i 1 -r 100ms -f 1 -p routeCount=100 -foe true"
```

For allocation profiling, append `--args="-prof gc"`. Run on an otherwise idle
machine with a fixed JDK, GC, heap, and CPU configuration. Record OS, hardware,
commit, JDK, JVM arguments, and the full command alongside exported results.
Use adequate warmup and multiple forks when comparing changes. Smoke-check
measurements are not performance evidence, and no performance claim or CI gate
is derived from them.
