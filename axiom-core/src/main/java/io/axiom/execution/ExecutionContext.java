package io.axiom.execution;

import java.time.Duration;
import java.util.function.LongSupplier;
import java.util.Objects;
import java.util.UUID;

/** Immutable request identity and monotonic deadline; safe to share with application tasks. */
public final class ExecutionContext {
    private final String requestId = UUID.randomUUID().toString();
    private final long started;
    private final long budget;
    private final LongSupplier clock;

    private ExecutionContext(Duration timeout, LongSupplier clock) {
        this.clock = clock;
        budget = timeout.toNanos();
        started = clock.getAsLong();
    }

    /**
     * Creates a fresh identity and starts a deadline using the monotonic system clock.
     * @param timeout positive budget, at most one day
     * @return execution metadata
     * @throws IllegalArgumentException for an invalid budget
     */
    public static ExecutionContext create(Duration timeout) {
        return create(timeout, System::nanoTime);
    }

    static ExecutionContext create(Duration timeout, LongSupplier clock) {
        validateTimeout(timeout);
        return new ExecutionContext(timeout, Objects.requireNonNull(clock, "clock"));
    }

    /**
     * Validates a supported deadline budget.
     * @param timeout positive duration, at most one day
     * @throws IllegalArgumentException for an invalid budget
     */
    public static void validateTimeout(Duration timeout) {
        Objects.requireNonNull(timeout, "timeout");
        if (timeout.isZero() || timeout.isNegative() || timeout.compareTo(Duration.ofDays(1)) > 0) {
            throw new IllegalArgumentException("Request timeout must be positive and at most one day");
        }
    }

    /**
     * Returns a framework-generated identity; client headers cannot choose it.
     * @return unique request identifier
     */
    public String requestId() { return requestId; }

    /**
     * Returns the remaining budget, clamped at zero after expiry.
     * @return remaining monotonic duration
     */
    public Duration remainingTime() {
        return Duration.ofNanos(Math.max(0, budget - (clock.getAsLong() - started)));
    }

    /**
     * Reports whether the deadline has elapsed.
     * @return true once no budget remains
     */
    public boolean isExpired() { return remainingTime().isZero(); }
}
