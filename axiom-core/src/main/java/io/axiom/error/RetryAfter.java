package io.axiom.error;

import java.time.Duration;
import java.util.Objects;

/** Formats Retry-After delays; package-private so the header is only built from a Duration. */
final class RetryAfter {
    private RetryAfter() {}

    static String seconds(Duration delay) {
        Objects.requireNonNull(delay, "retryAfter");
        if (delay.isNegative() || delay.compareTo(Duration.ofDays(1)) > 0) {
            throw new IllegalArgumentException("Retry-After must be between zero and one day");
        }
        long seconds = delay.getSeconds() + (delay.getNano() > 0 ? 1 : 0);
        return Long.toString(seconds);
    }
}
