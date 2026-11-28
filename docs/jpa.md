---
title: JPA transactions
parent: Guides
nav_order: 14
---

# JPA transactions

`axiom-jpa` gives an Axiom application one JPA `EntityManager` and one transaction boundary per
request. The application owns the `EntityManagerFactory`; this module only opens, binds, completes
and closes EntityManagers. There is no annotation processing, no reflection and no new core SPI:
the EntityManager is reached through an instance accessor keyed by the request's existing `Context`.
Axiom's core stays JDK-only and no Jakarta Persistence type appears in an Axiom signature. Nothing
is opened or registered until the middleware is registered.

```kotlin
dependencies {
    implementation(platform("com.jsgalactic.axiom:axiom-bom:<version>"))
    implementation("com.jsgalactic.axiom:axiom-jpa")
}
```

## Registering the middleware

Build one adapter per factory and share it for the lifetime of the application:

```java
import com.jsgalactic.axiom.Axiom;
import com.jsgalactic.axiom.jpa.Jpa;
import jakarta.persistence.EntityManagerFactory;

EntityManagerFactory factory = /* created and closed by the application */ null;
var jpa = Jpa.builder().entityManagerFactory(factory).build();
var managers = jpa.entityManagers();

var app = Axiom.create();
app.use(jpa.transaction());          // global boundary, or pass as route middleware
app.get("/items", ctx -> {
    EntityManager entityManager = managers.get(ctx);
    return entityManager.createQuery("select i from Item i", Object.class).getResultList();
});
app.start();
```

`entityManagerFactory` deliberately takes `Object`. `build()` rejects a value that is not an
`EntityManagerFactory` with `IllegalArgumentException`; a missing value fails with
`IllegalStateException`. The adapter never closes the factory. The generic `get` call infers
`EntityManager` at the call site, so a reader gets a typed value without a JPA type in the Axiom API.
`jpa.entityManager(ctx)` is a shortcut for the same lookup. Both throw `IllegalStateException` when
no transaction middleware is active for the request, and `has(ctx)` answers whether one is bound.

The accessor is not static: each `Jpa` instance owns one, and an accessor only sees requests served
by that instance's middleware. It keeps an identity map from `Context` to the request's EntityManager
and removes the entry when the middleware finishes. Two concurrent requests get two EntityManagers
even on the same thread pool, and nothing is retained after the response. Handlers must use the
EntityManager only on the request thread and only before returning, as JPA requires.

## Transaction outcomes

`jpa.transaction()` opens one EntityManager per request, begins a resource-local transaction, runs
the rest of the chain and then chooses an outcome:

| Chain outcome | Outcome |
| --- | --- |
| Response below 400 without a problem content type | Commit |
| Exception of any kind | Roll back, unless a rule says commit |
| Response of 400 or above | Roll back |
| `application/problem+json` response | Roll back |
| An active JTA transaction was joined | Leave the outcome to the JTA owner |

The EntityManager is closed in a `finally` block after every outcome, including a failure from
commit or rollback. `afterError` performs the same cleanup defensively for a failure that bypassed
that block, so an error response decorated by an outer middleware cannot leave a live EntityManager
behind. See [middleware](middleware.md#middleware) and [problem responses](errors.md#problem-responses)
for how Axiom composes chains and maps exceptions.

JPA exceptions from commit or rollback are added as suppressed exceptions to an original handler
failure rather than replacing it. A handler that needs to affect the transaction outcome directly
can still call `getTransaction().setRollbackOnly()` on its own EntityManager; the adapter will not
commit a transaction marked rollback-only.

## Read-only requests

`jpa.readOnlyTransaction()` is the read-only variant. It opens the same per-request EntityManager,
sets `FlushModeType.COMMIT` so no write is flushed inside the handler, and rolls the transaction back
when the chain returns even on success. A handler that writes therefore changes nothing. It can be
registered on individual read routes while `transaction()` protects the write routes:

```java
app.get("/items", jpa.readOnlyTransaction(), ctx -> managers.get(ctx));
app.post("/items", jpa.transaction(), ctx -> repository.create(ctx, managers));
```

`Jpa.builder().readOnly()` also makes `transaction()` use these read-only semantics.

## Rollback rules

By default every exception rolls back. Rules are plain classes and match the exception's class and
its superclasses, with the nearest match winning; a rollback rule wins over a commit rule at the same
class. Exceptions matching no rule roll back.

```java
var jpa = Jpa.builder()
        .entityManagerFactory(factory)
        .rollbackOn(IllegalStateException.class)
        .commitOn(BusinessRejection.class)
        .build();
```

A commit rule still rethrows the exception after committing, so Axiom's error mapping decides the
response. Rules never override the rollback forced by an error response or by read-only mode.

## Joining a JTA transaction

A JTA-enabled application supplies a callback instead of adding a JTA dependency here:

```java
var jpa = Jpa.builder()
        .entityManagerFactory(factory)
        .jta(context -> jta.active(context))   // supplied by the application or its JTA adapter
        .build();
```

When the callback returns true for a request, the middleware calls `joinTransaction()` instead of
beginning a resource-local transaction. It does not commit or roll back that transaction: the owner
of the JTA transaction decides the outcome. The EntityManager is still closed when the chain ends.
The callback runs on the request thread and must be thread-safe and free of JTA types in its own
Axiom-facing signature.

## Limits

This is a transaction-boundary adapter, not an ORM, a connection pool or a JTA implementation. It
does not create or close the factory, manage persistence contexts beyond one request, retry a
failed transaction, or mark a joined JTA transaction rollback-only: that decision belongs to the
JTA owner. Handlers that keep an EntityManager or its results beyond the request must copy the data
they need. A joined JTA transaction can still be committed by its owner after a handler failure that
this adapter rolled back locally; applications that need rollback-only semantics must set them on
the JTA transaction itself.
