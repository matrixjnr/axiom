package com.jsgalactic.axiom.execution;

import java.time.Duration;
import java.util.Objects;

/**
 * Immutable execution limits. A zero queue capacity means immediate rejection when busy.
 * @param maxActive maximum reserved or running executions
 * @param maxQueued maximum requests waiting for execution capacity
 * @param queueTimeout maximum wait; zero exactly when queuing is disabled
 */
public record AdmissionPolicy(int maxActive, int maxQueued, Duration queueTimeout) {
    /** Largest accepted {@code maxQueued}; bounds memory held by waiting requests. */
    public static final int MAX_QUEUED_LIMIT = 100_000;

    /**
     * Validates limits before they can be installed.
     * @param maxActive positive active limit
     * @param maxQueued queue limit from zero to {@link #MAX_QUEUED_LIMIT}
     * @param queueTimeout positive wait of at most one day, or zero for no queue
     */
    public AdmissionPolicy {
        Objects.requireNonNull(queueTimeout, "queueTimeout");
        if (maxActive < 1 || maxQueued < 0) {
            throw new IllegalArgumentException("Active capacity must be positive and queue capacity nonnegative");
        }
        if (maxQueued > MAX_QUEUED_LIMIT) {
            throw new IllegalArgumentException("Queue capacity must not exceed " + MAX_QUEUED_LIMIT);
        }
        if (maxQueued == 0) {
            if (!queueTimeout.isZero()) { throw new IllegalArgumentException("A disabled queue requires zero timeout"); }
        } else { ExecutionContext.validateTimeout(queueTimeout); }
    }

    /**
     * Configures immediate rejection when the active limit is occupied.
     * @param maxActive positive active limit
     * @return policy without a waiting queue
     */
    public static AdmissionPolicy reject(int maxActive) {
        return new AdmissionPolicy(maxActive, 0, Duration.ZERO);
    }
}
