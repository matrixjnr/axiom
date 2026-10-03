# Middleware, route groups and error handlers

Status: design. Sections are marked as implemented when the code lands.

## Goals

- Code that runs around handlers (logging, timing, authentication, security
  headers) without repeating it in every handler.
- Shared path prefixes and middleware for related routes.
- Application-defined mappings from exceptions to responses that never leak
  internals.
- Everything decided at `start()`: no chain is built per request.

Not goals of this step: group-scoped error handlers, a development mode that
exposes exception details, asynchronous middleware, and middleware that runs
before admission or for errors the listener produces itself.

## Middleware

```java
@FunctionalInterface
public interface Middleware {
    Response handle(Context ctx, Next next) throws Exception;

    interface Next {
        Response run() throws Exception;
    }
}
```

`next.run()` runs the rest of the chain (more middleware, then the handler) and
returns its response. A middleware that returns without calling it short-circuits:
the handler never runs and the returned response is used. A middleware can change
the response it gets back (`next.run().withHeader(...)`), and it sees exceptions
from the rest of the chain as exceptions from `next.run()`, which it can catch,
translate or let propagate.

- The handler's result reaches middleware as a `Response`, mapped exactly as
  today (`String` to text, `null` to 204, the context status). Codec encoding and
  the Accept check (406) happen once, after the whole chain, so a middleware sees
  `ctx.json(value)` bodies unencoded and can short-circuit with `ctx.json(...)` too.
- `next.run()` may be called at most once, and only while the middleware runs;
  a second or late call throws `IllegalStateException`.
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
  callback returned fail with `IllegalStateException`. If the callback throws,
  routes it already registered stay registered, as separate `route` calls would.
- Registration of any kind after `start()` fails with `IllegalStateException`.

Execution order for a matched route: global middleware, then group middleware
from the outermost to the innermost group, then route middleware, then the
handler. `start()` composes this chain once per route and publishes it with the
compiled router.

## Framework answers

Global middleware also wrap the answers the router produces itself: 404, 405,
the automatic OPTIONS 204 and 501 for an unrecognized method. That way headers
added by global middleware (for example security headers) apply to them.
Group and route middleware run only for the route that matched. In global
middleware for such a request `ctx.route()` throws `IllegalStateException`
and `ctx.pathParameters()` is empty.

Requests rejected before routing do not run middleware: a body over the limit
(413), CONNECT (501), and every error the listener produces itself (400, 408,
414, 431, 503, 504 and the others in [errors](errors.md#framework-statuses)).

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
  middleware have unwound. The registered class nearest to the exception's own
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
- Unmapped exceptions behave as before: `AxiomException`s become problem
  responses, others propagate from `app.handle` and `TestClient` and become the
  generic 500 over HTTP.
- Only `Exception` subclasses can be mapped. `Error`s (out of memory, stack
  overflow, assertion failures) are not application outcomes; mapping them
  could hide a broken process, so they keep aborting the request.

The never-leak rule is unchanged: framework responses contain no exception
messages, class names or stack traces; what an error handler returns is the
application's responsibility.

## Validated bodies

```java
public interface BodyValidator<T> {        // io.axiom.context, core
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
- Mapping exceptions inside the chain so middleware see error responses: the
  requested semantics are that middleware observe exceptions; middleware that
  must decorate error responses catch them or use an error handler.
