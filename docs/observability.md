# Observability

Axiom records what it does in a small, dependency-free `Metrics` SPI, reports liveness and
readiness through `Health`, and exposes the caller's W3C `traceparent` on the request context.
The SPI, `Health` and `TraceContext` are in core (`com.jsgalactic.axiom.observability`); the
optional `axiom-metrics` module adds an in-memory registry and Prometheus text output. None of it
adds a third-party dependency, and no tracing or metrics library appears in an API.

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
| `axiom.admission.rejected` | counter | `method`, `route`, `reason` | `capacity`, `shutdown`, `executor` or `queue_timeout`. Refusals at submission also count as `5xx` requests |
| `axiom.admission.queue.wait` | timer | `method`, `route` | Time a request spent queued, whether it was promoted, expired or cancelled |
| `axiom.admission.active` | gauge | none | Reserved or running executions |
| `axiom.admission.queued` | gauge | none | Requests waiting for capacity |

[Streamed responses](streaming.md) add four more series, none tagged by anything a request carries:

| Name | Kind | Tags | Meaning |
| --- | --- | --- | --- |
| `axiom.http.streams` | counter | `outcome` | Finished streams: `completed`, `client_disconnected`, `limit_exceeded`, `timeout`, `shutdown` or `failed` |
| `axiom.http.stream.bytes` | counter | none | Body bytes written by streams |
| `axiom.http.streams.active` | gauge | none | Streams whose head was sent and whose body is still running |
| `axiom.http.stream.backpressure` | counter | none | Stream writes that had to wait for a slow client |
| `axiom.http.tls.handshakes` | counter | `outcome` | TLS handshakes: `completed`, `failed`, `timeout`, `plaintext` or `closed` |
| `axiom.http.tls.reloads` | counter | `outcome` | Key material reloads: `completed` or `failed` |

The listener also reports its connections, so operators can compare open sockets with the
descriptor limit (see [HTTP listeners](http.md#resource-limits)):

| Name | Kind | Tags | Meaning |
| --- | --- | --- | --- |
| `axiom.http.connections` | gauge | `state` | Connections holding a regular slot (`open`) or the separate pool for connections that only linger after their last response (`lingering`). The sum over all listeners is the number of open client sockets, at most `maxConnections + maxLingeringConnections` per listener |

These cover requests that pass admission: HTTP listeners and `TestClient`. (The stream series
are recorded by HTTP listeners only.) Direct
`app.handle(...)` calls bypass admission and are not recorded. Timeouts count as `5xx` (504) and
queue timeouts and capacity refusals as `5xx` (503), matching the responses clients see.

### The registry

`MetricsRegistry` is for tests and for small deployments. It keeps at most 4096 series by default
(`MetricsRegistry.create(max)`); a lookup for a further series returns a working instrument that
is not stored and is counted in `droppedSeries()`, so putting user input into a tag cannot exhaust
memory. Names are dotted lowercase words, tag keys lowercase words (`le` is reserved), tag values
are truncated at 256 characters, and one name always has one kind and one set of tag keys. Tests
read values with `counterValue`, `gaugeValue` and `timerSnapshot`. Timers are histograms with the
fixed upper bounds 1 ms, 5 ms, 10 ms, 25 ms, 50 ms, 100 ms, 250 ms, 500 ms, 1 s, 2.5 s, 5 s and
10 s.

`PrometheusText.render(registry)` produces text exposition format 0.0.4: dots become
underscores, counters end in `_total`, timers become `<name>_seconds` histograms with `_bucket`,
`_sum` and `_count`, and tag values are escaped. Reads are not an atomic snapshot across series.

## Health and readiness

```java
var health = Health.builder(app)
        .liveness("event-loop", () -> true)
        .readiness("database", () -> pool.isValid(1))
        .timeout(Duration.ofSeconds(2))
        .build();
health.register(app, security.hasRole("ops"));
// on shutdown, before app.close():
health.beginDrain();
```

Liveness answers "restart me?" and should rarely have checks; readiness answers "send me
traffic?". `register` adds `GET /health/live` and `GET /health/ready` (under a group's prefix
when given one), each answering `{"status":"UP","checks":{"database":"UP"}}` with 200, or
`{"status":"DOWN",...}` with 503 and `Cache-Control: no-store`. Check names are restricted to
letters, digits and `_.-`, and failure reasons are logged, never sent.

- A check is `boolean isHealthy() throws Exception`; `false`, an exception and a timeout all mean
  DOWN. Checks of one probe run concurrently on virtual threads and share the probe timeout
  (default 2 s, at most 30 s); on timeout the thread is interrupted.
- A check that ignores interruption and is still running is not started again by the next
  probe; it reports DOWN until it returns, so slow dependencies cannot accumulate threads.
- Readiness is DOWN, without running checks, once `beginDrain()` was called or the application
  is no longer `RUNNING`. Closing a listener already makes the listener refuse new requests; call
  `beginDrain()` first (for example in a shutdown hook) and give the balancer time to notice, then
  close. The flag cannot be reset.

## Trace context

`ctx.traceContext()` returns the caller's `traceparent` as a `TraceContext` (`traceId`,
`parentId`, `flags`, `sampled()`), or empty. Parsing is strict: only version `00`, exactly
`00-<32 lowercase hex>-<16 lowercase hex>-<2 lowercase hex>`, nonzero ids. Uppercase digits,
whitespace, extra fields, other versions and joined duplicate headers are ignored and never
fail the request. `traceContext.child().traceparent()` gives the header for a downstream call.
The value is chosen by the client: use it for log correlation, not authorization, and never
as a metric tag. The framework request ID (`ctx.execution().requestId()`, sent as
`X-Request-ID`) is independent of it.

## Limitations

- No OpenTelemetry integration: Axiom neither creates spans nor exports traces.
- `tracestate` is not parsed or propagated, and incoming `traceparent` is not copied into
  responses or log records automatically.
- The registry has fixed histogram buckets, no quantiles, exemplars or help text, and no
  per-listener view; there is no JMX, StatsD or OTLP exporter.
- Metrics cover admitted requests, streams and connection counts, not bytes, body decoding or codec work.
- Health checks have no result caching, and there is no startup probe.

These are tracked in the limitations index, [#13](https://github.com/matrixjnr/axiom/issues/13).
