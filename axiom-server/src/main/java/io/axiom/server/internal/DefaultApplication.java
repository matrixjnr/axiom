package io.axiom.server.internal;

import io.axiom.application.Application;
import io.axiom.context.Handler;
import io.axiom.http.Request;
import io.axiom.http.Response;
import io.axiom.routing.Route;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

final class DefaultApplication implements Application {
    private final Map<Route, Handler> registrations = new LinkedHashMap<>();
    private CompiledRouter router;
    private List<Route> frozenRoutes = List.of();
    private volatile State state = State.CONFIGURING;

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
        registrations.clear();
        state = State.RUNNING;
        return this;
    }

    @Override
    public Response handle(Request request) throws Exception {
        Objects.requireNonNull(request, "request");
        CompiledRouter acceptedRouter;
        synchronized (this) {
            requireState(State.RUNNING);
            // Admission ends here. Never hold the lifecycle lock while invoking user code.
            acceptedRouter = router;
        }
        var match = acceptedRouter.match(request);
        Response response;
        if (match == null) {
            response = Response.of(404, "Not Found");
        } else {
            if (!match.methodAllowed()) {
                response = Response.of(405, "Method Not Allowed")
                        .withHeader("Allow", match.allow());
            } else {
                var context = new DefaultContext(request, match);
                var result = match.handler().handle(context);
                response = result instanceof Response explicit ? explicit : context.response(result);
            }
        }
        return request.method().equals("HEAD") ? response.withoutBody() : response;
    }

    @Override
    public State state() { return state; }

    @Override
    public synchronized void close() {
        if (state == State.CONFIGURING) {
            frozenRoutes = List.copyOf(registrations.keySet());
        }
        state = State.CLOSED;
        registrations.clear();
        router = null;
    }

    private void requireState(State expected) {
        if (state != expected) {
            throw new IllegalStateException("Expected application state " + expected + " but was " + state);
        }
    }
}
