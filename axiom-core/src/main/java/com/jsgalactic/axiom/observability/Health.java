package com.jsgalactic.axiom.observability;

import com.jsgalactic.axiom.application.Application;
import com.jsgalactic.axiom.context.Middleware;
import com.jsgalactic.axiom.http.Response;
import com.jsgalactic.axiom.routing.RouteGroup;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.LongSupplier;
import java.util.regex.Pattern;

/**
 * Liveness and readiness for orchestrators and load balancers.
 *
 * <p><strong>Liveness</strong> answers "should this process be restarted?". With no liveness
 * checks it is up whenever the process can answer. <strong>Readiness</strong> answers "should this
 * instance receive traffic?". It is down while the application is not running, once
 * {@link #beginDrain()} was called, and while any readiness check is unhealthy. A prober should
 * stop sending traffic on a failing readiness probe and restart only on a failing liveness probe.
 *
 * <pre>{@code
 * var health = Health.builder(app)
 *         .readiness("database", () -> pool.isValid())
 *         .build();
 * health.register(app, security.hasRole("ops"));   // GET /health/live and GET /health/ready
 * // on SIGTERM: health.beginDrain(); wait for the balancer to notice; app.close();
 * }</pre>
 *
 * <p><strong>Exposure.</strong> The routes answer without authentication unless the middleware
 * passed to {@link #register} requires it. Check names and states are operational detail: do not
 * expose them to the public internet without security middleware or a private listener.
 *
 * <p><strong>Startup.</strong> The startup probe answers "has this instance finished starting?". It is
 * DOWN until the application is running and every startup check passes, then UP for good: once it has
 * succeeded no startup check runs again, so an expensive warm-up check costs nothing afterwards. An
 * orchestrator holds back its liveness and readiness probes until it succeeds.
 *
 * <p><strong>Draining.</strong> Building a {@code Health} registers {@link #beginDrain()} with the
 * application (see {@link Application#onDrain}), so a JVM shutdown hook opted into with
 * {@link Application#closeOnJvmShutdown(Duration)} turns readiness DOWN first, waits the drain delay for
 * the balancer to notice, and only then closes the application and its listeners.
 *
 * <p><strong>Caching.</strong> {@link Builder#cacheFor} keeps each check's result for a short time, so a
 * frequently polled expensive check runs at most once per interval however many probes arrive.
 *
 * <p>An instance is immutable apart from the drain flag, the startup latch and the result caches, and is
 * thread-safe.
 */
public final class Health {
    /** Path of the liveness route. */
    public static final String LIVE_PATH = "/health/live";
    /** Path of the readiness route. */
    public static final String READY_PATH = "/health/ready";
    /** Path of the startup route. */
    public static final String STARTUP_PATH = "/health/startup";
    /** Probe timeout when none is configured. */
    public static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(2);
    /** Longest probe timeout. */
    public static final Duration MAX_TIMEOUT = Duration.ofSeconds(30);
    /** Most checks per probe. */
    public static final int MAX_CHECKS = 32;
    /** Longest time a check result may be cached. */
    public static final Duration MAX_CACHE = Duration.ofMinutes(1);

    private static final Pattern NAME = Pattern.compile("[A-Za-z0-9_.-]{1,64}");
    private static final System.Logger LOG = System.getLogger(Health.class.getName());

    /** Outcome of a check or probe. */
    public enum Status { UP, DOWN }

    /**
     * The outcome of one probe.
     * @param status UP only when every check is UP and, for readiness, the application accepts traffic
     * @param checks each check's state in registration order; empty when no check ran
     */
    public record Report(Status status, Map<String, Status> checks) {
        /** Copies the checks. */
        public Report {
            Objects.requireNonNull(status, "status");
            checks = java.util.Collections.unmodifiableMap(new LinkedHashMap<>(checks));
        }

        /**
         * Renders the report as small JSON: {@code {"status":"UP","checks":{"database":"UP"}}}.
         * Check names are restricted to letters, digits and {@code _ . -}, so no escaping is needed.
         * @return JSON text
         */
        public String toJson() {
            var json = new StringBuilder("{\"status\":\"").append(status).append('"');
            if (!checks.isEmpty()) {
                json.append(",\"checks\":{");
                boolean first = true;
                for (var check : checks.entrySet()) {
                    if (!first) { json.append(','); }
                    first = false;
                    json.append('"').append(check.getKey()).append("\":\"").append(check.getValue()).append('"');
                }
                json.append('}');
            }
            return json.append('}').toString();
        }
    }

    /** Collects checks before {@link #build()}. */
    public static final class Builder {
        private final Application application;
        private final Map<String, Probe> live = new LinkedHashMap<>();
        private final Map<String, Probe> ready = new LinkedHashMap<>();
        private final Map<String, Probe> startup = new LinkedHashMap<>();
        private Duration timeout = DEFAULT_TIMEOUT;
        private Duration cache = Duration.ZERO;
        private LongSupplier clock = System::nanoTime;

        private Builder(Application application) { this.application = application; }

        /**
         * Adds a check that decides whether the process should be restarted.
         * @param name letters, digits and {@code _ . -}, 1 to 64 characters, unique among liveness checks
         * @param check the condition
         * @return this builder
         * @throws IllegalArgumentException for an invalid or duplicate name, or too many checks
         */
        public Builder liveness(String name, HealthCheck check) { return add(live, name, check, null); }

        /**
         * Adds a liveness check with its own timeout.
         * @param name letters, digits and {@code _ . -}, 1 to 64 characters, unique among liveness checks
         * @param check the condition
         * @param timeout how long this check may run, positive, at most {@link #MAX_TIMEOUT}; replaces the probe timeout for it
         * @return this builder
         * @throws IllegalArgumentException for an invalid or duplicate name, an invalid timeout, or too many checks
         */
        public Builder liveness(String name, HealthCheck check, Duration timeout) {
            return add(live, name, check, checkedTimeout(timeout));
        }

        /**
         * Adds a check that decides whether the instance should receive traffic.
         * @param name letters, digits and {@code _ . -}, 1 to 64 characters, unique among readiness checks
         * @param check the condition
         * @return this builder
         * @throws IllegalArgumentException for an invalid or duplicate name, or too many checks
         */
        public Builder readiness(String name, HealthCheck check) { return add(ready, name, check, null); }

        /**
         * Adds a readiness check with its own timeout.
         * @param name letters, digits and {@code _ . -}, 1 to 64 characters, unique among readiness checks
         * @param check the condition
         * @param timeout how long this check may run, positive, at most {@link #MAX_TIMEOUT}; replaces the probe timeout for it
         * @return this builder
         * @throws IllegalArgumentException for an invalid or duplicate name, an invalid timeout, or too many checks
         */
        public Builder readiness(String name, HealthCheck check, Duration timeout) {
            return add(ready, name, check, checkedTimeout(timeout));
        }

        /**
         * Adds a check that must pass once before the instance counts as started, such as a cache
         * warm-up or a completed migration. The startup probe runs these checks until all pass at
         * the same time, then stays UP.
         * @param name letters, digits and {@code _ . -}, 1 to 64 characters, unique among startup checks
         * @param check the condition
         * @return this builder
         * @throws IllegalArgumentException for an invalid or duplicate name, or too many checks
         */
        public Builder startup(String name, HealthCheck check) { return add(startup, name, check, null); }

        /**
         * Adds a startup check with its own timeout.
         * @param name letters, digits and {@code _ . -}, 1 to 64 characters, unique among startup checks
         * @param check the condition
         * @param timeout how long this check may run, positive, at most {@link #MAX_TIMEOUT}; replaces the probe timeout for it
         * @return this builder
         * @throws IllegalArgumentException for an invalid or duplicate name, an invalid timeout, or too many checks
         */
        public Builder startup(String name, HealthCheck check, Duration timeout) {
            return add(startup, name, check, checkedTimeout(timeout));
        }

        /**
         * Keeps the result of each check for a while, so that a check polled by several probers, or
         * often, runs at most once per interval. Healthy, unhealthy and timed-out results are all
         * kept; a check that is still running from an earlier probe is not cached. Draining and a
         * stopped application are never cached. The default is no caching.
         * @param ttl zero for none, or at most {@link #MAX_CACHE}
         * @return this builder
         * @throws IllegalArgumentException for a negative or too long interval
         */
        public Builder cacheFor(Duration ttl) {
            Objects.requireNonNull(ttl, "ttl");
            if (ttl.isNegative() || ttl.compareTo(MAX_CACHE) > 0) {
                throw new IllegalArgumentException("Health cache interval must be from zero to " + MAX_CACHE);
            }
            this.cache = ttl;
            return this;
        }

        /** Replaces the monotonic clock that expires cached results; for tests in this package. */
        Builder clock(LongSupplier clock) {
            this.clock = Objects.requireNonNull(clock, "clock");
            return this;
        }

        /**
         * Sets how long one probe waits for its checks; all checks of a probe run concurrently.
         * @param timeout positive, at most {@link #MAX_TIMEOUT}; the default is {@link #DEFAULT_TIMEOUT}
         * @return this builder
         * @throws IllegalArgumentException for an invalid timeout
         */
        public Builder timeout(Duration timeout) {
            Objects.requireNonNull(timeout, "timeout");
            if (timeout.isZero() || timeout.isNegative() || timeout.compareTo(MAX_TIMEOUT) > 0) {
                throw new IllegalArgumentException("Health timeout must be positive and at most " + MAX_TIMEOUT);
            }
            this.timeout = timeout;
            return this;
        }

        /**
         * Builds the health service.
         * @return a new instance
         */
        public Health build() { return new Health(this); }

        private static Duration checkedTimeout(Duration timeout) {
            Objects.requireNonNull(timeout, "timeout");
            if (timeout.isZero() || timeout.isNegative() || timeout.compareTo(MAX_TIMEOUT) > 0) {
                throw new IllegalArgumentException("Health timeout must be positive and at most " + MAX_TIMEOUT);
            }
            return timeout;
        }

        private Builder add(Map<String, Probe> target, String name, HealthCheck check, Duration own) {
            Objects.requireNonNull(check, "check");
            if (name == null || !NAME.matcher(name).matches()) {
                throw new IllegalArgumentException("Check names are 1 to 64 letters, digits, '_', '.' or '-'");
            }
            if (target.size() >= MAX_CHECKS) { throw new IllegalArgumentException("At most " + MAX_CHECKS + " checks"); }
            if (target.putIfAbsent(name, new Probe(name, check, own)) != null) {
                throw new IllegalArgumentException("Duplicate check name: " + name);
            }
            return this;
        }
    }

    /** A registered check, its own timeout (null for the probe's), its kept result and whether an earlier run is still executing. */
    private static final class Probe {
        private final String name;
        private final HealthCheck check;
        private final Duration own;
        private final AtomicBoolean running = new AtomicBoolean();
        private volatile Kept kept;

        private Probe(String name, HealthCheck check, Duration own) {
            this.name = name;
            this.check = check;
            this.own = own;
        }
    }

    /** A check's result and when it stops being reused, on the health clock. */
    private record Kept(Status status, long until) { }

    private final Application application;
    private final List<Probe> live;
    private final List<Probe> ready;
    private final List<Probe> startup;
    private final Duration timeout;
    private final long cacheNanos;
    private final LongSupplier clock;
    private volatile boolean draining;
    private volatile boolean started;

    private Health(Builder builder) {
        this.application = builder.application;
        this.live = List.copyOf(builder.live.values());
        this.ready = List.copyOf(builder.ready.values());
        this.startup = List.copyOf(builder.startup.values());
        this.timeout = builder.timeout;
        this.cacheNanos = builder.cache.toNanos();
        this.clock = builder.clock;
        try { this.application.onDrain(this::beginDrain); }
        catch (IllegalStateException closed) { /* A closed application is never ready anyway. */ }
    }

    /**
     * Starts building health for an application.
     * @param application the application whose lifecycle readiness follows
     * @return a builder
     */
    public static Builder builder(Application application) {
        return new Builder(Objects.requireNonNull(application, "application"));
    }

    /**
     * Evaluates the startup probe: UP once the application is running and every startup check
     * passes, and UP from then on without running the checks again. While the application is still
     * being configured, or has closed before it ever started, the probe is DOWN without running them.
     * @return the report; its checks are those of the last evaluation that ran them
     */
    public Report startup() {
        if (started) { return new Report(Status.UP, Map.of()); }
        if (application.state() != Application.State.RUNNING) { return new Report(Status.DOWN, Map.of()); }
        var report = run(startup);
        if (report.status() == Status.UP) { started = true; }
        return report;
    }

    /**
     * Makes readiness report DOWN from now on, without running readiness checks. Call it when
     * shutdown begins, before closing the application or listener, so a balancer stops sending
     * traffic while running requests finish. It cannot be undone and is idempotent.
     */
    public void beginDrain() { draining = true; }

    /**
     * Reports whether {@link #beginDrain()} was called.
     * @return true once draining
     */
    public boolean isDraining() { return draining; }

    /**
     * Runs the liveness checks.
     * @return UP when every check is up, or when there are none
     */
    public Report liveness() { return run(live); }

    /**
     * Evaluates readiness: DOWN without running checks when draining or when the application is not
     * running, otherwise the readiness checks.
     * @return the report
     */
    public Report readiness() {
        if (draining || application.state() != Application.State.RUNNING) {
            return new Report(Status.DOWN, Map.of());
        }
        return run(ready);
    }

    /**
     * Registers {@code GET /health/startup}, {@code GET /health/live} and {@code GET /health/ready} on an application or group
     * (under its prefix). Each answers a {@link Report#toJson() JSON report} with
     * {@code Cache-Control: no-store}: 200 when up, 503 when down. HEAD works as for any GET route.
     * @param routes where to register
     * @param middleware middleware applied to both routes, outermost first; pass authentication here
     * @throws IllegalStateException if configuration has ended
     */
    public void register(RouteGroup routes, Middleware... middleware) {
        Objects.requireNonNull(routes, "routes");
        routes.get(STARTUP_PATH, context -> respond(startup()), middleware);
        routes.get(LIVE_PATH, context -> respond(liveness()), middleware);
        routes.get(READY_PATH, context -> respond(readiness()), middleware);
    }

    private static Response respond(Report report) {
        return Response.of(report.status() == Status.UP ? 200 : 503, report.toJson())
                .withHeader("Content-Type", "application/json").withHeader("Cache-Control", "no-store");
    }

    private Report run(List<Probe> probes) {
        if (probes.isEmpty()) { return new Report(Status.UP, Map.of()); }
        long begun = System.nanoTime();
        var executions = new ArrayList<Execution>(probes.size());
        for (var probe : probes) { executions.add(reuse(probe) ? null : start(probe)); }
        var results = new LinkedHashMap<String, Status>();
        boolean up = true;
        for (int i = 0; i < probes.size(); i++) {
            var probe = probes.get(i);
            Status status;
            if (executions.get(i) == null) {
                status = probe.kept.status();
            } else {
                var execution = executions.get(i);
                var own = probe.own == null ? timeout : probe.own;
                status = await(execution, begun + own.toNanos(), own);
                if (cacheNanos > 0 && execution.thread != null) {
                    probe.kept = new Kept(status, clock.getAsLong() + cacheNanos);
                }
            }
            results.put(probe.name, status);
            up &= status == Status.UP;
        }
        return new Report(up ? Status.UP : Status.DOWN, results);
    }

    /** Whether a check's kept result is still fresh. */
    private boolean reuse(Probe probe) {
        var kept = probe.kept;
        return cacheNanos > 0 && kept != null && kept.until() - clock.getAsLong() > 0;
    }

    private record Execution(Probe probe, CompletableFuture<Boolean> outcome, Thread thread) { }

    private static Execution start(Probe probe) {
        var outcome = new CompletableFuture<Boolean>();
        if (!probe.running.compareAndSet(false, true)) {
            outcome.complete(false); // An earlier run has not returned; do not pile up threads behind it.
            LOG.log(System.Logger.Level.WARNING, "Health check {0} is still running from an earlier probe", probe.name);
            return new Execution(probe, outcome, null);
        }
        var thread = Thread.ofVirtual().name("axiom-health-", 0).unstarted(() -> {
            // The flag clears before the outcome is published, so a prober that saw this check
            // finish can start it again.
            boolean healthy;
            try { healthy = probe.check.isHealthy(); }
            catch (Throwable failure) {
                probe.running.set(false);
                outcome.completeExceptionally(failure);
                return;
            }
            probe.running.set(false);
            outcome.complete(healthy);
        });
        thread.start();
        return new Execution(probe, outcome, thread);
    }

    private Status await(Execution execution, long deadline, Duration allowed) {
        try {
            long remaining = Math.max(0, deadline - System.nanoTime());
            return execution.outcome.get(remaining, TimeUnit.NANOSECONDS) ? Status.UP : Status.DOWN;
        } catch (TimeoutException expired) {
            if (execution.thread != null) { execution.thread.interrupt(); }
            LOG.log(System.Logger.Level.WARNING, "Health check {0} timed out after {1}", execution.probe.name, allowed);
            return Status.DOWN;
        } catch (ExecutionException failed) {
            LOG.log(System.Logger.Level.WARNING, "Health check " + execution.probe.name + " failed", failed.getCause());
            return Status.DOWN;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            if (execution.thread != null) { execution.thread.interrupt(); }
            return Status.DOWN;
        }
    }
}
