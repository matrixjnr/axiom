package com.jsgalactic.axiom.observability;

/**
 * One dependency or condition a probe reports on, such as a database connection or a warm cache.
 * Register it with {@link Health.Builder#liveness} or {@link Health.Builder#readiness}.
 *
 * <p>An implementation must be thread-safe and fast. It runs on its own virtual thread under the
 * probe timeout; when the timeout passes the thread is interrupted and the check counts as
 * unhealthy, so a check should honor interruption. A check that is still running from an earlier
 * probe is not started again: it is reported unhealthy until it returns. Returning false, throwing
 * and timing out all mean unhealthy; the reason is logged but never sent to the prober.
 */
@FunctionalInterface
public interface HealthCheck {
    /**
     * Evaluates the condition.
     * @return true when healthy
     * @throws Exception if the condition cannot be determined, which counts as unhealthy
     */
    boolean isHealthy() throws Exception;
}
