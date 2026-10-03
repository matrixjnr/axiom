package com.jsgalactic.axiom.application;

import com.jsgalactic.axiom.context.ErrorHandler;
import com.jsgalactic.axiom.context.Handler;
import com.jsgalactic.axiom.context.Middleware;
import com.jsgalactic.axiom.execution.AdmissionPolicy;
import com.jsgalactic.axiom.execution.ExecutionContext;
import com.jsgalactic.axiom.http.Request;
import com.jsgalactic.axiom.http.Response;
import com.jsgalactic.axiom.lifecycle.ListenerOptions;
import com.jsgalactic.axiom.lifecycle.Server;
import com.jsgalactic.axiom.observability.Metrics;
import com.jsgalactic.axiom.routing.Route;
import com.jsgalactic.axiom.routing.RouteGroup;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * Registers routes before startup and owns their execution lifecycle.
 * Registration, startup, and shutdown are thread-safe. Handlers may execute concurrently
 * and are responsible for synchronizing shared application state.
 *
 * <p>Methods are case-sensitive RFC 9110 tokens and are handled as follows. "Allow" is the union of
 * the methods registered on every template matching the path, plus HEAD wherever GET is. The same
 * answers come from {@link #handle(Request)}, the test client and HTTP listeners.
 * <table class="striped">
 * <caption>Method handling</caption>
 * <thead><tr><th>Method</th><th>Registration</th><th>Path routed, method not registered there</th>
 * <th>Path not routed</th></tr></thead>
 * <tbody>
 * <tr><td>GET, POST, PUT, PATCH, DELETE</td><td>shortcut or {@link #route}</td><td>405 with Allow</td>
 * <td>404</td></tr>
 * <tr><td>HEAD</td><td>{@link #head} or the GET route of the same template</td>
 * <td>405 with Allow</td><td>404</td></tr>
 * <tr><td>OPTIONS</td><td>{@link #options}</td><td>204 with Allow plus OPTIONS, no handler</td>
 * <td>404</td></tr>
 * <tr><td>OPTIONS *</td><td>not possible</td>
 * <td colspan="2">204 with every registered method (HEAD if GET is registered) plus OPTIONS</td></tr>
 * <tr><td>TRACE</td><td>refused</td><td>405 with Allow</td><td>404</td></tr>
 * <tr><td>CONNECT</td><td>refused</td><td colspan="2">501</td></tr>
 * <tr><td>extension token, e.g. PROPFIND or QUERY</td><td>{@link #route}</td><td>405 with Allow</td>
 * <td>404 if registered on any route or declared with {@link #recognizeMethods}, otherwise 501</td></tr>
 * </tbody>
 * </table>
 * Request bodies are accepted and limited alike for every method, HEAD responses never carry a
 * body, and method-override headers such as {@code X-HTTP-Method-Override} are ignored.
 */
public interface Application extends RouteGroup, AutoCloseable {
    /** Application lifecycle; a closed application cannot be restarted. */
    enum State {
        /** Routes may be registered. */
        CONFIGURING,
        /** Routes are frozen and requests may execute. */
        RUNNING,
        /** New requests and configuration changes are rejected. */
        CLOSED
    }

    /**
     * Registers a method/path template with optional route-level middleware. Methods and
     * static segments are case-sensitive.
     * The method must be an RFC 9110 token (one or more of {@code A-Z a-z 0-9} and
     * {@code !#$%&'*+-.^_`|~}); {@code get} and {@code GET} are different methods. Extension
     * methods such as {@code PROPFIND} or {@code QUERY} are registered here; there is no
     * {@code query} shortcut while that method is still a draft. Only the request line's method
     * is matched; method-override headers such as {@code X-HTTP-Method-Override} are ignored.
     * Named parameters match one non-empty segment; named terminal wildcards match the remainder.
     * Templates that differ only in capture names have the same shape and match the same paths;
     * registering a second one for the same method fails here.
     *
     * @param method case-sensitive HTTP method token
     * @param path absolute path template without query or fragment
     * @param handler callback invoked for matching requests
     * @param middleware route-level middleware, outermost first; see {@link RouteGroup}
     * @return the registered route identity
     * @throws IllegalArgumentException for a method that is not a token, for {@code TRACE} (whose
     *         echo of the request would expose credentials; TRACE requests are answered 405 or 404),
     *         for {@code CONNECT} (tunnels are not supported; CONNECT requests are answered 501),
     *         for an invalid template, or for a duplicate or same-shape route for one method
     * @throws IllegalStateException if configuration has ended
     */
    @Override
    Route route(String method, String path, Handler handler, Middleware... middleware);

    /**
     * Customises the 404 answer for a path no template matches (and a recognized method; see
     * {@link #recognizeMethods}) before startup, without middleware. The handler runs as the
     * innermost step of the global middleware chain, with the status already set to 404, so
     * {@code ctx.json(body)} and {@code ctx.text(text)} answer 404 and a returned {@code null}
     * is a 404 without a body. It may return any {@link Response}, or throw: exceptions are mapped
     * as for any handler, by {@link #error} handlers or into a problem response. No route matched:
     * {@code ctx.route()} throws and {@code ctx.matchedRoute()} is empty. Without a handler the
     * answer is the {@code application/problem+json} 404. A second call replaces the first.
     *
     * @param handler shared, thread-safe handler
     * @return this application
     * @throws IllegalStateException if configuration has ended
     */
    Application notFound(Handler handler);

    /**
     * Customises the 405 answer for a path some template matches but none serves the method of,
     * before startup. It behaves as {@link #notFound} with the status preset to 405. The
     * {@code Allow} header is always the router's list (see the method table above): it is set on the
     * handler's response whatever the handler sets, so a custom answer cannot omit or contradict it.
     * OPTIONS requests are never 405: they are answered automatically (see
     * {@link com.jsgalactic.axiom.context.Context#automaticOptions()}), so this handler never sees one.
     *
     * @param handler shared, thread-safe handler
     * @return this application
     * @throws IllegalStateException if configuration has ended
     */
    Application methodNotAllowed(Handler handler);

    /**
     * Customises the 501 answer for an unrecognized method on a path no template matches, before
     * startup. It behaves as {@link #notFound} with the status preset to 501. {@code CONNECT} is
     * answered 501 before routing and middleware and is not customised.
     *
     * @param handler shared, thread-safe handler
     * @return this application
     * @throws IllegalStateException if configuration has ended
     */
    Application notImplemented(Handler handler);

    /**
     * Declares methods this application recognizes although no route has them, before startup.
     * A request whose path matches no template is answered 404 for a recognized method and 501
     * for any other (RFC 9110 section 15.6.2). Recognized are the RFC 9110 methods, {@code PATCH},
     * every method registered on a route and the methods declared here, for example an extension
     * a gateway or global middleware answers itself. Declaring a method changes nothing else:
     * it is not added to any {@code Allow} header, and a request for it whose path matches
     * a template is still 405. Repeated calls add to the set; a method already recognized is
     * accepted.
     *
     * @param methods case-sensitive RFC 9110 method tokens
     * @return this application
     * @throws IllegalArgumentException for a method that is not a token or for {@code CONNECT},
     *         which is always answered 501
     * @throws IllegalStateException if configuration has ended
     */
    Application recognizeMethods(String... methods);

    /**
     * Adds global middleware. It runs first for every route, and also wraps the answers the
     * router produces itself (404, 405, automatic OPTIONS, 501 for an unrecognized method); for
     * those, {@code ctx.matchedRoute()} is empty and {@code ctx.route()} throws
     * {@link IllegalStateException}. Requests rejected before
     * routing (413, CONNECT, listener errors) do not run middleware.
     *
     * @param middleware shared, thread-safe middleware
     * @return this application
     * @throws IllegalStateException if configuration has ended
     */
    @Override
    Application use(Middleware middleware);

    /**
     * Creates a top-level route group; see {@link RouteGroup#group(String, Consumer)}.
     *
     * @param prefix group path prefix, or empty for a group that only scopes middleware
     * @param configure registers the group's routes, middleware and nested groups
     * @return this application
     */
    @Override
    Application group(String prefix, Consumer<RouteGroup> configure);

    /**
     * Maps exceptions of a class and its subclasses to responses before startup. When a handler
     * or middleware throws, the registered class nearest to the exception's class in its
     * superclass chain wins, after every middleware has unwound. {@link com.jsgalactic.axiom.error.AxiomException}
     * keeps its built-in problem response unless a handler is registered for
     * {@code AxiomException} or one of its subclasses: the built-in mapping counts as registered
     * for {@code AxiomException}, so a handler for {@code Exception} does not replace it.
     * Exceptions without a handler behave as described for {@link #handle(Request)}. Requests
     * rejected before routing (413, CONNECT) and listener errors are not offered to handlers.
     * Only exceptions can be mapped; {@link Error}s are never handled. Handlers do not run for a
     * request that was cancelled (its thread interrupted) or whose deadline expired, and
     * {@link InterruptedException} and {@link java.util.concurrent.CancellationException} are
     * never offered to them.
     *
     * @param type exception class
     * @param handler shared, thread-safe mapping
     * @param <E> exception type
     * @return this application
     * @throws IllegalArgumentException if a handler is already registered for the class
     * @throws IllegalStateException if configuration has ended
     */
    <E extends Exception> Application error(Class<E> type, ErrorHandler<? super E> handler);

    /**
     * Returns an immutable snapshot in registration order.
     *
     * @return an immutable snapshot in registration order
     */
    List<Route> routes();

    /**
     * Compiles routes, freezes registration, and enables in-memory execution without a listener.
     * Repeated calls while running are harmless. A compilation failure leaves registration
     * intact and the application in the configuring state.
     * Startup also discovers body codecs once with {@link java.util.ServiceLoader}.
     * @return this application
     * @throws IllegalArgumentException if the route table cannot be compiled
     * @throws IllegalStateException if closed, or if two installed codecs declare the same media type
     */
    Application start();

    /**
     * Sets the request execution budget before startup. The default is ten seconds.
     * @param timeout positive duration, at most one day
     * @return this application
     * @throws IllegalStateException after configuration has ended
     */
    Application requestTimeout(Duration timeout);

    /**
     * Returns the configured execution budget.
     * @return request timeout
     */
    Duration requestTimeout();

    /**
     * Sets the largest accepted request body before startup. The default is 1 MiB (1,048,576
     * bytes). Bodies are buffered in memory before the handler runs. Larger requests receive 413
     * without invoking a handler: HTTP listeners reject a declared Content-Length before reading
     * the body and stop reading a chunked body as soon as it exceeds the limit; in-memory calls
     * and the test client check the body length. Zero rejects every non-empty body.
     * The ceiling is deliberate: bodies are held in memory, and 64 MiB equals the default listener
     * budget for request bodies in flight, so any permitted body fits it (see the body guide).
     * @param bytes limit from zero to 64 MiB
     * @return this application
     * @throws IllegalArgumentException for a negative limit or one above 64 MiB
     * @throws IllegalStateException after configuration has ended
     */
    Application maxRequestBody(int bytes);

    /**
     * Returns the configured request body limit.
     * @return limit in bytes
     */
    int maxRequestBody();

    /**
     * Sets aggregate limits for each listener before startup. Defaults to reject(36).
     * @param policy immutable listener limits; also the default policy for each route
     * @return this application
     */
    Application admissionPolicy(AdmissionPolicy policy);

    /**
     * Returns the aggregate listener limits.
     * @return configured default policy
     */
    AdmissionPolicy admissionPolicy();

    /**
     * Sets limits for one registered route before startup. Aggregate limits still apply.
     * @param route registered route identity
     * @param policy complete route policy
     * @return this application
     * @throws IllegalArgumentException if the route is not registered
     */
    Application admissionPolicy(Route route, AdmissionPolicy policy);

    /**
     * Returns a registered route's override, or the default policy.
     * @param route registered route identity
     * @return effective route policy
     * @throws IllegalArgumentException if the route is not registered
     */
    AdmissionPolicy admissionPolicy(Route route);

    /**
     * Sets where listeners and the test client record request, latency and admission metrics, before
     * startup. The default is {@link Metrics#NOOP}. Metrics cover requests that pass through
     * admission; direct {@link #handle(Request)} calls bypass admission and are not recorded.
     * @param metrics thread-safe metrics implementation
     * @return this application
     * @throws IllegalStateException after configuration has ended
     */
    Application metrics(Metrics metrics);

    /**
     * Returns the configured metrics.
     * @return metrics receiving the runtime's measurements
     */
    Metrics metrics();

    /**
     * Resolves a route identity without executing user code. Requires a running application.
     * @param request request to match using the same precedence as handle
     * @return matching method/template, or empty for 404/405
     */
    Optional<Route> resolve(Request request);

    /**
     * Starts this application and binds a loopback HTTP listener.
     * @param port port, or zero to allocate an available port
     * @return application-owned listener
     * @throws IOException if binding fails; the application remains running
     */
    default Server listen(int port) throws IOException {
        return listen(new InetSocketAddress("127.0.0.1", port));
    }

    /**
     * Starts this application and binds an HTTP listener using the installed transport provider.
     * Multiple listeners may share an application. Closing the application closes all listeners.
     * @param address bind address
     * @return application-owned listener
     * @throws IOException if binding fails; the application remains running
     * @throws IllegalStateException if closed or no unique transport provider is installed
     */
    default Server listen(InetSocketAddress address) throws IOException {
        return listen(address, ListenerOptions.defaults());
    }

    /**
     * Starts this application and binds an HTTP listener with explicit limits and timeouts, such as
     * the shutdown grace period or the connection cap. The options apply to this listener only.
     * @param address bind address
     * @param options immutable listener options; see {@link ListenerOptions#defaults()}
     * @return application-owned listener
     * @throws IOException if binding fails; the application remains running
     * @throws IllegalStateException if closed or no unique transport provider is installed
     */
    Server listen(InetSocketAddress address, ListenerOptions options) throws IOException;

    /**
     * Executes a request synchronously on the calling thread with a fresh context.
     * Selects the most specific complete path match (static, parameter, wildcard at the first
     * differing segment) that is registered for the request method; a more specific template
     * without that method does not hide a less specific one with it. HEAD uses an explicit
     * HEAD route, or else the GET route of the same template, and suppresses response bodies;
     * a successful HEAD response (2xx other than 204 and 205) instead carries
     * {@code Content-Length} set to the encoded length of the body it would have had, and keeps a
     * body the HTTP transport could not send, so that HEAD fails where GET would.
     * Returns 404 for an unknown path (501 when the method is neither an RFC 9110 method, PATCH,
     * nor registered on any route or declared with {@link #recognizeMethods}), and 405 when no matching template has the method, with
     * an Allow header listing the methods of all matching templates (HEAD wherever GET is, and OPTIONS).
     * An OPTIONS request that no matching template registered is answered 204 without invoking
     * a handler, with that Allow list plus OPTIONS. {@code OPTIONS *} is answered 204 without route
     * lookup, with an Allow list of every registered method (HEAD if GET is registered) plus OPTIONS.
     * CONNECT is answered 501 without routing.
     * Bodies over {@link #maxRequestBody()} receive 413. Unknown paths, method mismatches and
     * {@link com.jsgalactic.axiom.error.AxiomException}s thrown by handlers or {@code Context.body} become
     * {@code application/problem+json} responses with only status, code, request ID and
     * violations. Responses of safe-method requests (GET, HEAD, OPTIONS, TRACE) whose Content-Type
     * has an installed codec are checked against the request's Accept header (406 when nothing
     * matches; other methods ignore Accept, so a 406 cannot follow a side effect) and
     * non-String, non-byte[] bodies are
     * encoded by that codec. Exceptions from handlers and middleware are first offered to the
     * handlers registered with {@link #error}; a failing error handler produces the generic 500
     * problem response. Other handler exceptions propagate unchanged; this method is not a
     * network error boundary.
     *
     * @param request request to execute
     * @return mapped handler result
     * @throws Exception if the handler fails
     * @throws IllegalStateException unless running
     */
    Response handle(Request request) throws Exception;

    /**
     * Executes on the calling thread with explicit request identity and deadline metadata.
     * Adapters own scheduling and cancellation. An expired context fails before invocation;
     * synchronous calls do not interrupt the caller or enforce a timeout after invocation.
     * @param request request to execute
     * @param execution execution metadata owned by the caller
     * @return mapped response
     * @throws Exception if the handler fails or the context is already expired
     */
    Response handle(Request request, ExecutionContext execution) throws Exception;

    /**
     * Returns the current lifecycle state.
     *
     * @return the current lifecycle state
     */
    State state();

    /**
     * Opts in to closing this application when the JVM begins to shut down, for example on
     * {@code SIGTERM} from a container runtime or {@code SIGINT}, or when the last non-daemon
     * thread ends or {@code System.exit} is called. Without this call nothing closes the
     * application on its own; Axiom never registers a hook unless asked.
     *
     * <p>The hook calls {@link #close()} and then waits for every listener to terminate, so the
     * graceful drain described by {@link Server#close()} completes within each listener's
     * shutdown grace period (see {@link ListenerOptions#shutdownGrace()}): queued requests are
     * answered 503, running handlers finish, and the remaining ones are interrupted afterwards.
     * The wait is bounded by the longest grace period of any listener plus five seconds, so a
     * handler that ignores interruption cannot keep the JVM from exiting. Because the application
     * is no longer {@link State#RUNNING} from the moment it closes, a
     * {@link com.jsgalactic.axiom.observability.Health} readiness probe reports DOWN; call
     * {@code Health.beginDrain()} from your own hook earlier if load balancers need time to notice.
     *
     * <p>Idempotent: the hook is registered once. An explicit {@link #close()} unregisters it. The
     * hook runs alongside other shutdown hooks, in no defined order, and the JVM's logging may
     * already be winding down.
     *
     * @return this application
     * @throws IllegalStateException if the application is closed or the JVM is already shutting down
     */
    Application closeOnJvmShutdown();

    /**
     * Permanently rejects new requests. Already accepted requests may finish.
     * Idempotent and nonblocking. Each owned listener starts the graceful drain described by
     * {@link Server#close()}: queued requests are answered 503, running network handlers get a
     * grace period before they are interrupted. In-memory handlers are not interrupted.
     * Await each listener's termination to join shutdown.
     */
    @Override
    void close();
}
