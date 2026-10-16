---
title: Middleware
parent: Guides
nav_order: 2
---

# Middleware, route groups and error handlers

Middleware run code around handlers, route groups share a path prefix and
middleware, and error handlers map exceptions to responses. The
[notes example](../examples/rest-api) uses all three.

```text
listener / TestClient: parse, admit, start deadline
  -> app.handle: body limit (413), CONNECT (501)       no middleware yet
  -> router match
       matched:   global -> group (outer..inner) -> route -> handler
       unmatched: global -> router answer or its custom handler (404, 405, OPTIONS 204, 501)
  <- exception?  error handler (nearest class) or built-in problem mapping,
                 then Middleware.afterError, innermost first
  <- Accept check (406), HEAD body removal
  <- listener: serialization, X-Request-ID, write
```

## Goals

- Code that runs around handlers (logging, timing, authentication, security
  headers) without repeating it in every handler.
- Shared path prefixes and middleware for related routes.
- Application-defined mappings from exceptions to responses that never leak
  internals.
- Everything decided at `start()`: no chain is built per request.

Middleware use synchronous continuations. They run after admission and do not
wrap errors produced by the listener itself.

## Middleware

> **Decorate error responses with `afterError`.** When a handler or middleware
> throws, the exception passes through every middleware and becomes a response
> only afterwards (problem response, error handler response or 406), so code
> after `next.run()` does not run for it. A middleware that must decorate every
> response, such as security headers or CORS, overrides `afterError` as well. The
> bundled `SecurityHeaders` and `Cors` do.

```java
@FunctionalInterface
public interface Middleware {
    Response handle(Context ctx, Next next) throws Exception;

    default Response afterError(Context ctx, Response response) { return response; }

    interface Next {
        Response run() throws Exception;
    }
}
```

```java
Middleware headers = new Middleware() {
    public Response handle(Context ctx, Next next) throws Exception {
        return next.run().withHeader("X-Content-Type-Options", "nosniff");
    }
    public Response afterError(Context ctx, Response response) {
        return response.withHeader("X-Content-Type-Options", "nosniff");
    }
};
```

`afterError` is called once for each response that was produced from an exception,
for every middleware that was entered when the failure happened, innermost first:

- **Which responses:** the problem response of an `AxiomException`, the response of
  an error handler (including one that translated the exception), the generic 500
  of a failing error handler, and the 406 for a response type the client does not
  accept. A request that never reaches the chain (413, CONNECT, listener errors),
  an exception that nothing maps (it propagates from `app.handle` and becomes the
  generic 500 over HTTP) and a cancelled or expired request do not reach it.
- **Which middleware:** exactly those on the stack when the exception was thrown.
  A middleware an outer one short-circuited before was never entered and is not
  called. A middleware that catches an exception and throws another one is
  counted, and so are the ones outside it; the ones it had called inside are not.
  When the 406 is decided every middleware has returned, and all of them are called.
- **Not called** for responses that come back through `next.run()`, including ones
  a handler or middleware builds itself with an error status, and for the router's
  404, 405 and 501 answers, which already flow through global middleware as
  ordinary responses. A stream body that fails after its head was sent never reaches error
  handlers or `afterError`: the connection is closed without a second response. An exception thrown by a custom `notFound` handler is an
  ordinary handler exception.
- It runs on the request's thread under the deadline and must not throw: an
  `AxiomException` from it answers with its own undecorated problem response, `null`
  and any other exception fail the request like an unexpected exception.
- A middleware that already catches exceptions and builds responses itself keeps
  working; it does not need `afterError` for those, and its own response is
  decorated by the middleware outside it as before.

`next.run()` runs the rest of the chain (more middleware, then the handler) and
returns its response. A middleware that returns without calling it short-circuits:
the handler never runs and the returned response is used. A middleware can change
the response it gets back (`next.run().withHeader(...)`), and it sees exceptions
from the rest of the chain as exceptions from `next.run()`, which it can catch,
translate or let propagate.

- The handler's result reaches middleware as a `Response` (`String` to text,
  `null` to 204, using the context status). `ctx.json(value)` and
  `ctx.encode(mediaType, value)` encode immediately and snapshot the resulting
  bytes. Middleware see owned, encoded bodies and can short-circuit with the
  same methods. Arbitrary objects returned without encoding are rejected.
  The Accept check (406, safe methods only) happens after the whole chain;
  encoding failures can therefore occur before that check.
- `next.run()` may be called at most once, only while the middleware runs and
  only on the request's thread; a second, late or other-thread call throws
  `IllegalStateException`.
- Returning `null` is a programming error (`IllegalStateException`), treated like
  any other exception.
- The context is the handler's context: the same thread confinement rules apply,
  and `ctx.status(...)` set by a middleware applies to the handler's mapping.

Middleware run inside the admitted request task, on the same virtual thread as
the handler, after admission and before response serialization. The request
deadline covers them; a timeout interrupts them like a handler; an exception
they throw is handled exactly like a handler exception. One instance serves all
requests concurrently, so a middleware must be thread-safe and keep per-request
state in local variables.

## Decision: synchronous middleware, parallel work inside handlers

Middleware continuations remain synchronous: call `next.run()` at most once,
while `handle` is on the stack, on the request thread. This keeps exception
propagation, context ownership, admission and cancellation within one request
task. Invoking a saved continuation after middleware returns, or invoking it
on an executor thread, fails with `IllegalStateException`.

Blocking downstream calls already fit the request's virtual thread. When two
independent calls must overlap, give them a local scope inside the handler.
The compiled and tested [FanOutApi example](../examples/readme/src/main/java/FanOutApi.java)
uses Java 21's stable virtual-thread executor and a completion queue:

1. Read the route ID and `ctx.execution()` on the request thread. Share immutable
   inputs and the thread-safe `ExecutionContext`, never `Context` or `Next`.
2. Start exactly two child tasks. Each receives the remaining deadline as its
   downstream timeout; configure the actual client to honor that budget.
3. Await completions with the remaining request budget recalculated each time.
   Observe either child's failure immediately, without waiting for submission order.
4. Cancel unfinished children on success, failure, timeout or interruption. Close
   the executor and wait for child termination before returning or throwing.
5. Build and encode the response on the request thread after the children stop.

The example propagates original downstream exceptions for normal application
error handling. A downstream timeout can be mapped explicitly with
`app.error(TimeoutException.class, (ctx, failure) -> { throw new GatewayTimeoutException(); });`.
An expired or interrupted request follows the framework cancellation path instead.

Cancellation is cooperative: downstream operations must honor interruption and
use finite I/O timeouts. Executor closure waits for termination; it cannot
forcibly stop an uncooperative client. The request deadline bounds waiting for
results, not arbitrary child cleanup. Do not start detached work or use unbounded
fan-out. This is a scoped task pattern using stable Java 21 APIs, without a
preview API requirement or a new middleware continuation API. For a concrete
HTTP client adapter and verified cancellation boundaries, see
[downstream HTTP calls](downstream-http.md).

## Registration

```java
app.use(securityHeaders);                       // global
app.group("/api/v1", api -> {
    api.use(requestLog);                        // every route of the group
    api.get("/notes/:id", getNote);
    api.post("/notes", createNote, requireJson); // route-level, innermost
    api.group("/admin", admin -> {
        admin.use(requireAdmin);
        admin.delete("/notes/:id", deleteNote); // DELETE /api/v1/admin/notes/:id
    });
});
```

- `Application` is the root `RouteGroup`. A group has the same `route`, shortcut
  and `use` methods, plus `group(prefix, configure)` for nesting.
- Route-level middleware are trailing varargs of `route` and the shortcuts
  (`app.get(path, handler, middleware...)`). That is one consistent style for every
  level, needs no mutable route object (`Route` stays an immutable identity), and
  makes the route's own middleware visible where the route is declared.
- The composed template is `prefix + path`, validated by the ordinary route
  rules (strict path syntax, capture names, conflicts). A prefix is empty or
  starts with `/`, does not end with `/` and contains no wildcard; inside a
  group a path is empty (the prefix itself) or starts with `/`.
- `use` applies to every route of its scope, wherever it is called in the
  configuration callback; middleware of one scope run in `use` order.
- `configure` runs immediately on the calling thread. Calls on a group after its
  callback returned fail with `IllegalStateException`.
- A group is atomic: if its callback throws, everything it registered (routes,
  their route admission policies, nested groups, middleware) is removed before the
  exception propagates. A partly configured group could otherwise serve routes
  without middleware, such as authentication, that a later statement would have
  added. A nested group that fails is removed on its own; whether the enclosing
  group survives depends on whether its callback catches the exception.
- `start()` fails with `IllegalStateException` while any group callback is still
  running, on any thread, so a route is never compiled without the middleware of
  its enclosing groups.
- Registration of any kind after `start()` fails with `IllegalStateException`.
- `group(prefix, ...)` with a `null` prefix fails with `NullPointerException`; an
  empty prefix creates a group that only scopes middleware, its routes keeping the
  enclosing prefix.
- Compatibility: the registration methods moved from `Application` to
  `RouteGroup` and gained trailing `Middleware...` parameters. This is source
  compatible but not binary compatible; code compiled against an earlier
  snapshot must be recompiled (the project is unreleased).

Execution order for a matched route: global middleware, then group middleware
from the outermost to the innermost group, then route middleware, then the
handler. `start()` composes this chain once per route and publishes it with the
compiled router.

## Framework answers

Global middleware also wrap the answers the router produces itself: 404, 405,
the automatic OPTIONS 204 and 501 for an unrecognized method. That way headers
added by global middleware (for example security headers) apply to them.
Group and route middleware run only for the route that matched. In global
middleware for such a request `ctx.matchedRoute()` is empty, `ctx.route()`
throws `IllegalStateException` (so global middleware should use `matchedRoute()`)
and `ctx.pathParameters()` is empty. To change the answers themselves rather than
decorate them, use `app.notFound`, `app.methodNotAllowed` and `app.notImplemented`
([customising router answers](routing.md#customising-router-answers)); their handlers
run inside the global middleware.

Requests rejected before routing do not run middleware: a body over the limit
(413), CONNECT (501), and every error the listener produces itself (400, 408,
414, 431, 503, 504 and the others in [errors](errors.md#framework-statuses)).
To count or log them, set a read-only
[`rejectionObserver`](errors.md#observing-listener-rejections) on the listener.

HEAD keeps working: middleware see the HEAD request and the GET route's full
response; the body is removed afterwards. Automatic OPTIONS keeps working and is
wrapped by global middleware only.

## Error handlers

```java
app.error(NoSuchElementException.class, (ctx, failure) -> ctx.status(404).json(new Missing()));
app.error(IllegalStateException.class, (ctx, failure) -> { throw new ConflictException(); });
```

- `error(Class<E>, ErrorHandler<? super E>)` registers a handler for an exception
  class and its subclasses before startup; one handler per class.
- An exception that leaves the chain (handler or middleware) is looked up after
  middleware have unwound, and the response is then decorated by `afterError`. The registered class nearest to the exception's own
  class in its superclass chain wins.
- `AxiomException` keeps its built-in problem response unless a handler is
  registered for `AxiomException` or one of its subclasses: the built-in
  mapping counts as a handler registered for `AxiomException`, so a handler for
  `Exception` does not replace it.
- The error handler gets the request's context with its status reset to 200.
  Its response is encoded with the codecs but is not subject to the Accept check.
- An error handler that throws an `AxiomException` answers with that exception's
  problem response (a deliberate translation, never handled again). Any other
  exception, or a `null` result, is logged with the request ID and answered with
  the generic 500 problem body, in memory and over HTTP.
- A group can register its own handlers with `group.error(type, handler)`
  ([group-scoped handlers](errors.md#group-scoped-handlers)).
- Unmapped exceptions behave as before: `AxiomException`s become problem
  responses, others propagate from `app.handle` and `TestClient` and become the
  generic 500 over HTTP.
- Error handlers do not run once the request was cancelled (thread interrupted)
  or its deadline expired; `InterruptedException` and `CancellationException`
  are never offered.
- Only `Exception` subclasses can be mapped. `Error`s (out of memory, stack
  overflow, assertion failures) are not application outcomes; mapping them
  could hide a broken process, so they keep aborting the request.

The never-leak rule is unchanged: framework responses contain no exception
messages, class names or stack traces; what an error handler returns is the
application's responsibility. Router answers are responses, not exceptions, and
are never offered to error handlers; global middleware can replace them. See
[errors](errors.md#error-handlers).

## Validated bodies

```java
public interface BodyValidator<T> {        // com.jsgalactic.axiom.context, core
    List<Violation> validate(T value);
}

var order = ctx.validatedBody(Order.class, ORDER);
```

`ctx.validatedBody(type, validator)` decodes like `ctx.body(type)` and throws
`ValidationException` (422) when the validator reports violations. In
`axiom-validation`, `Validator<T>` extends `BodyValidator<T>`, so rule sets and
the Jakarta adapter can be passed directly; `Validation.require` is unchanged.

## Rejected alternatives

- `route.use(...)`: `Route` is an immutable value used as an identity for
  admission policies; making it a mutable builder would change that contract.
- Express-style position-dependent `use` (only routes registered after it): order
  of statements would silently change security behavior.
- Building the chain per request from a list: avoidable work on every request.
- Running group middleware for 404/405: no group matched, and guessing one from a
  prefix would make a group's authentication answer for paths it does not own.
- Mapping exceptions inside the chain so middleware see error responses:
  middleware then could not observe exceptions for logging or translation.
  Instead the exception passes through the chain as before and the response is
  decorated afterwards by `afterError`, which costs a second method on middleware
  that decorate responses.
- Mapping `Throwable`: `Error`s signal a broken process, not a request outcome.
- Route-level middleware through a separate builder or an empty-prefix group
  only: either is more ceremony for the common single-route case.

## Limitations

- Middleware are synchronous and run after admission; they cannot influence
  admission, run before the body is received, or see listener errors (use the listener's `rejectionObserver` to count those).
- `Next` and the context are confined to the request thread; a middleware cannot
  continue the chain on another thread.
