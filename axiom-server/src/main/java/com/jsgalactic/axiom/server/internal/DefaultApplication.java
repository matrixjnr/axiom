package com.jsgalactic.axiom.server.internal;

import com.jsgalactic.axiom.application.Application;
import com.jsgalactic.axiom.context.ErrorHandler;
import com.jsgalactic.axiom.context.Handler;
import com.jsgalactic.axiom.context.Middleware;
import com.jsgalactic.axiom.error.AxiomException;
import com.jsgalactic.axiom.error.NotAcceptableException;
import com.jsgalactic.axiom.error.PayloadTooLargeException;
import com.jsgalactic.axiom.execution.AdmissionPolicy;
import com.jsgalactic.axiom.execution.ExecutionContext;
import com.jsgalactic.axiom.http.Request;
import com.jsgalactic.axiom.http.Response;
import com.jsgalactic.axiom.http.spi.HttpTransportProvider;
import com.jsgalactic.axiom.lifecycle.Server;
import com.jsgalactic.axiom.observability.Metrics;
import com.jsgalactic.axiom.routing.Route;
import com.jsgalactic.axiom.routing.RouteGroup;
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
import java.util.function.Consumer;

final class DefaultApplication implements Application {
    /**
     * Immutable configuration published at startup. The request path reads it through one
     * volatile field and takes no lock; {@code router} is null once the application closes.
     */
    private record Runtime(CompiledRouter router, Handler unmatched, ErrorHandlers errors, List<Route> routes,
                           Set<Route> routeSet, Map<Route, AdmissionPolicy> routePolicies,
                           AdmissionPolicy defaultPolicy, Codecs codecs, int maxRequestBody) {
        /** Drops every reference to application handlers, middleware and error handlers. */
        Runtime withoutRouter() {
            return new Runtime(null, null, ErrorHandlers.NONE, routes, routeSet, routePolicies, defaultPolicy,
                    codecs, maxRequestBody);
        }
    }

    /** One registered route before startup: its handler, scope and route-level middleware. */
    private record Registration(Handler handler, Scope scope, List<Middleware> middleware) {}

    /** Largest configurable request body; bodies are buffered in memory before the handler runs. */
    static final int MAX_REQUEST_BODY_LIMIT = 64 * 1024 * 1024;
    private static final System.Logger LOG = System.getLogger(DefaultApplication.class.getName());

    // Configuration state; guarded by this until startup publishes a runtime snapshot.
    private final Map<Route, Registration> registrations = new LinkedHashMap<>();
    private final Scope root = new Scope(null, "");
    private final Map<Class<?>, ErrorHandler<?>> errorHandlers = new LinkedHashMap<>();
    private final Map<Route, AdmissionPolicy> routePolicies = new LinkedHashMap<>();
    private final Map<String, Route> shapes = new HashMap<>();
    /** Group callbacks currently running; startup is refused while any is open. */
    private int openGroups;
    private final List<Server> listeners = new ArrayList<>();
    private volatile AdmissionPolicy admissionPolicy = AdmissionPolicy.reject(36);
    private volatile Metrics metrics = Metrics.NOOP;
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
    public Route route(String method, String path, Handler handler, Middleware... middleware) {
        return register(root, method, path, handler, middleware);
    }

    @Override
    public Application use(Middleware middleware) {
        root.use(middleware);
        return this;
    }

    @Override
    public Application group(String prefix, Consumer<RouteGroup> configure) {
        root.group(prefix, configure);
        return this;
    }

    private synchronized Route register(Scope scope, String method, String relativePath, Handler handler,
                                        Middleware[] middleware) {
        requireState(State.CONFIGURING);
        scope.requireOpen();
        Objects.requireNonNull(handler, "handler");
        Objects.requireNonNull(relativePath, "path");
        var routeMiddleware = List.of(Objects.requireNonNull(middleware, "middleware"));
        if (scope != root && !relativePath.isEmpty() && !relativePath.startsWith("/")) {
            throw new IllegalArgumentException("A path in a route group must be empty or start with '/': " + relativePath);
        }
        var path = scope.prefix + relativePath;
        var route = new Route(method, path);
        if (method.equals("TRACE")) {
            // A TRACE response reflects the request, including credentials (cross-site tracing).
            throw new IllegalArgumentException("TRACE routes are not supported: echoing requests can expose credentials");
        }
        if (method.equals("CONNECT")) {
            // CONNECT turns the connection into a tunnel; no transport supports that.
            throw new IllegalArgumentException("CONNECT routes are not supported: CONNECT is answered 501");
        }
        if (registrations.containsKey(route)) {
            throw new IllegalArgumentException("Duplicate route: " + method + " " + path);
        }
        var previous = shapes.putIfAbsent(method + " " + shape(path), route);
        if (previous != null) {
            throw new IllegalArgumentException("Ambiguous routes for " + method + ": "
                    + previous.path() + " and " + path);
        }
        registrations.put(route, new Registration(handler, scope, routeMiddleware));
        return route;
    }

    @Override
    public synchronized <E extends Exception> Application error(Class<E> type, ErrorHandler<? super E> handler) {
        requireState(State.CONFIGURING);
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(handler, "handler");
        if (errorHandlers.putIfAbsent(type, handler) != null) {
            throw new IllegalArgumentException("An error handler is already registered for " + type.getName());
        }
        return this;
    }

    /**
     * Validates a group prefix appended to its parent's prefix: empty, or starting with '/', not
     * ending with '/', without a wildcard, and valid as a template on its own.
     */
    private static String groupPrefix(String parent, String prefix) {
        Objects.requireNonNull(prefix, "prefix");
        if (prefix.isEmpty()) { return parent; }
        if (!prefix.startsWith("/") || prefix.endsWith("/") || prefix.contains("/*")) {
            throw new IllegalArgumentException("A group prefix must be empty or start with '/', must not end with '/'"
                    + " and must not contain a wildcard: " + prefix);
        }
        var composed = parent + prefix;
        new Route("GET", composed);
        return composed;
    }

    /**
     * The application (root) or a route group. Mutable state is guarded by the application's
     * lifecycle lock; a group closes when its configuration callback returns.
     */
    private final class Scope implements RouteGroup {
        private final Scope parent;
        private final String prefix;
        private final List<Middleware> middleware = new ArrayList<>();
        private boolean open = true;

        private Scope(Scope parent, String prefix) {
            this.parent = parent;
            this.prefix = prefix;
        }

        @Override
        public Route route(String method, String path, Handler handler, Middleware... routeMiddleware) {
            return register(this, method, path, handler, routeMiddleware);
        }

        @Override
        public RouteGroup use(Middleware added) {
            Objects.requireNonNull(added, "middleware");
            synchronized (DefaultApplication.this) {
                requireState(State.CONFIGURING);
                requireOpen();
                middleware.add(added);
            }
            return this;
        }

        @Override
        public RouteGroup group(String childPrefix, Consumer<RouteGroup> configure) {
            Objects.requireNonNull(configure, "configure");
            Scope child;
            synchronized (DefaultApplication.this) {
                requireState(State.CONFIGURING);
                requireOpen();
                child = new Scope(this, groupPrefix(prefix, childPrefix));
                openGroups++;
            }
            // User code runs outside the lifecycle lock; each registration takes it again.
            try {
                configure.accept(child);
            } catch (Throwable failure) {
                // A partly configured group may lack middleware (authentication, for example) that
                // a later statement would have added, so nothing it registered may be served.
                synchronized (DefaultApplication.this) { rollBack(child); }
                throw failure;
            } finally {
                synchronized (DefaultApplication.this) {
                    child.open = false;
                    openGroups--;
                }
            }
            return this;
        }

        private boolean within(Scope ancestor) {
            for (var scope = this; scope != null; scope = scope.parent) {
                if (scope == ancestor) { return true; }
            }
            return false;
        }

        private void requireOpen() {
            if (!open) { throw new IllegalStateException("Route group configuration has ended; register inside its callback"); }
        }

        /** Middleware of the enclosing groups and this one, outermost first, without the global ones. */
        private void collectGroupMiddleware(List<Middleware> into) {
            if (parent == null) { return; }
            parent.collectGroupMiddleware(into);
            into.addAll(middleware);
        }
    }

    /** Removes every route registered in a scope or its nested groups, with its shape and policy. */
    private void rollBack(Scope scope) {
        var removed = registrations.entrySet().iterator();
        while (removed.hasNext()) {
            var entry = removed.next();
            if (!entry.getValue().scope().within(scope)) { continue; }
            var route = entry.getKey();
            shapes.remove(route.method() + " " + shape(route.path()), route);
            routePolicies.remove(route);
            removed.remove();
        }
        scope.middleware.clear();
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
        if (openGroups > 0) {
            // A route of an open group could be compiled without the middleware its callback adds later.
            throw new IllegalStateException("Cannot start while a route group is being configured");
        }
        var codecs = Codecs.discover();
        var global = List.copyOf(root.middleware);
        var chains = new LinkedHashMap<Route, Handler>();
        registrations.forEach((route, registration) -> {
            var chain = new ArrayList<>(global);
            registration.scope().collectGroupMiddleware(chain);
            chain.addAll(registration.middleware());
            chains.put(route, Pipeline.compose(chain, registration.handler()));
        });
        var router = CompiledRouter.compile(chains);
        // Answers the router produces itself are wrapped by global middleware only.
        var unmatched = Pipeline.compose(global, context -> ((DefaultContext) context).frameworkAnswer());
        runtime = snapshot(router, unmatched, new ErrorHandlers(errorHandlers), codecs);
        registrations.clear();
        shapes.clear();
        state = State.RUNNING;
        return this;
    }

    private Runtime snapshot(CompiledRouter router, Handler unmatched, ErrorHandlers errors, Codecs codecs) {
        var routes = List.copyOf(registrations.keySet());
        return new Runtime(router, unmatched, errors, routes, Set.copyOf(routes), Map.copyOf(routePolicies), admissionPolicy,
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

    @Override public synchronized Application metrics(Metrics metrics) {
        requireState(State.CONFIGURING);
        this.metrics = Objects.requireNonNull(metrics, "metrics");
        return this;
    }

    @Override public Metrics metrics() { return metrics; }

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
        // A stream has no known length and its writer must not run: HEAD gets the head only.
        if (response.isStreaming() || status < 200 || status > 299 || status == 204 || status == 205) {
            return response.withoutBody();
        }
        var bytes = ResponseSerialization.bodyBytes(response.body());
        if (bytes == null) { return response; }
        return response.withoutBody().withHeader("Content-Length", Integer.toString(bytes.length));
    }

    private static Response dispatch(Runtime published, Request request, ExecutionContext execution) throws Exception {
        if (request.body().length() > published.maxRequestBody()) { throw new PayloadTooLargeException(); }
        // CONNECT requests a tunnel, which is not supported for any target (RFC 9110 15.6.2).
        if (request.method().equals("CONNECT")) { return Problems.response(501, execution.requestId()); }
        var match = published.router().match(request);
        DefaultContext context;
        Handler chain;
        if (match != null && match.methodAllowed()) {
            context = new DefaultContext(request, match, execution, published.codecs(), null);
            chain = match.handler();
        } else {
            context = new DefaultContext(request, null, execution, published.codecs(),
                    frameworkAnswer(published.router(), request, match, execution));
            chain = published.unmatched();
        }
        try {
            // Composed chains always return a Response; encoding runs once, after every middleware.
            return encode(published.codecs(), request, (Response) chain.handle(context), true);
        } catch (Exception failure) {
            return handleError(published, context, failure);
        }
    }

    /**
     * Offers an exception from the chain to the registered error handlers. Without one it is
     * rethrown: AxiomExceptions get their problem response, others propagate. An AxiomException
     * thrown by the error handler is rethrown for its problem response and not handled again;
     * any other failure of the error handler becomes the generic 500.
     */
    private static Response handleError(Runtime published, DefaultContext context, Exception failure) throws Exception {
        if (cancelled(context, failure)) { throw failure; }
        var handler = published.errors().find(failure.getClass());
        if (handler == null) { throw failure; }
        context.resetStatus();
        try {
            var response = handler.handle(context, failure);
            if (response == null) {
                throw new IllegalStateException("Error handler for " + failure.getClass().getName() + " returned null");
            }
            // Error responses are sent whatever the client accepts, like problem responses.
            var encoded = encode(published.codecs(), context.request(), response, false);
            logMapped(context, failure, encoded.status());
            return encoded;
        } catch (AxiomException translated) {
            logMapped(context, failure, translated.status());
            throw translated;
        } catch (Exception broken) {
            if (broken instanceof InterruptedException) { Thread.currentThread().interrupt(); }
            broken.addSuppressed(failure);
            LOG.log(System.Logger.Level.ERROR, "Request " + context.execution().requestId()
                    + " failed and its error handler failed too", broken);
            return Problems.response(500, context.execution().requestId());
        }
    }

    /**
     * Logs a failure an error handler mapped, server-side only, unless it is an expected client
     * error: an AxiomException below 500 answered below 500.
     */
    private static void logMapped(DefaultContext context, Exception failure, int status) {
        boolean clientError = failure instanceof AxiomException axiom && axiom.status() < 500;
        if (status >= 500 || !clientError) {
            LOG.log(System.Logger.Level.WARNING, "Request " + context.execution().requestId() + " failed; its error handler"
                    + " answered " + status, failure);
        }
    }

    /**
     * Whether the request was cancelled or ran out of time: its outcome is discarded, so no
     * application error handler runs. Interruption and cancellation are never offered at all.
     */
    private static boolean cancelled(DefaultContext context, Exception failure) {
        return failure instanceof InterruptedException || failure instanceof java.util.concurrent.CancellationException
                || Thread.currentThread().isInterrupted() || context.execution().isExpired();
    }

    /** The router's own answer when no route serves the request method on its path. */
    private static Response frameworkAnswer(CompiledRouter router, Request request, CompiledRouter.Match match,
                                            ExecutionContext execution) {
        if (match == null) {
            // RFC 9110 15.6.2: a method the server does not recognize for any resource is 501.
            return Problems.response(router.recognizes(request.method()) ? 404 : 501, execution.requestId());
        }
        // OPTIONS *, or a routed path where no matching template registered OPTIONS: no handler runs.
        if (request.method().equals("OPTIONS")) { return Response.of(204, null).withHeader("Allow", match.allow()); }
        return Problems.response(405, execution.requestId()).withHeader("Allow", match.allow());
    }

    /**
     * Prepares a response whose Content-Type has an installed codec: checks the request's Accept
     * header (406 when nothing matches) and encodes values other than String and byte[].
     */
    private static Response encode(Codecs codecs, Request request, Response response, boolean negotiate) {
        var mediaType = Codecs.mediaType(response.headers().get("Content-Type"));
        var codec = mediaType == null ? null : codecs.forMediaType(mediaType);
        if (codec == null) { return response; }
        if (negotiate && !Codecs.acceptable(request.header("Accept").orElse(null), mediaType)) {
            throw new NotAcceptableException();
        }
        var body = response.body();
        if (body == null || body instanceof String || body instanceof byte[] || response.isStreaming()) { return response; }
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
            runtime = runtime == null ? snapshot(null, null, ErrorHandlers.NONE, Codecs.of(List.of())) : runtime.withoutRouter();
            state = State.CLOSED;
            registrations.clear();
            root.middleware.clear();
            errorHandlers.clear();
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
