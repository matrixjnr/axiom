---
title: Server benchmark protocol
parent: Operations
nav_order: 6
---

# Server benchmark protocol

This protocol defines the evidence needed for the external HTTP benchmark gate
in [the roadmap](https://github.com/matrixjnr/axiom/issues/18). The current
[JMH suite](benchmarks.md) measures components inside the process. A server
comparison must also exercise parsing, sockets, response writes, backpressure,
connection management and any application dependencies included in the workload.

## Reference study

[The Rust/SQLite challenge submission](https://github.com/arjaythedev/twelve-dollar-server-challenge/pull/19)
is a useful example of documenting a specialized server and its measurements.
This review pins revision `bd517fa7c8a57b135579ae02fdddb6196b4cd9a4`:

- [Implementation and current measurements](https://github.com/Scooter1337/twelve-dollar-server-challenge/blob/bd517fa7c8a57b135579ae02fdddb6196b4cd9a4/submissions/rust-epoll-scooter1337/README.md).
- [I/O ownership, earlier measurements and rejected experiments](https://github.com/Scooter1337/twelve-dollar-server-challenge/blob/bd517fa7c8a57b135579ae02fdddb6196b4cd9a4/submissions/rust-epoll-scooter1337/IO_URING.md).
- [Benchmark orchestration](https://github.com/Scooter1337/twelve-dollar-server-challenge/blob/bd517fa7c8a57b135579ae02fdddb6196b4cd9a4/submissions/rust-epoll-scooter1337/bench/compare_all.py).

Its design uses registered sockets, completion batching, bounded reusable buffers
and connection generations to reject stale completions. It serializes SQLite
operations and sends successful grouped replies after commit. These choices serve
one application with a specific database and correctness contract; the reported
results are the author's measurements, and have not been reproduced for Axiom.

The transferable lessons are explicit ownership, bounded retained memory,
correctness checks, reproducible inputs and evidence for rejected changes. Changes
to Axiom's transport, pooling or request dispatch need their own profile and
controlled comparison. A different I/O API alone does not establish an improvement.

## Define equivalent work

Write the workload contract before implementing adapters for Axiom, Spring Boot,
Micronaut, Quarkus and Javalin. Pin each adapter's source, runtime and dependencies.
Compare one change at a time within Axiom before making framework comparisons.

| Workload | Required agreement |
| --- | --- |
| Plain response | Status, bytes, headers, keep-alive and connection limits |
| Routed JSON | Path captures, JSON input/output, payload size and validation policy |
| Authenticated request | Algorithm, signature/expiry checks and claim policy on every request |
| Persistent application | Schema, indexes, dataset, query semantics, transaction boundary and acknowledgement policy |
| Slow client or overload | Offered load, expected rejection policy, deadlines and bounded resource use |

Measure direct HTTP and reverse-proxy deployments as separate configurations.
Include the proxy's CPU and memory in the server budget when one is used. Keep
TLS configuration and negotiated protocol equivalent. Publish intentional
differences instead of treating their results as interchangeable.

## Establish correctness first

Run the same checks against every adapter before warmup, and retain their output.
Check response content as well as status: a fast wrong answer invalidates the run.
Cover malformed routes, authentication failures, JSON edge cases, request framing,
`Expect: 100-continue`, HEAD, keep-alive reuse and connection shutdown. Add overload
and stalled-client checks for resource bounds.

Persistent workloads must check live reads, acknowledged writes after restart,
duplicate-write semantics and failed commits. State the durability policy precisely;
successful commit and survival after power loss are different guarantees. Use fresh
copies of the same dataset per trial and record their hashes. Keep copying and
warmup policy identical, including which memory budget is charged for file cache.

## Control the experiment

Record the host, kernel, CPU model, topology, CPU placement/quota, memory and swap
limits, file-descriptor limit, network path, JDK/runtime, GC, heap, application
settings, tool versions and full commands. Keep server and load-generator CPU
resources separate. Build before timing; record source and executable/container
hashes and whether the source tree was clean.

Choose load levels before comparing variants: low load for fixed overhead,
increasing concurrency for saturation, and a fixed offered rate for latency under
load. Report connection count, active users and requests per second separately.
Record client CPU and errors so a saturated generator or network limit is visible.

For two variants A and B, use at least four complete paired trials with order
`AB`, `BA`, `BA`, `AB`. Predeclare additional blocks if more precision is needed.
For multiple frameworks, publish a fixed, balanced schedule before running it.
Use equal warmup and measurement durations and reset mutable state between trials.
Retain failed and interrupted attempts; rerun a complete pair with its reason
recorded rather than silently selecting successful rows.

Treat unexpected socket, status or semantic errors as a failed comparison. Planned
overload rejections belong to a separate workload with explicit expected outcomes.
Report each trial, paired changes, medians and ranges. Keep warmup and measured
observations separate, and label exploratory runs separately from confirmation.

## Retain evidence

Save the following outside generated build output before cleaning the checkout:

- Pinned workload/adapter sources, dependency locks, build commands and artifact identities.
- Correctness results, workload seeds/hashes, trial schedule and all attempted runs.
- Raw client output with completed request counts, durations, response checks,
  unexpected errors and p50/p95/p99 latency.
- Process RSS, process/heap allocation and GC, plus total server memory including
  native buffers, kernel/socket memory and file cache where the platform exposes it.
- Server and client CPU, actual clock observations where available, I/O counters,
  profiles, configuration and logs needed to explain anomalies.

Process RSS is not total server memory. A cgroup limit is not evidence that the
process alone used that amount, and a warm allocation counter may omit native
allocations and cold or exceptional paths. State the scope of every counter.

Profile a representative baseline, form one hypothesis, implement one change and
repeat the same correctness and comparison procedure. Keep rejected experiments
and their evidence available. Claims must name the tested workload, environment,
revision and variability; a harness smoke run establishes only that it executes.

## Current status

The controlled external runner, equivalent framework adapters and measured report
remain pending in [the external benchmark evidence issue](https://github.com/matrixjnr/axiom/issues/188)
under the benchmark gate. This document defines their acceptance criteria. It
contains no Axiom throughput, capacity or cross-framework ranking.
