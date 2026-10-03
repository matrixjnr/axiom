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
 * <p>An instance is immutable apart from the drain flag and is thread-safe.
 */
public final class Health {
    /** Path of the liveness route. */
    public static final String LIVE_PATH = "/health/live";
    /** Path of the readiness route. */
    public static final String READY_PATH = "/health/ready";
    /** Probe timeout when none is configured. */
    public static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(2);
    /** Longest probe timeout. */
    public static final Duration MAX_TIMEOUT = Duration.ofSeconds(30);
    /** Most checks per probe. */
    public static final int MAX_CHECKS = 32;

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
        private Duration timeout = DEFAULT_TIMEOUT;

        private Builder(Application application) { this.application = application; }

        /**
         * Adds a check that decides whether the process should be restarted.
         * @param name letters, digits and {@code _ . -}, 1 to 64 characters, unique among liveness checks
         * @param check the condition
         * @return this builder
         * @throws IllegalArgumentException for an invalid or duplicate name, or too many checks
         */
        public Builder liveness(String name, HealthCheck check) { return add(live, name, check); }

        /**
         * Adds a check that decides whether the instance should receive traffic.
         * @param name letters, digits and {@code _ . -}, 1 to 64 characters, unique among readiness checks
         * @param check the condition
         * @return this builder
         * @throws IllegalArgumentException for an invalid or duplicate name, or too many checks
         */
        public Builder readiness(String name, HealthCheck check) { return add(ready, name, check); }

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

        private Builder add(Map<String, Probe> target, String name, HealthCheck check) {
            Objects.requireNonNull(check, "check");
            if (name == null || !NAME.matcher(name).matches()) {
                throw new IllegalArgumentException("Check names are 1 to 64 letters, digits, '_', '.' or '-'");
            }
            if (target.size() >= MAX_CHECKS) { throw new IllegalArgumentException("At most " + MAX_CHECKS + " checks"); }
            if (target.putIfAbsent(name, new Probe(name, check)) != null) {
                throw new IllegalArgumentException("Duplicate check name: " + name);
            }
            return this;
        }
    }

    /** A registered check and whether an earlier run of it is still executing. */
    private static final class Probe {
        private final String name;
        private final HealthCheck check;
        private final AtomicBoolean running = new AtomicBoolean();

        private Probe(String name, HealthCheck check) {
            this.name = name;
            this.check = check;
        }
    }

    private final Application application;
    private final List<Probe> live;
    private final List<Probe> ready;
    private final Duration timeout;
    private volatile boolean draining;

    private Health(Builder builder) {
        this.application = builder.application;
        this.live = List.copyOf(builder.live.values());
        this.ready = List.copyOf(builder.ready.values());
        this.timeout = builder.timeout;
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
     * Registers {@code GET /health/live} and {@code GET /health/ready} on an application or group
     * (under its prefix). Each answers a {@link Report#toJson() JSON report} with
     * {@code Cache-Control: no-store}: 200 when up, 503 when down. HEAD works as for any GET route.
     * @param routes where to register
     * @param middleware middleware applied to both routes, outermost first; pass authentication here
     * @throws IllegalStateException if configuration has ended
     */
    public void register(RouteGroup routes, Middleware... middleware) {
        Objects.requireNonNull(routes, "routes");
        routes.get(LIVE_PATH, context -> respond(liveness()), middleware);
        routes.get(READY_PATH, context -> respond(readiness()), middleware);
    }

    private static Response respond(Report report) {
        return Response.of(report.status() == Status.UP ? 200 : 503, report.toJson())
                .withHeader("Content-Type", "application/json").withHeader("Cache-Control", "no-store");
    }

    private Report run(List<Probe> probes) {
        if (probes.isEmpty()) { return new Report(Status.UP, Map.of()); }
        long deadline = System.nanoTime() + timeout.toNanos();
        var started = new ArrayList<Execution>(probes.size());
        for (var probe : probes) { started.add(start(probe)); }
        var results = new LinkedHashMap<String, Status>();
        boolean up = true;
        for (var execution : started) {
            var status = await(execution, deadline);
            results.put(execution.probe.name, status);
            up &= status == Status.UP;
        }
        return new Report(up ? Status.UP : Status.DOWN, results);
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

    private Status await(Execution execution, long deadline) {
        try {
            long remaining = Math.max(0, deadline - System.nanoTime());
            return execution.outcome.get(remaining, TimeUnit.NANOSECONDS) ? Status.UP : Status.DOWN;
        } catch (TimeoutException expired) {
            if (execution.thread != null) { execution.thread.interrupt(); }
            LOG.log(System.Logger.Level.WARNING, "Health check {0} timed out after {1}", execution.probe.name, timeout);
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
