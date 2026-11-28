---
title: JDBC
parent: Guides
nav_order: 14
---

# JDBC

`axiom-jdbc` is an opt-in, JDK-only companion for applications that use JDBC directly. It bounds
how many requests may hold a database permit at once, counts connection checkouts through Axiom's
[`Metrics`](observability.md#metrics) SPI and contributes a readiness check. It is not a connection
pool and not an ORM: the application still owns the `DataSource`, its driver, its SQL and its
connections. Nothing is registered until the application asks for it.

```kotlin
dependencies {
    implementation(platform("com.jsgalactic.axiom:axiom-bom:<version>"))
    implementation("com.jsgalactic.axiom:axiom-jdbc")
    // the application also supplies its own JDBC driver
}
```

The module depends on `axiom-core` and on `java.sql` from the JDK. No third-party production
dependency is added and `axiom-core` is unchanged.

## Configuration and use

```java
import com.jsgalactic.axiom.Axiom;
import com.jsgalactic.axiom.jdbc.Jdbc;
import com.jsgalactic.axiom.observability.Health;

var orders = app.post("/orders", ctx -> createOrder(ctx));
var jdbc =
        Jdbc.builder(app, applicationOwnedDataSource)
                .maxConnections(16)
                .routeLimit(orders, 4)
                .wait(Duration.ofMillis(500))
                .readiness(Health.builder(app))
                .build()
                .register();

// handlers keep using ordinary JDBC; the wrapper only counts what they do
try (var connection = jdbc.dataSource().getConnection();
        var statement = connection.prepareStatement("INSERT INTO orders (id) VALUES (?)")) {
    statement.setLong(1, id);
    statement.executeUpdate();
}
```

`Jdbc.builder(app, source)` takes the application's `Application` lifecycle and its `DataSource`.
`register()` installs the gate as global middleware, on every route including ones registered
later. To bound only a group or a route, pass `jdbc.middleware()` to that group or route instead.
`JdbcOptions` carries the validated bounds and can be read back with `jdbc.options()`.

A request acquires one permit before the handler runs and releases it when the response is
available. Error responses go through `Middleware.afterError`, which releases the same permit; the
release is idempotent, so a mapped error, an unmapped exception, a cancellation and a timeout all
free exactly one permit. A drain stops new permits; requests already running may finish.

The aggregate cap limits all gated requests together. `routeLimit(route, n)` adds a second cap for
one registered route; both caps must have room, and a route cap cannot exceed the aggregate cap.
Permits are fair: a request that has waited longer is served first. At most 64 routes get their
own metric series; later ones share one `other` series.

### When the gate is full

`wait` bounds how long a request waits for a permit. It defaults to two seconds, accepts zero
(zero refuses immediately when full) and is capped at 30 seconds. A refused request never runs its
handler. It receives a 503 `application/problem+json` response with code `db_capacity`, a
`Retry-After` header, and one increment of `axiom.admission.rejected` with the fixed reason tag
`db_capacity`. That reason joins the runtime's existing bounded reasons in the
[metrics table](observability.md#metrics).

## Ownership and shutdown

The `DataSource` passed to the builder belongs to the application. The gate never closes it, never
changes its settings and never opens a connection on its own. A connection checked out through
`jdbc.dataSource()` belongs to the handler that checked it out: closing it closes the underlying
connection, as JDBC requires, and counts one release. A second `close()` on the same wrapper is a
no-op.

By default, closing the application leaves every connection alone. Call
`closeConnectionsOnShutdown()` when the application wants the gate to own the connections it left
open: closing the gate or the application then closes those tracked connections once. Even then
the application-provided `DataSource` is not closed; a pool must be closed by the code that created
it. The drain action registered on the application is nonblocking and only refuses new permits.

The gate bounds concurrent *requests* holding permits, not connections. A handler that opens two
connections still holds one permit; a background task that opens a connection outside a request
holds none. Put any code that must be bounded behind the middleware or behind the counted source.

## Health

`Jdbc.Builder.readiness(healthBuilder)` adds a readiness check named `database`, or call
`Health.builder(app).readiness("database", jdbc.healthCheck())` to choose the name yourself. Build
the `Health` after the gate so the check is registered.

The check opens a connection on the **unwrapped** source, runs one short `SELECT` through a
statement with a bounded timeout (one second by default, configurable with `probeTimeout`), and is
UP only when the statement returns a row. A driver failure, a failed query, an empty result, an
interrupted probe and a timeout are all DOWN. The report contains only check names and `UP`/`DOWN`:
SQL, URLs, credentials and exception messages never reach it or the logs. Health probes do not
consume permits and do not appear in the connection counters.

Set the health builder's own `timeout` shorter than the probe timeout if a stalled driver should be
abandoned quickly; both bounds are independent of the gate's request `wait`.

## Metrics

The gate and the counted source record through `Application.metrics()` unless `Builder.metrics(...)`
replaces it. Route series carry the registered method and template from a bounded set, never a raw
path, query or parameter; the wrapper's only tag is the configured `name` (default `database`,
1–64 lowercase tag characters).

| Name | Kind | Tags | Meaning |
| --- | --- | --- | --- |
| `axiom.jdbc.gate.acquired` | counter | `method`, `route` | Permits granted to requests |
| `axiom.jdbc.gate.released` | counter | `method`, `route` | Permits returned, once per acquisition |
| `axiom.jdbc.gate.wait` | timer | `method`, `route` | Time a granted request waited for its permit |
| `axiom.admission.rejected` | counter | `method`, `route`, `reason` | The fixed reason `db_capacity` when no permit arrived in time |
| `axiom.jdbc.connections.acquired` | counter | `name` | Successful checkouts on the counted source |
| `axiom.jdbc.connections.released` | counter | `name` | First `close()` of a checked-out connection |
| `axiom.jdbc.connections.waited` | counter | `name` | Checkouts that overlapped another outstanding checkout of this wrapper |
| `axiom.jdbc.connections.wait` | timer | `name` | Time spent inside the delegate's `getConnection` |

`waited` reports contention seen by the wrapper, not the delegate's internal queue depth; a pool
may wait without the wrapper knowing, and a second checkout may return at once. Use `wait` together
with the acquired count to judge whether database concurrency is the bottleneck. A failing
`Metrics` implementation is logged once and its measurements are dropped; requests keep running.

## Limitations

- The gate does not create, validate, configure or close a pool. Use the pool's own settings for
  connection lifetime and size; use this module to bound how many requests contend for it.
- The health probe is one short `SELECT` and does not verify transactions, schema or driver-specific
  readiness. Customize `probeQuery` for a database where `SELECT 1` is not valid.
- Per-route caps are keyed by registered route identity and are fixed when the gate is built; a
  route registered later cannot receive a cap from that gate.
- No SQL is parsed, rewritten, logged or attributed to a route. Applications are responsible for
  parameterization and for their own query bounds.
