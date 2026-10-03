# Benchmarks

The [`benchmarks/http`](../benchmarks/http/README.md) module holds JMH 1.37
microbenchmarks in a separate `jmh` source set. `./gradlew check` compiles them so
they cannot rot; it never runs them, and no CI job measures performance.

**No performance claims are made.** The repository publishes no results, and no
number from these benchmarks is a guarantee, a target or a CI gate. They exist so
that changes to hot paths can be compared on one machine, by one person, under
controlled conditions.

## What is measured

| Benchmark | Cases | Path |
| --- | --- | --- |
| `RoutingBenchmark` | literal, parameter, wildcard, backtracking, method mismatch, missing; 100 or 10,000 routes | `Application.handle`: routing, context, handler, response |
| `JsonCodecBenchmark` | decode and encode of a small record (2 properties) and a medium one (20 nested lines, java.time, UUID, map; about 2 KiB) | the Jackson codec found through `ServiceLoader`, decoding from a read-only buffer as `ctx.body` does |
| `NegotiationBenchmark` | no Accept header, exact match, a browser-style header, a ranked header with parameters, an unacceptable header (406) | `Application.handle` of a route returning `ctx.json(...)`, including encoding |
| `AdmissionBenchmark` | uncontended submission waiting for the result; submission rejected because the active and queue slots are held | the shared `RequestDispatcher` used by listeners and `TestClient` |
| `ProblemBenchmark` | status-only problem; problem with a field violation | `Problems.response`, used for every error response |
| `RequestPathBenchmark` | `handle` (creates the execution context) and `handleSharedExecution` (reuses one), driven by one JMH thread per available processor | `Application.handle` of a running application: the lock-free read of the immutable runtime snapshot, routing, context, handler, response |
| `RequestIdBenchmark` | creating an execution context, reading its request ID, and `UUID.randomUUID()` as a reference; one JMH thread per available processor | the process-wide counter behind request IDs |
| `MiddlewareChainBenchmark` | 0, 1, 5 or 20 pass-through middleware, registered globally or on the route; one thread | `Application.handle` of a trivial handler behind a chain composed once at startup |

The multi-thread benchmarks default to `@Threads(Threads.MAX)`; pass `-t 1`, `-t 4` and so on
to compare thread counts on the same machine. For `MiddlewareChainBenchmark` the
`depth=0` case is the baseline: the cost of a chain is the difference to it, and
`-prof gc` reports `gc.alloc.rate.norm`, the bytes allocated per request, for each
depth and scope. `uuid` in `RequestIdBenchmark` is only a reference point for the
call the counter replaced; it is not part of the request path.

Negotiation is not isolated from routing and encoding: compare the `none` case with
the others to see what the Accept header adds. The admission and problem
benchmarks call internal server classes directly; those classes are not
application API and may change with the benchmarks.

## Running

```sh
./gradlew :benchmarks:http:jmh                                   # everything, default settings
./gradlew :benchmarks:http:jmh --args="JsonCodecBenchmark"       # one class (a regular expression)
./gradlew :benchmarks:http:jmh --args="NegotiationBenchmark -p accept=none,exact"
./gradlew :benchmarks:http:jmh --args="JsonCodecBenchmark -prof gc"   # allocation per operation
```

Defaults are three one-second warmup iterations, five one-second measurement
iterations and two forks, reporting average time per operation. A smoke run that
only proves the harness executes:

```sh
./gradlew :benchmarks:http:jmh --args="-wi 0 -i 1 -r 100ms -f 1 -foe true -p routeCount=100"
```

```sh
./gradlew :benchmarks:http:jmh --args="RequestPathBenchmark -t 1"          # one thread
./gradlew :benchmarks:http:jmh --args="RequestPathBenchmark -t 8"          # eight threads contending
./gradlew :benchmarks:http:jmh --args="RequestIdBenchmark"
./gradlew :benchmarks:http:jmh --args="MiddlewareChainBenchmark -prof gc"
./gradlew :benchmarks:http:jmh --args="MiddlewareChainBenchmark -p depth=0,20 -p scope=global"
```

Smoke-run numbers are meaningless and must not be quoted.

## Methodology

- Run on an otherwise idle machine with a fixed JDK, garbage collector, heap size
  and CPU frequency policy; record OS, hardware, JDK, JVM arguments, commit and the
  full command alongside any exported result (`-rf json -rff result.json`).
- Compare changes with the same settings on the same machine, with enough warmup
  and forks that the reported error is small relative to the difference.
- Inputs are built once in `@Setup`; results are returned to JMH so the work is
  not eliminated. Network I/O, startup and request construction are excluded.
- JVM flags: the `jmh` task forks JMH, which forks the measured JVMs with the Java 21
  toolchain's defaults (the platform's default collector and heap sizing). Pass
  `-jvmArgs "-Xms2g -Xmx2g -XX:+UseParallelGC"` (any flags) after the class pattern to fix
  them, and record whatever you used. `-prof gc` adds allocation per operation
  (`gc.alloc.rate.norm`, bytes per operation); it counts what the JVM allocated and does not
  say where.
- Thread counts matter for contention results: report the `-t` value, and the number of
  hardware threads, with them. Results taken with more benchmark threads than cores
  measure scheduling as much as the code.
- **Numbers from shared hardware are not performance claims.** CI runners, containers and
  virtual machines share cores, caches and memory bandwidth with other work and change speed
  from run to run. Results from them, and from any single run, show at most that a harness
  works. No benchmark here is a target, a guarantee or a gate, and the repository states no
  figure.
- Do not commit results to the repository. A pull request may quote raw output to show a
  comparison, labeled as non-authoritative, with the machine and command beside it.
