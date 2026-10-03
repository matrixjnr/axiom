package io.axiom.server.internal;

import io.axiom.application.Application;
import io.axiom.context.Handler;
import io.axiom.error.AxiomException;
import io.axiom.error.NotAcceptableException;
import io.axiom.error.PayloadTooLargeException;
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
import java.util.HashMap;
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
                           Map<Route, AdmissionPolicy> routePolicies, AdmissionPolicy defaultPolicy,
                           Codecs codecs, int maxRequestBody) {
        Runtime withoutRouter() {
            return new Runtime(null, routes, routeSet, routePolicies, defaultPolicy, codecs, maxRequestBody);
        }
    }

    /** Largest configurable request body; bodies are buffered in memory before the handler runs. */
    static final int MAX_REQUEST_BODY_LIMIT = 64 * 1024 * 1024;
    private static final System.Logger LOG = System.getLogger(DefaultApplication.class.getName());

    // Configuration state; guarded by this until startup publishes a runtime snapshot.
    private final Map<Route, Handler> registrations = new LinkedHashMap<>();
    private final Map<Route, AdmissionPolicy> routePolicies = new LinkedHashMap<>();
    private final Map<String, Route> shapes = new HashMap<>();
    private final List<Server> listeners = new ArrayList<>();
    private volatile AdmissionPolicy admissionPolicy = AdmissionPolicy.reject(36);
    private volatile Duration requestTimeout = Duration.ofSeconds(10);
    private volatile int maxRequestBody = 1024 * 1024;
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
        if (method.equals("TRACE")) {
            // A TRACE response reflects the request, including credentials (cross-site tracing).
            throw new IllegalArgumentException("TRACE routes are not supported: echoing requests can expose credentials");
        }
        if (registrations.containsKey(route)) {
            throw new IllegalArgumentException("Duplicate route: " + method + " " + path);
        }
        var previous = shapes.putIfAbsent(method + " " + shape(path), route);
        if (previous != null) {
            throw new IllegalArgumentException("Ambiguous routes for " + method + ": "
                    + previous.path() + " and " + path);
        }
        registrations.put(route, handler);
        return route;
    }

    /** Template shape with capture names erased; templates of equal shape match the same paths. */
    private static String shape(String path) {
        var shape = new StringBuilder(path.length());
        for (var segment : path.substring(1).split("/", -1)) {
            shape.append('/').append(segment.startsWith(":") ? ":" : segment.startsWith("*") ? "*" : segment);
        }
        return shape.toString();
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
        var codecs = Codecs.discover();
        var router = CompiledRouter.compile(registrations);
        runtime = snapshot(router, codecs);
        registrations.clear();
        shapes.clear();
        state = State.RUNNING;
        return this;
    }

    private Runtime snapshot(CompiledRouter router, Codecs codecs) {
        var routes = List.copyOf(registrations.keySet());
        return new Runtime(router, routes, Set.copyOf(routes), Map.copyOf(routePolicies), admissionPolicy,
                codecs, maxRequestBody);
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

    @Override public synchronized Application maxRequestBody(int bytes) {
        requireState(State.CONFIGURING);
        if (bytes < 0 || bytes > MAX_REQUEST_BODY_LIMIT) {
            throw new IllegalArgumentException("Request body limit must be between 0 and " + MAX_REQUEST_BODY_LIMIT + " bytes");
        }
        maxRequestBody = bytes;
        return this;
    }

    @Override public int maxRequestBody() { return maxRequestBody; }

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
        return acceptingRuntime().router();
    }

    private Runtime acceptingRuntime() {
        var published = runtime;
        if (published == null || published.router() == null) {
            throw new IllegalStateException("Expected application state RUNNING but was " + state);
        }
        return published;
    }

    @Override
    public Response handle(Request request, ExecutionContext execution) throws Exception {
        Objects.requireNonNull(execution, "execution");
        Objects.requireNonNull(request, "request");
        // Admission ends here. User code never runs under the lifecycle lock.
        var published = acceptingRuntime();
        if (execution.isExpired()) { throw new TimeoutException("Request deadline exceeded"); }
        Response response;
        try {
            response = dispatch(published, request, execution);
        } catch (AxiomException failure) {
            if (failure.status() >= 500) {
                LOG.log(System.Logger.Level.WARNING, "Request " + execution.requestId() + " failed with "
                        + failure.status() + " " + failure.code(), failure);
            }
            response = Problems.response(failure, execution.requestId());
        }
        return request.method().equals("HEAD") ? head(response) : response;
    }

    /**
     * Strips the body of a HEAD response. A successful response with content (2xx other than 204
     * and 205) instead carries {@code Content-Length} set to the encoded length the body would
     * have, which the transport sends in place of the bytes. A body the transport could not send
     * is kept, so HEAD fails where GET would (a 500 over HTTP).
     */
    private static Response head(Response response) {
        int status = response.status();
        if (status < 200 || status > 299 || status == 204 || status == 205) { return response.withoutBody(); }
        var bytes = ResponseSerialization.bodyBytes(response.body());
        if (bytes == null) { return response; }
        return response.withoutBody().withHeader("Content-Length", Integer.toString(bytes.length));
    }

    private static Response dispatch(Runtime published, Request request, ExecutionContext execution) throws Exception {
        if (request.body().length() > published.maxRequestBody()) { throw new PayloadTooLargeException(); }
        var match = published.router().match(request);
        if (match == null) { return Problems.response(404, execution.requestId()); }
        if (!match.methodAllowed()) {
            // OPTIONS *, or a routed path where no matching template registered OPTIONS: no handler runs.
            if (request.method().equals("OPTIONS")) { return Response.of(204, null).withHeader("Allow", match.allow()); }
            return Problems.response(405, execution.requestId()).withHeader("Allow", match.allow());
        }
        var context = new DefaultContext(request, match, execution, published.codecs());
        var result = match.handler().handle(context);
        return encode(published.codecs(), request, result instanceof Response explicit ? explicit : context.response(result));
    }

    /**
     * Prepares a response whose Content-Type has an installed codec: checks the request's Accept
     * header (406 when nothing matches) and encodes values other than String and byte[].
     */
    private static Response encode(Codecs codecs, Request request, Response response) {
        var mediaType = Codecs.mediaType(response.headers().get("Content-Type"));
        var codec = mediaType == null ? null : codecs.forMediaType(mediaType);
        if (codec == null) { return response; }
        if (!Codecs.acceptable(request.header("Accept").orElse(null), mediaType)) {
            throw new NotAcceptableException();
        }
        var body = response.body();
        if (body == null || body instanceof String || body instanceof byte[]) { return response; }
        var encoded = Response.of(response.status(), codec.encode(body));
        for (var header : response.headers().entrySet()) {
            encoded = encoded.withHeader(header.getKey(), header.getValue());
        }
        return encoded;
    }

    @Override
    public State state() { return state; }

    @Override
    public void close() {
        List<Server> owned;
        synchronized (this) {
            runtime = runtime == null ? snapshot(null, Codecs.of(List.of())) : runtime.withoutRouter();
            state = State.CLOSED;
            registrations.clear();
            shapes.clear();
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
