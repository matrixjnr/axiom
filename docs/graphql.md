---
title: GraphQL
parent: Guides
nav_order: 11
---

# GraphQL

`axiom-graphql` is an opt-in HTTP adapter over
[graphql-java](https://github.com/graphql-java/graphql-java). It turns an application-owned
`graphql.GraphQL` instance into an Axiom `Handler`. Axiom's core gains no SPI, no graphql-java type
in its API and no third-party dependency; graphql-java and its own libraries stay inside this
module. Nothing is served until the handler is registered on a route.

```kotlin
dependencies {
    implementation(platform("com.jsgalactic.axiom:axiom-bom:<version>"))
    implementation("com.jsgalactic.axiom:axiom-graphql")
}
```

The module's own public API contains no graphql-java type. The schema is supplied as SDL, and the
application's runtime is passed as `Object` and checked when the handler is built. This keeps the
adapter swappable and keeps third-party types out of Axiom signatures; it also means a wrong value
fails at startup with a clear `IllegalArgumentException` rather than at compile time.

## Registering a route

```java
import com.jsgalactic.axiom.Axiom;
import com.jsgalactic.axiom.graphql.GraphQl;
import com.jsgalactic.axiom.graphql.GraphQlOptions;
import graphql.GraphQL;

String sdl = "type Query { greeting: String }";
GraphQL runtime = /* built by the application: schema, runtime wiring, data fetchers */ null;

var graphql = GraphQl.builder()
        .schema(sdl)
        .graphQL(runtime)
        .options(new GraphQlOptions(64 * 1024, 10, 100, false, false))
        .build();

var app = Axiom.create();
app.post("/graphql", graphql.handler());      // or graphql.register(app, "/graphql")
```

`register(app, path, middleware...)` registers `POST`, and `GET` when `getQueries()` is enabled,
on the same path. It returns the POST route and takes route middleware like any other route, so
authentication and policy can wrap the endpoint. `handler()` is available when the application
wants to register the methods itself. Without either call, the route does not exist and answers
404. `HEAD` and `OPTIONS` follow the router's normal rules: `HEAD` falls back to `GET` when GET is
registered, and `OPTIONS` answers 204 with `Allow` (POST only gives `OPTIONS, POST`).

When no runtime is supplied, the adapter builds a read-only graphql-java runtime from the SDL with
default property fetchers. That is useful for simple queries and tests, but a production API
should pass its own runtime: its data fetchers, instrumentation, execution strategies and scalars
are the application's responsibility. The SDL is parsed and validated at build time but does not
replace or rewrite the runtime's schema, so build the runtime from the same SDL. The adapter never
mutates that instance; it derives a `GraphQL.transform` that composes its own instrumentation after
the application's.

## Request format

A `POST` body must be `application/json` and a single JSON object:

```json
{"query":"query Greet($name: String!) { greet(name: $name) }","operationName":"Greet","variables":{"name":"Ada"}}
```

`query` is required; `variables` must be an object; `operationName` and `variables` are optional.
Unrecognized members, duplicate keys, malformed JSON, invalid UTF-8 and a missing or wrong
Content-Type are rejected. With `getQueries()` enabled, `GET /graphql?query=...` accepts the same
three values as query parameters (`variables` is a JSON object string). GET never runs mutations,
which graphql-java rejects as an invalid query. Requests are answered with `application/json`,
`Cache-Control: no-store`; errors are `application/problem+json`.

## Bounds and instrumentation

`GraphQlOptions` is a plain record with bounded values:

| Setting | Default | Effect |
| --- | --- | --- |
| `maxBodyBytes` | 65536 | Request body and query string bound; over the limit answers 413 |
| `maxDepth` | 10 | graphql-java `MaxQueryDepthInstrumentation`; over the limit answers 400 |
| `maxComplexity` | 100 | graphql-java `MaxQueryComplexityInstrumentation`; over the limit answers 400 |
| `introspection` | `false` | Rejects `__schema` and `__type` fields with 400 when off |
| `getQueries` | `false` | Registers (and allows) GET queries only when on |

Every limit is validated at construction. The depth and complexity checks are graphql-java
instrumentation attached to the derived runtime, so they see fragments, aliases and variables as
graphql-java does. An application's own instrumentation is retained and runs before these checks.

Each request gets a fresh `DataLoaderRegistry`. The application can supply a thread-safe factory
whose result is a `graphql.execution.DataLoaderRegistry` (typed as `Object` at the API boundary):

```java
var graphql = GraphQl.builder().schema(sdl).graphQL(runtime)
        .dataLoaders(() -> {
            var registry = new org.dataloader.DataLoaderRegistry();
            registry.register("users", org.dataloader.DataLoaderFactory.<String, String>newDataLoader(keys -> ...));
            return registry;
        }).build();
```

The registry is never shared between requests, so per-request caches and batch counts cannot
leak. graphql-java dispatches the loaders for each execution; the adapter does not dispatch them
itself. A factory that returns anything other than a DataLoader registry is a 500 with a fixed
code.

## Errors and deadlines

The adapter does not put GraphQL error messages, exception classes, stack traces or schema
internals in a response. Client mistakes become 400 problems with fixed codes such as
`graphql_invalid_query`, `graphql_invalid_request`, `graphql_query_too_deep`,
`graphql_query_too_complex`, `graphql_introspection_disabled` and `graphql_get_disabled`. A failure
inside a resolver becomes a 500 `graphql_execution_failed`. The cause remains available to the
application's own logging through its runtime wiring.

The Axiom `ExecutionContext` is placed in graphql-java's `GraphQLContext` under the key
`com.jsgalactic.axiom.execution`, where data fetchers and application instrumentation can read its
request ID and remaining time. The adapter's own instrumentation cancels the execution if the
deadline has expired before an operation or an execution level starts, mapping that to 504
`graphql_deadline_expired`; an interruption or cancellation during execution cancels the
`ExecutionInput` as well. See
[execution and deadlines](execution.md). A long-running resolver must still honor interruption:
the runtime's thread is interrupted when its deadline expires, and graphql-java cannot undo work
that ignores that signal.

## Limits of this adapter

Only JDK value shapes are serialized in the `data` object: maps with string keys, lists, arrays,
strings, numbers, booleans, nulls and enums. An application-specific scalar or arbitrary object
is written as `null`; bind a custom scalar to a JDK value in the runtime wiring if a client needs
it. Subscriptions and the multipart file-upload protocol are not served: only query and mutation
operations over a bounded JSON envelope are supported. The response is always one JSON document,
not an incremental or multipart stream.
