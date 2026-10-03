---
title: Benchmarks
parent: Operations
nav_order: 5
---

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

Smoke-run numbers are meaningless and must not be quoted.

## Methodology

- Run on an otherwise idle machine with a fixed JDK, garbage collector, heap size
  and CPU frequency policy; record OS, hardware, JDK, JVM arguments, commit and the
  full command alongside any exported result (`-rf json -rff result.json`).
- Compare changes with the same settings on the same machine, with enough warmup
  and forks that the reported error is small relative to the difference.
- Inputs are built once in `@Setup`; results are returned to JMH so the work is
  not eliminated. Network I/O, startup and request construction are excluded.
- Do not commit results to the repository.
