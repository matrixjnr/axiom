package io.axiom.server.internal;

import io.axiom.application.Application;
import io.axiom.context.Handler;
import io.axiom.execution.AdmissionPolicy;
import io.axiom.execution.ExecutionContext;
import io.axiom.http.Request;
import io.axiom.http.Response;
import io.axiom.http.spi.HttpTransportProvider;
import io.axiom.lifecycle.Server;
import io.axiom.routing.Route;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.ServiceLoader;
import java.util.concurrent.TimeoutException;

final class DefaultApplication implements Application {
    /**
     * Immutable configuration published at startup. The request path reads it through one
     * volatile field and takes no lock; {@code router} is null once the application closes.
     */
    private record Runtime(CompiledRouter router, List<Route> routes, Set<Route> routeSet,
                           Map<Route, AdmissionPolicy> routePolicies, AdmissionPolicy defaultPolicy) {
        Runtime withoutRouter() { return new Runtime(null, routes, routeSet, routePolicies, defaultPolicy); }
    }

    // Configuration state; guarded by this until startup publishes a runtime snapshot.
    private final Map<Route, Handler> registrations = new LinkedHashMap<>();
    private final Map<Route, AdmissionPolicy> routePolicies = new LinkedHashMap<>();
    private final List<Server> listeners = new ArrayList<>();
    private volatile AdmissionPolicy admissionPolicy = AdmissionPolicy.reject(36);
    private volatile Duration requestTimeout = Duration.ofSeconds(10);
    private volatile State state = State.CONFIGURING;
    private volatile Runtime runtime;

    @Override
    public Server listen(InetSocketAddress address) throws IOException {
        Objects.requireNonNull(address, "address");
        if (state == State.CLOSED) { throw new IllegalStateException("Application is closed"); }
        // Provider discovery and binding can block; neither runs under the lifecycle lock.
        var providers = ServiceLoader.load(HttpTransportProvider.class).iterator();
        if (!providers.hasNext()) {
            throw new IllegalStateException("No HTTP transport provider; add axiom-http");
        }
        var provider = providers.next();
        if (providers.hasNext()) { throw new IllegalStateException("Multiple HTTP transport providers"); }
        start();
        var server = provider.bind(this, address);
        synchronized (this) {
            if (state != State.CLOSED) {
                listeners.removeIf(listener -> listener.termination().toCompletableFuture().isDone());
                listeners.add(server);
                return server;
            }
        }
        // The application closed while binding; it must not leave an unowned listener behind.
        server.close();
        throw new IllegalStateException("Application closed while the listener was binding");
    }

    @Override
    public synchronized Route route(String method, String path, Handler handler) {
        requireState(State.CONFIGURING);
        Objects.requireNonNull(handler, "handler");
        var route = new Route(method, path);
        if (registrations.putIfAbsent(route, handler) != null) {
            throw new IllegalArgumentException("Duplicate route: " + method + " " + path);
        }
        return route;
    }

    @Override
    public List<Route> routes() {
        var published = runtime;
        if (published != null) { return published.routes(); }
        synchronized (this) {
            return runtime != null ? runtime.routes() : List.copyOf(registrations.keySet());
        }
    }

    @Override
    public synchronized Application start() {
        if (state == State.RUNNING) {
            return this;
        }
        requireState(State.CONFIGURING);
        var router = CompiledRouter.compile(registrations);
        runtime = snapshot(router);
        registrations.clear();
        state = State.RUNNING;
        return this;
    }

    private Runtime snapshot(CompiledRouter router) {
        var routes = List.copyOf(registrations.keySet());
        return new Runtime(router, routes, Set.copyOf(routes), Map.copyOf(routePolicies), admissionPolicy);
    }

    @Override
    public Response handle(Request request) throws Exception {
        return handle(request, ExecutionContext.create(requestTimeout));
    }

    @Override public synchronized Application requestTimeout(Duration timeout) {
        requireState(State.CONFIGURING);
        ExecutionContext.validateTimeout(timeout);
        requestTimeout = timeout;
        return this;
    }

    @Override public Duration requestTimeout() { return requestTimeout; }

    @Override public synchronized Application admissionPolicy(AdmissionPolicy policy) {
        requireState(State.CONFIGURING);
        admissionPolicy = Objects.requireNonNull(policy, "policy");
        return this;
    }

    @Override public AdmissionPolicy admissionPolicy() { return admissionPolicy; }

    @Override public synchronized Application admissionPolicy(Route route, AdmissionPolicy policy) {
        requireState(State.CONFIGURING);
        Objects.requireNonNull(route, "route");
        if (!registrations.containsKey(route)) { throw notRegistered(route); }
        routePolicies.put(route, Objects.requireNonNull(policy, "policy"));
        return this;
    }

    @Override public AdmissionPolicy admissionPolicy(Route route) {
        Objects.requireNonNull(route, "route");
        var published = runtime;
        if (published == null) {
            synchronized (this) {
                published = runtime;
                if (published == null) {
                    if (!registrations.containsKey(route)) { throw notRegistered(route); }
                    return routePolicies.getOrDefault(route, admissionPolicy);
                }
            }
        }
        if (!published.routeSet().contains(route)) { throw notRegistered(route); }
        return published.routePolicies().getOrDefault(route, published.defaultPolicy());
    }

    @Override public Optional<Route> resolve(Request request) {
        Objects.requireNonNull(request, "request");
        var match = acceptingRouter().match(request);
        return match == null || !match.methodAllowed() ? Optional.empty() : Optional.of(match.route());
    }

    private static IllegalArgumentException notRegistered(Route route) {
        return new IllegalArgumentException("Route is not registered: " + route);
    }

    /** Admission check for the request path: reads the published snapshot without locking. */
    private CompiledRouter acceptingRouter() {
        var published = runtime;
        var router = published == null ? null : published.router();
        if (router == null) {
            throw new IllegalStateException("Expected application state RUNNING but was " + state);
        }
        return router;
    }

    @Override
    public Response handle(Request request, ExecutionContext execution) throws Exception {
        Objects.requireNonNull(execution, "execution");
        Objects.requireNonNull(request, "request");
        // Admission ends here. User code never runs under the lifecycle lock.
        var router = acceptingRouter();
        if (execution.isExpired()) { throw new TimeoutException("Request deadline exceeded"); }
        var match = router.match(request);
        Response response;
        if (match == null) {
            response = Response.of(404, "Not Found");
        } else {
            if (!match.methodAllowed()) {
                response = Response.of(405, "Method Not Allowed")
                        .withHeader("Allow", match.allow());
            } else {
                var context = new DefaultContext(request, match, execution);
                var result = match.handler().handle(context);
                response = result instanceof Response explicit ? explicit : context.response(result);
            }
        }
        return request.method().equals("HEAD") ? response.withoutBody() : response;
    }

    @Override
    public State state() { return state; }

    @Override
    public void close() {
        List<Server> owned;
        synchronized (this) {
            runtime = runtime == null ? snapshot(null) : runtime.withoutRouter();
            state = State.CLOSED;
            registrations.clear();
            routePolicies.clear();
            owned = List.copyOf(listeners);
            listeners.clear();
        }
        owned.forEach(Server::close);
    }

    private void requireState(State expected) {
        if (state != expected) {
            throw new IllegalStateException("Expected application state " + expected + " but was " + state);
        }
    }
}
