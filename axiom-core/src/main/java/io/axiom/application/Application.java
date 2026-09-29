package io.axiom.application;

import io.axiom.context.Handler;
import io.axiom.http.Request;
import io.axiom.http.Response;
import io.axiom.routing.Route;
import java.util.List;

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
     * Registers an exact method/path pair. Methods and paths are case-sensitive.
     * Parameter and wildcard templates are not supported yet.
     *
     * @param method HTTP method token
     * @param path absolute path without query or fragment
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
     * Freezes registration and enables in-memory execution without opening a listener.
     * Repeated calls while running are harmless.
     * @return this application
     * @throws IllegalStateException if closed
     */
    Application start();

    /**
     * Executes a request synchronously on the calling thread with a fresh context.
     * Returns 404 for an unknown path and 405 with Allow for a method mismatch.
     * Handler exceptions propagate unchanged; this method is not a network error boundary.
     * HEAD dispatches only explicitly registered HEAD routes and suppresses response bodies.
     *
     * @param request request to execute
     * @return mapped handler result
     * @throws Exception if the handler fails
     * @throws IllegalStateException unless running
     */
    Response handle(Request request) throws Exception;

    /**
     * Returns the current lifecycle state.
     *
     * @return the current lifecycle state
     */
    State state();

    /**
     * Permanently rejects new requests. Already accepted requests may finish.
     * Idempotent; does not wait for, interrupt, or cancel handlers.
     */
    @Override
    void close();
}
