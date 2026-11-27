---
title: Observability
parent: Guides
nav_order: 9
---

# Observability

Axiom records what it does in a small, dependency-free `Metrics` SPI, reports liveness and
readiness through `Health`, and exposes the caller's W3C `traceparent` on the request context.
The SPI, `Health` and `TraceContext` are in core (`com.jsgalactic.axiom.observability`); the
optional `axiom-metrics` module adds an in-memory registry and Prometheus text output. Two more
opt-in adapters connect the same SPI to third-party libraries: `axiom-otel` uses only the
OpenTelemetry API, and `axiom-metrics-micrometer` records through a Micrometer `MeterRegistry`.
Core and the starter add no third-party dependency, and no tracing or metrics library appears in
a core API.

> **Do not expose `/metrics`, `/health/live` or `/health/ready` to the public internet without
> authentication.** They reveal route templates, traffic volume, dependency names and whether an
> instance is draining. Serve them on a private network or listener, or pass security middleware
> when registering them (see [security](security.md)): `health.register(app, security.hasRole("ops"))`.

## Metrics

```java
var registry = MetricsRegistry.create();                 // axiom-metrics
var app = Axiom.create().metrics(registry);              // before start()
app.get("/metrics", PrometheusText.handler(registry), security.hasRole("ops"));
```

`Metrics` hands out `Counter`, `Gauge` and `Timer` instruments by name and alternating tag
keys and values. The default is `Metrics.NOOP`, which records nothing and allocates nothing.
Looking an instrument up may allocate and lock, so callers keep the instrument; recording
(`increment`, `add`, `record`) is thread-safe and allocation-free in the registry. A gauge moves
by deltas so several listeners can share one series. A `RuntimeException` from any `Metrics`
method is caught, logged once and the measurement dropped; metrics never fail a request.

**Tags are low-cardinality by rule.** The runtime tags only with the registered route's HTTP
method and template (`/users/:id`, never `/users/42`), a status class, and a fixed reason.
Requests that match no route share the series `method=none, route=unmatched`. Query strings,
headers, identities and `traceparent` values are never tags. The dispatcher keeps series for at
most 1024 distinct endpoints and pools later ones under `other`.

| Name | Kind | Tags | Meaning |
| --- | --- | --- | --- |
| `axiom.http.requests` | counter | `method`, `route`, `status_class` | Finished requests. `status_class` is `1xx`..`5xx`, `cancelled` (client gone or listener closed) or `unknown` |
| `axiom.http.request.duration` | timer | `method`, `route` | Time from submission to the final outcome, including queue wait |
| `axiom.admission.rejected` | counter | `method`, `route`, `reason` | `capacity`, `shutdown`, `executor`, `queue_timeout` or `stream_capacity` (a full aggregate or route stream lane). Refusals at submission and full stream lanes also count as `5xx` requests |
| `axiom.admission.queue.wait` | timer | `method`, `route` | Time a request spent queued, whether it was promoted, expired or cancelled |
| `axiom.admission.active` | gauge | none | Reserved or running executions |
| `axiom.admission.queued` | gauge | none | Requests waiting for capacity |

[Streamed responses](streaming.md) add four more series, tagged by the route's method and template
(the same bounded set as the request series) and nothing a request carries:

| Name | Kind | Tags | Meaning |
| --- | --- | --- | --- |
| `axiom.http.streams` | counter | `method`, `route`, `outcome` | Finished streams: `completed`, `client_disconnected`, `limit_exceeded`, `timeout`, `shutdown` or `failed` |
| `axiom.http.stream.bytes` | counter | `method`, `route` | Body bytes written by streams |
| `axiom.http.streams.active` | gauge | `method`, `route` | Streams whose head was sent and whose body is still running |
| `axiom.http.stream.backpressure` | counter | `method`, `route` | Stream writes that had to wait for a slow client |

TLS adds two more:

| Name | Kind | Tags | Meaning |
| --- | --- | --- | --- |
| `axiom.http.tls.handshakes` | counter | `outcome` | TLS handshakes: `completed`, `failed`, `timeout`, `plaintext` or `closed` |
| `axiom.http.tls.reloads` | counter | `outcome` | Key material reloads: `completed` or `failed` |

### Listeners, bytes and codecs

The listener reports what happens before and outside admission. Every series carries `listener`,
the name given with `ListenerOptions.builder().name("api")` (lowercase letters, digits and
underscores, at most 32 characters; `default` unless set). Name each listener of an application
that serves a different audience, such as a public and an admin port, to tell them apart; listeners
with the same name are summed. The name is chosen by the operator, never by a client.

| Name | Kind | Tags | Meaning |
| --- | --- | --- | --- |
| `axiom.http.connections` | gauge | `listener`, `state` | Connections holding a regular slot (`open`) or the separate pool for connections that only linger after their last response (`lingering`). The sum over all listeners is the number of open client sockets, at most `maxConnections + maxLingeringConnections` per listener (see [HTTP listeners](http.md#resource-limits)) |
| `axiom.http.connections.accepted` | counter | `listener` | Connections that got a slot |
| `axiom.http.connections.rejected` | counter | `listener`, `reason` | Connections closed at once: `limit` (all slots taken) or `shutdown` (the listener was closing) |
| `axiom.http.listener.bytes` | counter | `listener`, `direction` | Bytes read from (`in`) and written to (`out`) client sockets, including heads, framing and, on a TLS listener, the encrypted bytes |
| `axiom.http.listener.answers` | counter | `listener`, `status` | Responses the listener gave itself without admission, such as 400, 408, 413, 414 and 431; `status` is the code, from the fixed set the transport can produce |
| `axiom.codec.duration` | timer | `operation`, `media_type` | Time a codec spent decoding (`decode`) or encoding (`encode`) a body |
| `axiom.codec.failures` | counter | `operation`, `media_type` | Decodings or encodings that threw |

`media_type` is the type the installed codec declared (for example `application/json`), so its values
are bounded by the installed codecs and never come from a request's `Content-Type`. Codec series exist
only while metrics are installed, and are recorded by listeners and `TestClient` alike, for decoding
through `ctx.body` and for encoding of response bodies.

These decisions bound the cost of measuring: bytes are counted per listener, not per route, because a
route tag would multiply every series and stream bytes are already counted per route; a request that
dies before it is complete (a timeout while reading, a reset) shows in the connection and answer
series rather than as a request; and direct `app.handle(...)` calls bypass admission and are not
recorded.

The request series above cover requests that pass admission: HTTP listeners and `TestClient`. (The
stream series are recorded by both.) Timeouts count as `5xx` (504) and queue timeouts and capacity
refusals as `5xx` (503), matching the responses clients see.

### The registry

`MetricsRegistry` is for tests and for small deployments. It keeps at most 4096 series by default
(`MetricsRegistry.create(max)`); a lookup for a further series returns a working instrument that
is not stored and is counted in `droppedSeries()`, so putting user input into a tag cannot exhaust
memory. Names are dotted lowercase words, tag keys lowercase words (`le` is reserved), tag values
are truncated at 256 characters, and one name always has one kind and one set of tag keys. Tests
read values with `counterValue`, `gaugeValue` and `timerSnapshot`. Timers are histograms, by default
with the upper bounds 1 ms, 5 ms, 10 ms, 25 ms, 50 ms, 100 ms, 250 ms, 500 ms, 1 s, 2.5 s, 5 s and
10 s (`MetricsRegistry.DEFAULT_BUCKETS`).

Settings that differ from the defaults go through the builder, before the application starts:

```java
var registry = MetricsRegistry.builder()
        .maxSeries(8192)
        .timerBuckets(List.of(Duration.ofMillis(5), Duration.ofMillis(50), Duration.ofMillis(500), Duration.ofSeconds(5)))
        .timerBuckets("axiom.http.request.duration", List.of(Duration.ofMillis(1), Duration.ofMillis(10), Duration.ofSeconds(1)))
        .help("shop.orders", "Orders placed, by payment kind.")
        .build();
```

- `timerBuckets(bounds)` sets the bounds of every timer without its own; `timerBuckets(name, bounds)`
  sets those of one timer whatever its tags, including the runtime's own timers. Bounds are positive,
  strictly ascending and at most 64; a larger observation falls into the implicit `+Inf` bucket.
  A series takes the bounds in force when it is first created.
- `help(name, text)` is one line of at most 512 characters for the `# HELP` line. The runtime's own
  metrics have help text built in, which `help` overrides.
- The registry keeps **no quantiles and no exemplars**, by design. Quantiles computed inside one
  process cannot be aggregated across instances, whereas the histogram buckets can
  (`histogram_quantile` in Prometheus); and an exemplar is a per-request identifier, which must never
  become a tag and has no place in a bounded registry. Put exemplars in an exporter that has the
  request at hand.

`PrometheusText.render(registry)` produces text exposition format 0.0.4: a `# HELP` line where the
metric has help text, then `# TYPE`; dots become underscores, counters end in `_total`, timers become
`<name>_seconds` histograms with `_bucket`, `_sum` and `_count`, and tag values are escaped. The
registry reports on itself with `axiom_metrics_series` (gauge: stored series) and
`axiom_metrics_dropped_series_total` (counter: series refused at the limit), so a full registry is
visible and can be alerted on. Reads are not an atomic snapshot across series.

## OpenTelemetry (`axiom-otel`)

The opt-in `axiom-otel` module builds against `io.opentelemetry:opentelemetry-api` only. It never
installs an SDK, selects a sampler or ships an exporter: the application supplies an
`OpenTelemetry` instance and owns the export pipeline.

```java
var tracing = Tracing.create(openTelemetry);          // your SDK, or OpenTelemetry.noop()
var app = Axiom.create()
        .metrics(OtelMetrics.create(openTelemetry))
        .use(tracing);
```

`Tracing.create` returns middleware that starts one `SERVER` span per admitted request. It reads
the caller's W3C `traceparent` and `tracestate` with the OpenTelemetry propagator, so a valid
remote header becomes the parent and an absent or invalid header produces a root span. The span
name is `METHOD route-template` (`GET /users/:id`) for a matched route and the method for a
router-generated answer. Its attributes are the stable HTTP conventions `http.request.method`,
`http.route` (the template, not the raw path) and `http.response.status_code`. Query strings,
header values and identities are never attributes. The tracer and the meter use the instrumentation
scope `com.jsgalactic.axiom.otel`.

A successful response records its status before the span ends. An exception records the
exception, sets `ERROR`, records the status of the `AxiomException` (or 500 when there is none),
and ends the span before the exception leaves the middleware. Error responses do not travel back
through the chain, so `Middleware.afterError` adds
`traceresponse: 00-<trace id>-<span id>-<flags>` for the span created for the failed request. A
failure with no mapped error response has no response to decorate and adds no header. The created
span is current while the chain runs, so OpenTelemetry instrumentation on the request thread sees
it as its parent.

`OtelMetrics.create` maps the `Metrics` SPI to OTel instruments: counters to dimensionless
`LongCounter`s, gauges to `LongUpDownCounter`s and timers to `DoubleHistogram`s in seconds.
Histograms carry explicit bucket advice of 1 ms, 5 ms, 10 ms, 25 ms, 50 ms, 100 ms, 250 ms,
500 ms, 1 s, 2.5 s, 5 s and 10 s (`OtelMetrics.DEFAULT_BUCKETS`), the bounds of the in-memory
registry. A name keeps one kind and one set of tag keys; tag values remain caller-chosen, so keep
them to route templates, methods and other fixed sets. Readers, views and exporters belong to the
application.

## Micrometer (`axiom-metrics-micrometer`)

The opt-in `axiom-metrics-micrometer` module depends on `io.micrometer:micrometer-core` only. It
registers instruments in a registry the application supplies:

```java
var metrics = MicrometerMetrics.create(registry);     // any MeterRegistry the application owns
var app = Axiom.create().metrics(metrics);
```

Counters and gauges map to Micrometer counters and gauges; a gauge holds an `AtomicLong`, so
deltas from several components add into one value. Timers are registered with service-level
objectives of 1 ms through 10 s (`MicrometerMetrics.DEFAULT_BUCKETS`, the same bounds as the
registry) and a published percentile histogram. Nothing is registered until a metric is looked
up, and the adapter adds no HTTP endpoint, Prometheus renderer, StatsD client or JMX bridge.
Micrometer registries and an OpenTelemetry Collector provide those publication paths.

## Health and readiness

```java
var app = Axiom.create().closeOnJvmShutdown(Duration.ofSeconds(10)); // drain delay, see below
var health = Health.builder(app)
        .startup("warm-up", () -> cache.isWarm(), Duration.ofSeconds(5))
        .liveness("event-loop", () -> true)
        .readiness("database", () -> pool.isValid(1))
        .timeout(Duration.ofSeconds(2))
        .cacheFor(Duration.ofSeconds(5))
        .build();
health.register(app, security.hasRole("ops"));
```

Startup answers "has it finished starting?", liveness "restart me?" (and should rarely have checks) and
readiness "send me traffic?". `register` adds `GET /health/startup`, `GET /health/live` and
`GET /health/ready` (under a group's prefix when given one), each answering
`{"status":"UP","checks":{"database":"UP"}}` with 200, or `{"status":"DOWN",...}` with 503 and
`Cache-Control: no-store`. Check names are restricted to letters, digits and `_.-`, and failure reasons
are logged, never sent.

- A check is `boolean isHealthy() throws Exception`; `false`, an exception and a timeout all mean
  DOWN. Checks of one probe run concurrently on virtual threads and each is bounded by the probe
  timeout (default 2 s, at most 30 s) or by its own: `readiness(name, check, Duration)`, and likewise
  for `liveness` and `startup`. On timeout the thread is interrupted.
- A check that ignores interruption and is still running is not started again by the next
  probe; it reports DOWN until it returns, so slow dependencies cannot accumulate threads.
- **Startup.** The startup probe is DOWN until the application is running and every startup check
  passes, then UP for good: later probes answer UP without running the checks, so an expensive
  warm-up check costs nothing afterwards. Point an orchestrator's startup probe (Kubernetes
  `startupProbe`) at it so liveness and readiness probes are held back until it succeeds.
- **Caching.** `cacheFor(Duration)` (zero by default, at most one minute) keeps each check's result,
  healthy or not, so a frequently polled expensive check runs at most once per interval however many
  probers ask. A check that was skipped because an earlier run is still going is not kept, and
  neither is anything while draining or when the application is not running. Cached results delay
  the report of a change by up to the interval, so keep it well under the prober's period times
  its failure threshold.
- **Draining.** Readiness is DOWN, without running checks, once `beginDrain()` was called or the
  application is no longer `RUNNING`; the flag cannot be reset. Building a `Health` registers
  `beginDrain()` with the application (`Application.onDrain`), so with
  `app.closeOnJvmShutdown(Duration drainDelay)` a `SIGTERM` or normal JVM exit first turns
  readiness DOWN, waits the drain delay for the balancer to notice, and only then closes the
  application and its listeners (which still get their own shutdown grace period). Size the delay
  to your balancer's probe period times its failure threshold, and keep delay plus grace under the
  container's termination grace period (`terminationGracePeriodSeconds`). Without
  `closeOnJvmShutdown`, or when closing from your own code, call `beginDrain()` yourself and wait
  before `close()`; closing a single listener does not drain the application's other listeners.

## Trace context

`ctx.traceContext()` returns the caller's `traceparent` as a `TraceContext` (`traceId`,
`parentId`, `flags`, `sampled()`, `traceState`), or empty. Parsing is strict: only version `00`, exactly
`00-<32 lowercase hex>-<16 lowercase hex>-<2 lowercase hex>`, nonzero ids. Uppercase digits,
whitespace, extra fields, other versions and joined duplicate headers are ignored and never
fail the request. The value is chosen by the client: use it for log correlation, not authorization,
and never as a metric tag. The framework request ID (`ctx.execution().requestId()`, sent as
`X-Request-ID`) is independent of it.

**Passing the trace on.** `traceContext.child()` keeps the trace id, flags and trace state and gives
this service a new span id; `child().headers()` is the map of `traceparent` (and `tracestate`, when
there is one) to send on an outgoing call.

**`tracestate`.** The caller's `tracestate` is read together with a valid `traceparent` (and ignored
without one, as the specification says) into `TraceContext.traceState()`. Parsing is strict and
all-or-nothing: at most 32 members and 512 characters in all (the size every implementation must
propagate; a longer header is ignored), lowercase simple or `tenant@system` keys, values of up to 256
printable ASCII characters without `,` and `=`, unique keys. Anything else leaves the state empty and
never fails the request. Axiom does not interpret the members; `TraceState.with(key, value)` sets one
and moves it to the front, as the specification asks of a vendor that changes its entry.

**Logs.** The framework's own log messages about a request (a failing handler, a mapped failure, an
aborted stream) name the request ID and, when the caller sent a valid `traceparent`, `trace=<trace id>`
next to it. The id passed validation, so it cannot inject lines, but it remains the caller's choice. There
is no thread-local map: for your own log lines `ctx.correlation()` returns the same text
(`<request id>` or `<request id> trace=<trace id>`), for example
`log.info("charged " + ctx.correlation())`.

**Responses.** Nothing is added to responses unless you ask: `app.use(TraceContext.responseHeader())`
adds `traceresponse: 00-<trace id>-<span id>-<flags>` to responses to requests that carried a valid
`traceparent`, with a span id of this service, so a client can find the request in the server's
logs. Requests without a trace get no header, because Axiom starts no traces of its own. Like all
middleware it does not decorate responses made from exceptions.

## Scope

Axiom starts no traces of its own: it parses `traceparent` for correlation, and creating spans
requires the opt-in adapter. The core SPI records measurements but publishes nothing, so an
application chooses a registry or adapter (or writes its own `Metrics`). Publication through
Prometheus, OTLP, StatsD or JMX is a property of the chosen registry, adapter or Collector, not a
separate Axiom module.
