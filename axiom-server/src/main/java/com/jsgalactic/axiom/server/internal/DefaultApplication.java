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
import com.jsgalactic.axiom.lifecycle.ListenerOptions;
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
import java.util.ServiceLoader;
import java.util.Set;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;

final class DefaultApplication implements Application {
    /**
     * Immutable configuration published at startup. The request path reads it through one
     * volatile field and takes no lock; {@code router} is null once the application closes.
     */
    private record Runtime(CompiledRouter router, Handler unmatched, List<Route> routes,
                           Set<Route> routeSet, Map<Route, AdmissionPolicy> routePolicies,
                           AdmissionPolicy defaultPolicy, Codecs codecs, int maxRequestBody) {
        /** Drops every reference to application handlers, middleware and error handlers. */
        Runtime withoutRouter() {
            return new Runtime(null, null, routes, routeSet, routePolicies, defaultPolicy,
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
    private final Map<Route, AdmissionPolicy> routePolicies = new LinkedHashMap<>();
    private final Map<String, Route> shapes = new HashMap<>();
    private final Set<String> recognizedMethods = new java.util.TreeSet<>();
    private Handler notFoundHandler;
    private Handler methodNotAllowedHandler;
    private Handler notImplementedHandler;
    /** Group callbacks currently running; startup is refused while any is open. */
    private int openGroups;
    private final List<Server> listeners = new ArrayList<>();
    private volatile AdmissionPolicy admissionPolicy = AdmissionPolicy.reject(36);
    private volatile Metrics metrics = Metrics.NOOP;
    private volatile FailureLog failureLog = FailureLog.DEFAULT;
    private volatile boolean developmentErrors;
    private volatile Duration requestTimeout = Duration.ofSeconds(10);
    private volatile int maxRequestBody = 1024 * 1024;
    private volatile State state = State.CONFIGURING;
    private volatile Runtime runtime;
    private final ShutdownHooks hooks;
    /** The registered JVM shutdown hook, or null; guarded by this. */
    private Thread shutdownHook;
    /** The longest shutdown grace period of any listener bound so far; guarded by this. */
    private Duration longestGrace = ListenerOptions.defaults().shutdownGrace();

    /** Registration of JVM shutdown hooks; a seam so tests can run a hook without ending the JVM. */
    interface ShutdownHooks {
        void add(Thread hook);
        void remove(Thread hook);
    }

    /** Time added to the longest grace period for the stop of executors and event loops. */
    static final Duration SHUTDOWN_HOOK_MARGIN = Duration.ofSeconds(5);
    private final Duration hookMargin;

    DefaultApplication() {
        this(new ShutdownHooks() {
            @Override public void add(Thread hook) { java.lang.Runtime.getRuntime().addShutdownHook(hook); }
            @Override public void remove(Thread hook) { java.lang.Runtime.getRuntime().removeShutdownHook(hook); }
        });
    }

    DefaultApplication(ShutdownHooks hooks) { this(hooks, SHUTDOWN_HOOK_MARGIN); }

    DefaultApplication(ShutdownHooks hooks, Duration hookMargin) {
        this.hooks = hooks;
        this.hookMargin = hookMargin;
    }

    @Override
    public Server listen(InetSocketAddress address, ListenerOptions options) throws IOException {
        Objects.requireNonNull(address, "address");
        Objects.requireNonNull(options, "options");
        if (state == State.CLOSED) { throw new IllegalStateException("Application is closed"); }
        if (developmentErrors && (address.isUnresolved() || !address.getAddress().isLoopbackAddress())) {
            throw new IllegalStateException("Development errors expose exception details in responses and are"
                    + " allowed only on a loopback address, not " + address);
        }
        // Provider discovery and binding can block; neither runs under the lifecycle lock.
        var providers = ServiceLoader.load(HttpTransportProvider.class).iterator();
        if (!providers.hasNext()) {
            throw new IllegalStateException("No HTTP transport provider; add axiom-http");
        }
        var provider = providers.next();
        if (providers.hasNext()) { throw new IllegalStateException("Multiple HTTP transport providers"); }
        start();
        return adopt(provider.bind(this, address, options), options);
    }

    /** Takes ownership of a bound listener, or closes it when the application closed meanwhile. */
    Server adopt(Server server, ListenerOptions options) {
        synchronized (this) {
            if (state != State.CLOSED) {
                listeners.removeIf(listener -> listener.termination().toCompletableFuture().isDone());
                listeners.add(server);
                if (options.shutdownGrace().compareTo(longestGrace) > 0) { longestGrace = options.shutdownGrace(); }
                return server;
            }
        }
        // The application closed while binding; it must not leave an unowned listener behind.
        server.close();
        throw new IllegalStateException("Application closed while the listener was binding");
    }

    @Override
    public Application closeOnJvmShutdown() {
        synchronized (this) {
            if (state == State.CLOSED) { throw new IllegalStateException("Application is closed"); }
            if (shutdownHook != null) { return this; }
            var hook = new Thread(this::shutdownFromJvm, "axiom-shutdown");
            hooks.add(hook); // IllegalStateException when the JVM is already shutting down
            shutdownHook = hook;
        }
        return this;
    }

    /** The shutdown hook: closes the application, then waits for its listeners, within a bound. */
    private void shutdownFromJvm() {
        List<Server> owned;
        Duration wait;
        synchronized (this) {
            owned = List.copyOf(listeners);
            wait = longestGrace.plus(hookMargin);
        }
        close();
        long deadline = System.nanoTime() + wait.toNanos();
        for (var server : owned) {
            try {
                server.termination().toCompletableFuture()
                        .get(Math.max(0, deadline - System.nanoTime()), java.util.concurrent.TimeUnit.NANOSECONDS);
            } catch (TimeoutException | java.util.concurrent.ExecutionException unfinished) {
                LOG.log(System.Logger.Level.WARNING,
                        "A listener had not terminated " + wait + " after JVM shutdown began", unfinished);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return;
            }
        }
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
    public synchronized Application notFound(Handler handler) {
        requireState(State.CONFIGURING);
        notFoundHandler = Objects.requireNonNull(handler, "handler");
        return this;
    }

    @Override
    public synchronized Application methodNotAllowed(Handler handler) {
        requireState(State.CONFIGURING);
        methodNotAllowedHandler = Objects.requireNonNull(handler, "handler");
        return this;
    }

    @Override
    public synchronized Application notImplemented(Handler handler) {
        requireState(State.CONFIGURING);
        notImplementedHandler = Objects.requireNonNull(handler, "handler");
        return this;
    }

    @Override
    public synchronized Application recognizeMethods(String... methods) {
        requireState(State.CONFIGURING);
        var declared = new java.util.TreeSet<String>();
        for (var method : Objects.requireNonNull(methods, "methods")) {
            Objects.requireNonNull(method, "method");
            new Request(method, "/"); // IllegalArgumentException ("Invalid HTTP method") for a non-token
            if (method.equals("CONNECT")) {
                throw new IllegalArgumentException("CONNECT is answered 501 and cannot be declared as recognized");
            }
            declared.add(method);
        }
        recognizedMethods.addAll(declared);
        return this;
    }

    @Override
    public <E extends Exception> Application error(Class<E> type, ErrorHandler<? super E> handler) {
        root.error(type, handler);
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
        private final Map<Class<?>, ErrorHandler<?>> errors = new LinkedHashMap<>();
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
        public <E extends Exception> RouteGroup error(Class<E> type, ErrorHandler<? super E> handler) {
            Objects.requireNonNull(type, "type");
            Objects.requireNonNull(handler, "handler");
            synchronized (DefaultApplication.this) {
                requireState(State.CONFIGURING);
                requireOpen();
                if (errors.putIfAbsent(type, handler) != null) {
                    throw new IllegalArgumentException("An error handler is already registered for " + type.getName());
                }
            }
            return this;
        }

        /** Error handlers of this scope and its enclosing ones, innermost first. */
        private List<ErrorHandlers> errorScopes() {
            var scopes = new ArrayList<ErrorHandlers>();
            for (var scope = this; scope != null; scope = scope.parent) { scopes.add(new ErrorHandlers(scope.errors)); }
            return scopes;
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
        scope.errors.clear();
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
        if (developmentErrors) {
            LOG.log(System.Logger.Level.WARNING, "Development errors are on: responses describe exceptions."
                    + " Never use them in production.");
        }
        var codecs = Codecs.discover();
        var global = List.copyOf(root.middleware);
        var rootScopes = root.errorScopes();
        var chains = new LinkedHashMap<Route, Handler>();
        registrations.forEach((route, registration) -> {
            var chain = new ArrayList<>(global);
            registration.scope().collectGroupMiddleware(chain);
            chain.addAll(registration.middleware());
            chains.put(route, Pipeline.compose(chain, registration.handler(),
                    registration.scope().errorScopes(), failureLog, developmentErrors));
        });
        var router = CompiledRouter.compile(chains, Set.copyOf(recognizedMethods));
        // Answers the router produces itself are wrapped by global middleware only.
        var unmatched = Pipeline.compose(global, unmatchedTerminal(notFoundHandler, methodNotAllowedHandler,
                notImplementedHandler), rootScopes, failureLog, developmentErrors);
        runtime = snapshot(router, unmatched, codecs);
        registrations.clear();
        shapes.clear();
        state = State.RUNNING;
        return this;
    }

    /**
     * The innermost step for requests no route serves: the router's own answer, or the handler the
     * application installed for its status. A handler runs with that status preset on the context;
     * the 405 {@code Allow} list is the router's whatever the handler sets. The automatic OPTIONS
     * answer (204) has no hook.
     */
    private static Handler unmatchedTerminal(Handler notFound, Handler methodNotAllowed, Handler notImplemented) {
        return context -> {
            var unmatched = (DefaultContext) context;
            var answer = unmatched.frameworkAnswer();
            var hook = switch (answer.status()) {
                case 404 -> notFound;
                case 405 -> methodNotAllowed;
                case 501 -> notImplemented;
                default -> null;
            };
            if (hook == null) { return answer; }
            unmatched.status(answer.status());
            var result = hook.handle(unmatched);
            var response = result instanceof Response given ? given : unmatched.response(result);
            return answer.status() == 405 ? response.withHeader("Allow", answer.headers().get("Allow")) : response;
        };
    }

    private Runtime snapshot(CompiledRouter router, Handler unmatched, Codecs codecs) {
        var routes = List.copyOf(registrations.keySet());
        return new Runtime(router, unmatched, routes, Set.copyOf(routes), Map.copyOf(routePolicies), admissionPolicy,
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

    @Override public synchronized Application developmentErrors() {
        requireState(State.CONFIGURING);
        developmentErrors = true;
        return this;
    }

    @Override public synchronized Application failureLog(System.Logger logger, System.Logger.Level level) {
        requireState(State.CONFIGURING);
        failureLog = new FailureLog(logger, level);
        return this;
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
            // Failures before routing (413) and exceptions of afterError, which the chain did not map.
            if (failure.status() >= 500) {
                failureLog.failure("Request " + execution.requestId() + " failed with "
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
            context = new DefaultContext(request, match, execution, published.codecs(), null, published.router());
            chain = match.handler();
        } else {
            context = new DefaultContext(request, null, execution, published.codecs(),
                    frameworkAnswer(published.router(), request, match, execution), published.router());
            chain = published.unmatched();
        }
        // The chain's outermost step encodes the response and maps exceptions; see ErrorBoundary.
        return (Response) chain.handle(context);
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

    @Override
    public State state() { return state; }

    @Override
    public void close() {
        List<Server> owned;
        Thread hook;
        synchronized (this) {
            hook = shutdownHook;
            shutdownHook = null;
            runtime = runtime == null ? snapshot(null, null, Codecs.of(List.of())) : runtime.withoutRouter();
            state = State.CLOSED;
            registrations.clear();
            root.middleware.clear();
            root.errors.clear();
            recognizedMethods.clear();
            notFoundHandler = null;
            methodNotAllowedHandler = null;
            notImplementedHandler = null;
            shapes.clear();
            routePolicies.clear();
            owned = List.copyOf(listeners);
            listeners.clear();
        }
        owned.forEach(Server::close);
        // An explicit close makes the hook unnecessary; the hook itself, or a JVM already shutting
        // down, must not unregister.
        if (hook != null && Thread.currentThread() != hook) {
            try { hooks.remove(hook); } catch (IllegalStateException shuttingDown) { /* The hook is running or due. */ }
        }
    }

    private void requireState(State expected) {
        if (state != expected) {
            throw new IllegalStateException("Expected application state " + expected + " but was " + state);
        }
    }
}
