---
title: JTA transactions
parent: Guides
nav_order: 14
---

# JTA transactions

`axiom-jta` is an opt-in module that puts a JTA transaction boundary around a handler. It uses the
JTA API only; the transaction manager (Narayana, Atomikos, an application server's, or a test
double) is owned and configured by the application. Neither core nor this module bundles a provider,
and no JTA type appears in an Axiom signature.

```kotlin
dependencies {
    implementation(platform("com.jsgalactic.axiom:axiom-bom:<version>"))
    implementation("com.jsgalactic.axiom:axiom-jta")
    // Also add the application's own JTA provider; this module does not choose one.
}
```

The transaction manager is passed to the builder as an `Object` and checked when the builder is
created. A wrong value fails at configuration time, with an exception that names the expected type
without printing the supplied object. Nothing is bound until the middleware is registered on a
route or with `app.use`.

## Registering a boundary

```java
import com.jsgalactic.axiom.Axiom;
import com.jsgalactic.axiom.jta.Jta;
import java.time.Duration;

var jta = Jta.builder(applicationOwnedTransactionManager)
        .timeout(Duration.ofSeconds(5))
        .build();

var app = Axiom.create();
app.use(jta.transaction());                  // every request routed by this application
app.post("/orders", ctx -> createOrder(ctx));  // or pass jta.transaction() to one route
```

The middleware suspends whatever transaction was already associated with the request thread,
begins a new transaction, runs the rest of the chain, then completes it:

- A response returned by the chain with a status below 400 commits.
- A response with status 400 or above, an exception leaving the chain, or a handler that calls
  `setRollbackOnly()` rolls back.
- Completion restores the previously associated transaction in every case, including failures in
  begin, commit or rollback. If there was no previous transaction, none is left associated.

An error handler that maps an exception to a successful answer still sees a rolled-back transaction
if the exception left the chain before the error handler ran. Use `noRollbackOn(...)` for those
exception types (see [rollback rules](#timeout-rollback-rules-and-status-mapping)).

A failure in the boundary itself becomes an `application/problem+json` response with a fixed code:
503 `transaction_unavailable` for begin and rollback failures, 409 `transaction_conflict` for a
failed commit and 500 `transaction_failed` for suspend, resume or configuration failures. The
provider's message, exception class and stack trace are attached as a cause for the framework's logs
and never enter the response. See [errors](errors.md).

## Per-request transaction context

Handlers reach the transaction context through the context object they already receive; no new core
SPI and no thread local are involved:

```java
import com.jsgalactic.axiom.jta.Jta;
import com.jsgalactic.axiom.jta.TransactionContext;

var transaction = Jta.context(ctx).orElseThrow();
transaction.setRollbackOnly();
boolean active = transaction.active();
var status = transaction.status();      // plain TransactionStatus enum
var outcome = transaction.outcome();    // PENDING, COMMITTED, ROLLED_BACK or FAILED
```

`active()` is true only while the innermost boundary has a transaction. `status()` maps the
provider's status to `ACTIVE`, `MARKED_ROLLBACK`, `COMMITTED`, `ROLLED_BACK`, `INACTIVE` or `UNKNOWN`;
a failed status query reports `UNKNOWN` rather than failing the request. `timeout()` returns the
configured duration when one was set. `outcome()` stays available after completion so an error
handler can decorate its answer; the middleware adds `X-Axiom-Transaction` to an error response with
the plain outcome name.

The context lives only on the request thread and only until the request ends. A later request on
the same thread cannot see the earlier one's transaction, including when both use route middleware
rather than global middleware.

## Nested boundaries

Registering the same or another `Jta` middleware both globally and on a route creates nested,
independent scopes. The inner scope suspends the outer transaction and begins its own; on exit it
commits or rolls back, then resumes the outer transaction, which completes last. Handlers see the
innermost scope through `Jta.context(ctx)`. Use this for work that must commit independently of an
outer boundary; a handler that should join the outer transaction needs only one boundary.

An association created before Axiom entered the request is restored too: the middleware suspends
it, begins its own transaction and resumes the original handle at the end.

## Timeout, rollback rules and status mapping

JTA expresses timeouts in whole seconds. `timeout(Duration)` accepts a positive duration of at most
one day and rounds up to seconds, with a minimum of one second; the configured duration is what
`TransactionContext.timeout()` reports. Without a call the provider's own timeout is used.

By default every exception and every problem response rolls back. Rules change what happens when
an exception leaves the chain; they are evaluated in the order added and the last matching rule
wins:

```java
var jta = Jta.builder(transactionManager)
        .noRollbackOn(BusinessException.class)       // commit these when an error handler maps them
        .rollbackWhen(t -> needsRollback(t))          // a later rule can override that
        .problemStatus(TransactionFailure.COMMIT, 500)
        .build();
```

`rollbackOn(Class)`, `noRollbackOn(Class)`, `rollbackWhen(Predicate)` and `commitWhen(Predicate)`
are the four rule forms. A rule that throws is treated as "roll back" and the rule's failure is
attached to the request exception for logs. Rules do not change the decision for a problem response
returned by the chain: that always rolls back.

`problemStatus(TransactionFailure, int)` overrides one boundary failure's status and accepts only
409, 500 and 503, so an application can treat a commit failure as a conflict or a server error.
`TransactionFailure` is a plain enum (`SUSPEND`, `BEGIN`, `COMMIT`, `ROLLBACK`, `RESUME`, `MARK`,
`INACTIVE`, `TIMEOUT`); no JTA type is needed to configure it.

## Integration with a future JPA adapter

A module such as `axiom-jpa` can ask whether a request has an active JTA transaction without
compiling against `axiom-jta`. The lookup service is published with `ServiceLoader` under the name
`com.jsgalactic.axiom.jta.spi.JtaTransactionLookup`; its three methods take and return only `Object`,
`String` and `boolean`:

```java
Class<?> type = Class.forName("com.jsgalactic.axiom.jta.spi.JtaTransactionLookup");
Object lookup = ServiceLoader.load(type).iterator().next();
boolean active = (boolean) type.getMethod("active", Object.class).invoke(lookup, ctx);
String status = (String) type.getMethod("status", Object.class).invoke(lookup, ctx);
```

Pass the request's Axiom context as the argument. The lookup is read-only: it never begins,
completes or marks a transaction. When `axiom-jta` is not on the class path there is no service and
no behavior to rely on, so an integration must treat that as "no JTA boundary".

## Limitations

- No transaction manager is bundled. The application supplies a JTA 2.0 `TransactionManager` and
  owns its configuration, data sources and XA resources; this module calls only the manager's
  boundary methods.
- Nested scopes are independent (suspend and begin), not joins of an outer transaction.
- Timeout granularity is one second, as defined by JTA.
- Transaction association is request-thread-bound. Work handed to another thread, a streamed
  response body or a scheduled task does not inherit it; pass needed work into the handler instead.
- The response-status rule sees only responses that pass back through the middleware. Errors are
  mapped after middleware returns, so use exception rules to decide their transaction outcome.
- The provider callback is a read-only discovery point by name, not a core SPI. Integrations that
  want a typed contract can declare their own matching interface and call the service normally when
  they do have the module on their compile classpath.
