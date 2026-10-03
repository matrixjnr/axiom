package com.jsgalactic.axiom.execution;

import java.security.SecureRandom;
import java.time.Duration;
import java.util.Base64;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;

/** Immutable request identity and monotonic deadline; safe to share with application tasks. */
public final class ExecutionContext {
    /** 96 random bits chosen once per process, so identities from different processes do not collide. */
    private static final String PROCESS_PREFIX = processPrefix();
    private static final AtomicLong SEQUENCE = new AtomicLong();

    private final String requestId = PROCESS_PREFIX + "-" + Long.toHexString(SEQUENCE.incrementAndGet());
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

    private static String processPrefix() {
        var bytes = new byte[12];
        new SecureRandom().nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    /**
     * Returns a framework-generated identity; client headers cannot choose it.
     * The identity is a random per-process prefix (16 URL-safe Base64 characters) followed by
     * {@code -} and a hexadecimal sequence number. It is unique within the process and, with
     * overwhelming probability, across processes. The prefix cannot be guessed without seeing
     * an identity, but anyone who has seen one can predict later identities from the same
     * process, so it is a correlation value and must never be used as a secret or credential.
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
