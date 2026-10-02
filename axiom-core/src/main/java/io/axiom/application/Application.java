package io.axiom.application;

import io.axiom.context.Handler;
import io.axiom.execution.AdmissionPolicy;
import io.axiom.execution.ExecutionContext;
import io.axiom.http.Request;
import io.axiom.http.Response;
import io.axiom.lifecycle.Server;
import io.axiom.routing.Route;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.List;
import java.util.Optional;

/**
 * Registers routes before startup and owns their execution lifecycle.
 * Registration, startup, and shutdown are thread-safe. Handlers may execute concurrently
 * and are responsible for synchronizing shared application state.
 */
public interface Application extends AutoCloseable {
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
     * Registers a method/path template. Methods and static segments are case-sensitive.
     * Named parameters match one non-empty segment; named terminal wildcards match the remainder.
     *
     * @param method HTTP method token
     * @param path absolute path template without query or fragment
     * @param handler callback invoked for matching requests
     * @return the registered route identity
     * @throws IllegalArgumentException for invalid or duplicate routes
     * @throws IllegalStateException if configuration has ended
     */
    Route route(String method, String path, Handler handler);

    /**
     * Registers a route for this HTTP method.
     *
     * @param path route path
     * @param handler callback
     * @return registered route
     */
    default Route get(String path, Handler handler) {
        return route("GET", path, handler);
    }

    /**
     * Registers a route for this HTTP method.
     *
     * @param path route path
     * @param handler callback
     * @return registered route
     */
    default Route post(String path, Handler handler) {
        return route("POST", path, handler);
    }

    /**
     * Registers a route for this HTTP method.
     *
     * @param path route path
     * @param handler callback
     * @return registered route
     */
    default Route put(String path, Handler handler) {
        return route("PUT", path, handler);
    }

    /**
     * Registers a route for this HTTP method.
     *
     * @param path route path
     * @param handler callback
     * @return registered route
     */
    default Route patch(String path, Handler handler) {
        return route("PATCH", path, handler);
    }

    /**
     * Registers a route for this HTTP method.
     *
     * @param path route path
     * @param handler callback
     * @return registered route
     */
    default Route delete(String path, Handler handler) {
        return route("DELETE", path, handler);
    }

    /**
     * Registers a route for this HTTP method.
     *
     * @param path route path
     * @param handler callback
     * @return registered route
     */
    default Route head(String path, Handler handler) {
        return route("HEAD", path, handler);
    }

    /**
     * Registers a route for this HTTP method.
     *
     * @param path route path
     * @param handler callback
     * @return registered route
     */
    default Route options(String path, Handler handler) {
        return route("OPTIONS", path, handler);
    }

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
     * @return this application
     * @throws IllegalArgumentException if routes have the same shape and method
     * @throws IllegalStateException if closed
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
    Server listen(InetSocketAddress address) throws IOException;

    /**
     * Executes a request synchronously on the calling thread with a fresh context.
     * Selects the most specific complete path match (static, parameter, wildcard at the first
     * differing segment) that is registered for the request method; a more specific template
     * without that method does not hide a less specific one with it. HEAD uses an explicit
     * HEAD route, or else the GET route of the same template, and suppresses response bodies.
     * Returns 404 for an unknown path, and 405 when no matching template has the method, with
     * an Allow header listing the methods of all matching templates (HEAD wherever GET is).
     * Handler exceptions propagate unchanged; this method is not a network error boundary.
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
     * Permanently rejects new requests. Already accepted requests may finish.
     * Idempotent and nonblocking. Owned listeners close connections and interrupt network handlers.
     * In-memory handlers are not interrupted. Await each listener's termination to join shutdown.
     */
    @Override
    void close();
}
