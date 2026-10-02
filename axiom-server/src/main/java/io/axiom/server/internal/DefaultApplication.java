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
import java.util.ServiceLoader;
import java.util.concurrent.TimeoutException;

final class DefaultApplication implements Application {
    private final Map<Route, Handler> registrations = new LinkedHashMap<>();
    private CompiledRouter router;
    private AdmissionPolicy admissionPolicy = AdmissionPolicy.reject(36);
    private final Map<Route, AdmissionPolicy> routePolicies = new LinkedHashMap<>();
    private List<Route> frozenRoutes = List.of();
    private java.util.Set<Route> frozenRouteSet = java.util.Set.of();
    private volatile Duration requestTimeout = Duration.ofSeconds(10);
    private volatile State state = State.CONFIGURING;
    private final List<Server> listeners = new ArrayList<>();

    @Override
    public synchronized Server listen(InetSocketAddress address) throws IOException {
        Objects.requireNonNull(address, "address");
        if (state == State.CLOSED) { throw new IllegalStateException("Application is closed"); }
        var providers = ServiceLoader.load(HttpTransportProvider.class).iterator();
        if (!providers.hasNext()) {
            throw new IllegalStateException("No HTTP transport provider; add axiom-http");
        }
        var provider = providers.next();
        if (providers.hasNext()) { throw new IllegalStateException("Multiple HTTP transport providers"); }
        start();
        var server = provider.bind(this, address);
        listeners.removeIf(listener -> listener.termination().toCompletableFuture().isDone());
        listeners.add(server);
        return server;
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
    public synchronized List<Route> routes() {
        return state == State.CONFIGURING ? List.copyOf(registrations.keySet()) : frozenRoutes;
    }

    @Override
    public synchronized Application start() {
        if (state == State.RUNNING) {
            return this;
        }
        requireState(State.CONFIGURING);
        router = CompiledRouter.compile(registrations);
        frozenRoutes = List.copyOf(registrations.keySet());
        frozenRouteSet = java.util.Set.copyOf(frozenRoutes);
        registrations.clear();
        state = State.RUNNING;
        return this;
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

    @Override public synchronized AdmissionPolicy admissionPolicy() { return admissionPolicy; }

    @Override public synchronized Application admissionPolicy(Route route, AdmissionPolicy policy) {
        requireState(State.CONFIGURING);
        requireRegistered(route);
        routePolicies.put(route, Objects.requireNonNull(policy, "policy"));
        return this;
    }

    @Override public synchronized AdmissionPolicy admissionPolicy(Route route) {
        requireRegistered(route);
        return routePolicies.getOrDefault(route, admissionPolicy);
    }

    @Override public Optional<Route> resolve(Request request) {
        Objects.requireNonNull(request, "request");
        CompiledRouter acceptedRouter;
        synchronized (this) {
            requireState(State.RUNNING);
            acceptedRouter = router;
        }
        var match = acceptedRouter.match(request);
        return match == null || !match.methodAllowed() ? Optional.empty() : Optional.of(match.route());
    }

    private void requireRegistered(Route route) {
        Objects.requireNonNull(route, "route");
        if (!(state == State.CONFIGURING ? registrations.containsKey(route) : frozenRouteSet.contains(route))) {
            throw new IllegalArgumentException("Route is not registered: " + route);
        }
    }

    @Override
    public Response handle(Request request, ExecutionContext execution) throws Exception {
        Objects.requireNonNull(execution, "execution");
        Objects.requireNonNull(request, "request");
        CompiledRouter acceptedRouter;
        synchronized (this) {
            requireState(State.RUNNING);
            // Admission ends here. Never hold the lifecycle lock while invoking user code.
            acceptedRouter = router;
        }
        if (execution.isExpired()) { throw new TimeoutException("Request deadline exceeded"); }
        var match = acceptedRouter.match(request);
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
            if (state == State.CONFIGURING) {
                frozenRoutes = List.copyOf(registrations.keySet());
                frozenRouteSet = java.util.Set.copyOf(frozenRoutes);
            }
            state = State.CLOSED;
            registrations.clear();
            router = null;
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
