package io.axiom.routing;

import io.axiom.context.Handler;
import io.axiom.context.Middleware;
import java.util.function.Consumer;

/**
 * Registers routes and middleware for a scope: the whole application, or a group of routes
 * under a path prefix created with {@link #group(String, Consumer)}.
 *
 * <p>For a matched route the chain runs global middleware, then the middleware of each enclosing
 * group from the outermost to the innermost, then the route's own middleware, then the handler.
 * {@link #use(Middleware)} applies to every route of its scope wherever it is called; middleware
 * of one scope run in {@code use} order. Startup composes each route's chain once.
 *
 * <p><b>Lifecycle.</b> Registration is allowed only before the application starts and fails with
 * {@link IllegalStateException} afterwards. A group passed to a {@code group} callback is valid
 * only until that callback returns. Registration is thread-safe; middleware and handlers are
 * shared across concurrent requests and must be thread-safe.
 */
public interface RouteGroup {
    /**
     * Registers a route in this scope. In a group the template is the group's prefix followed by
     * {@code path}, which is empty (the prefix itself) or starts with {@code /}; the composed
     * template is validated by the ordinary routing rules.
     *
     * @param method case-sensitive HTTP method token
     * @param path path template, relative to the group's prefix
     * @param handler callback invoked for matching requests
     * @param middleware route-level middleware, outermost first, run after group middleware
     * @return the registered route identity, with the composed template
     * @throws IllegalArgumentException for an invalid method or template, or a conflicting route
     * @throws IllegalStateException if configuration has ended
     */
    Route route(String method, String path, Handler handler, Middleware... middleware);

    /**
     * Registers a GET route; see {@link #route}.
     *
     * @param path path template
     * @param handler callback
     * @param middleware route-level middleware
     * @return registered route
     */
    default Route get(String path, Handler handler, Middleware... middleware) {
        return route("GET", path, handler, middleware);
    }

    /**
     * Registers a POST route; see {@link #route}.
     *
     * @param path path template
     * @param handler callback
     * @param middleware route-level middleware
     * @return registered route
     */
    default Route post(String path, Handler handler, Middleware... middleware) {
        return route("POST", path, handler, middleware);
    }

    /**
     * Registers a PUT route; see {@link #route}.
     *
     * @param path path template
     * @param handler callback
     * @param middleware route-level middleware
     * @return registered route
     */
    default Route put(String path, Handler handler, Middleware... middleware) {
        return route("PUT", path, handler, middleware);
    }

    /**
     * Registers a PATCH route; see {@link #route}.
     *
     * @param path path template
     * @param handler callback
     * @param middleware route-level middleware
     * @return registered route
     */
    default Route patch(String path, Handler handler, Middleware... middleware) {
        return route("PATCH", path, handler, middleware);
    }

    /**
     * Registers a DELETE route; see {@link #route}.
     *
     * @param path path template
     * @param handler callback
     * @param middleware route-level middleware
     * @return registered route
     */
    default Route delete(String path, Handler handler, Middleware... middleware) {
        return route("DELETE", path, handler, middleware);
    }

    /**
     * Registers a HEAD route; see {@link #route}. Without one, HEAD uses the GET route of the
     * same template, including its middleware.
     *
     * @param path path template
     * @param handler callback
     * @param middleware route-level middleware
     * @return registered route
     */
    default Route head(String path, Handler handler, Middleware... middleware) {
        return route("HEAD", path, handler, middleware);
    }

    /**
     * Registers an OPTIONS route; see {@link #route}. Without one, OPTIONS for a routed path is
     * answered automatically with 204 and an Allow header; an explicit route replaces that answer
     * for the paths it matches.
     *
     * @param path path template
     * @param handler callback
     * @param middleware route-level middleware
     * @return registered route
     */
    default Route options(String path, Handler handler, Middleware... middleware) {
        return route("OPTIONS", path, handler, middleware);
    }

    /**
     * Adds middleware for every route of this scope, including routes registered before this call
     * and routes of nested groups. On the application it is global: it also wraps the answers the
     * router produces itself (404, 405, automatic OPTIONS, 501 for an unrecognized method), for
     * which {@code ctx.matchedRoute()} is empty and {@code ctx.route()} throws
     * {@link IllegalStateException}.
     *
     * @param middleware shared, thread-safe middleware
     * @return this scope
     * @throws IllegalStateException if configuration has ended
     */
    RouteGroup use(Middleware middleware);

    /**
     * Creates a nested group and configures it immediately on the calling thread. Its prefix is
     * appended to this scope's prefix. Registration is atomic: if {@code configure} throws,
     * everything it registered (routes and their admission policies, nested groups, middleware)
     * is removed before the exception propagates, so a partly configured group never serves
     * routes without middleware a later statement would have added. While the callback runs,
     * {@code start()} fails with {@link IllegalStateException}.
     *
     * @param prefix empty, or a template prefix starting with {@code /} that does not end with
     *        {@code /} and has no wildcard; it may declare parameters such as {@code /users/:id}
     * @param configure registers the group's routes, middleware and nested groups
     * @return this scope
     * @throws IllegalArgumentException for an invalid prefix
     * @throws IllegalStateException if configuration has ended
     */
    RouteGroup group(String prefix, Consumer<RouteGroup> configure);
}
