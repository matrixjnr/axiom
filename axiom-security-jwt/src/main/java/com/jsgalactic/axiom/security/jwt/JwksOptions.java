package com.jsgalactic.axiom.security.jwt;

import java.time.Duration;
import java.util.Objects;

/**
 * Bounds and timings for a JSON Web Key Set used by {@link JwtAuthenticator}, set with
 * {@link JwtAuthenticator.Builder#jwks(JwksSource, JwksOptions)}. Immutable; every method returns a
 * changed copy.
 *
 * <table class="striped">
 * <caption>Settings</caption>
 * <thead><tr><th>Setting</th><th>Default</th><th>Range</th><th>Meaning</th></tr></thead>
 * <tbody>
 * <tr><td>{@link #maxBytes}</td><td>64 KiB</td><td>1 KiB to 1 MiB</td><td>longest accepted document; at most 100 keys</td></tr>
 * <tr><td>{@link #refreshInterval}</td><td>10 minutes</td><td>1 second to 24 hours</td><td>age after which the set is refetched</td></tr>
 * <tr><td>{@link #minRefreshInterval}</td><td>30 seconds</td><td>1 second to the refresh interval</td><td>shortest time between fetch attempts, successful or not</td></tr>
 * <tr><td>{@link #maxStale}</td><td>24 hours</td><td>the refresh interval to 7 days</td><td>how long the last good set stays usable while fetches fail</td></tr>
 * <tr><td>{@link #defaultAlgorithm}</td><td>none</td><td>-</td><td>algorithm for keys that declare no {@code alg}</td></tr>
 * </tbody>
 * </table>
 */
public final class JwksOptions {
    private static final JwksOptions DEFAULTS = new JwksOptions(65_536, Duration.ofMinutes(10), Duration.ofSeconds(30),
            Duration.ofHours(24), null);

    private final int maxBytes;
    private final Duration refreshInterval;
    private final Duration minRefreshInterval;
    private final Duration maxStale;
    private final JwsAlgorithm defaultAlgorithm;

    private JwksOptions(int maxBytes, Duration refreshInterval, Duration minRefreshInterval, Duration maxStale,
            JwsAlgorithm defaultAlgorithm) {
        this.maxBytes = maxBytes;
        this.refreshInterval = refreshInterval;
        this.minRefreshInterval = minRefreshInterval;
        this.maxStale = maxStale;
        this.defaultAlgorithm = defaultAlgorithm;
    }

    /**
     * Returns the default settings.
     *
     * @return the defaults
     */
    public static JwksOptions defaults() {
        return DEFAULTS;
    }

    /**
     * Sets the longest accepted key set document.
     *
     * @param bytes 1,024 to 1,048,576
     * @return a changed copy
     */
    public JwksOptions maxBytes(int bytes) {
        if (bytes < 1024 || bytes > 1_048_576) { throw new IllegalArgumentException("The key set size limit is 1024 to 1048576 bytes"); }
        return new JwksOptions(bytes, refreshInterval, minRefreshInterval, maxStale, defaultAlgorithm);
    }

    /**
     * Sets the age after which the next token triggers a refetch. Tokens keep being verified with
     * the previous set while the refetch is running or failing.
     *
     * @param interval 1 second to 24 hours, whole seconds
     * @return a changed copy
     */
    public JwksOptions refreshInterval(Duration interval) {
        return new JwksOptions(maxBytes, seconds(interval, 1, 86_400, "refresh interval"), minRefreshInterval, maxStale, defaultAlgorithm);
    }

    /**
     * Sets the shortest time between two fetch attempts, successful or not. It bounds the load a
     * stream of tokens with unknown key IDs can put on the key set endpoint, and the retry rate
     * while the endpoint fails.
     *
     * @param interval 1 second up to the refresh interval, whole seconds
     * @return a changed copy
     */
    public JwksOptions minRefreshInterval(Duration interval) {
        return new JwksOptions(maxBytes, refreshInterval, seconds(interval, 1, 86_400, "minimum refresh interval"), maxStale, defaultAlgorithm);
    }

    /**
     * Sets how long a key set stays usable after its last successful fetch while later fetches
     * fail. After that no key of the set verifies a token (fail closed), so a revoked key cannot
     * outlive an unreachable endpoint forever.
     *
     * @param age the refresh interval up to 7 days, whole seconds
     * @return a changed copy
     */
    public JwksOptions maxStale(Duration age) {
        return new JwksOptions(maxBytes, refreshInterval, minRefreshInterval, seconds(age, 1, 604_800, "maximum staleness"), defaultAlgorithm);
    }

    /**
     * Sets the algorithm for keys of the set that declare no {@code alg} (some issuers omit it).
     * Without it such keys are skipped, because an RSA key alone does not say whether it is meant
     * for RS256 or PS256. The algorithm must fit the key type; a key of another type is skipped.
     * A key that declares an {@code alg} always keeps its own.
     *
     * @param algorithm an RS, PS, ES or EdDSA algorithm
     * @return a changed copy
     */
    public JwksOptions defaultAlgorithm(JwsAlgorithm algorithm) {
        Objects.requireNonNull(algorithm, "algorithm");
        if (algorithm.family() == JwsAlgorithm.Family.HMAC) {
            throw new IllegalArgumentException("A key set never supplies HMAC secrets");
        }
        return new JwksOptions(maxBytes, refreshInterval, minRefreshInterval, maxStale, algorithm);
    }

    int maxBytes() { return maxBytes; }

    Duration refreshInterval() { return refreshInterval; }

    Duration minRefreshInterval() { return minRefreshInterval; }

    Duration maxStale() { return maxStale; }

    JwsAlgorithm defaultAlgorithm() { return defaultAlgorithm; }

    /** Rejects combinations that contradict each other; called when the options are applied. */
    void validate() {
        if (minRefreshInterval.compareTo(refreshInterval) > 0) {
            throw new IllegalArgumentException("The minimum refresh interval exceeds the refresh interval");
        }
        if (maxStale.compareTo(refreshInterval) < 0) {
            throw new IllegalArgumentException("The maximum staleness is shorter than the refresh interval");
        }
    }

    private static Duration seconds(Duration value, long min, long max, String name) {
        Objects.requireNonNull(value, name);
        if (value.getNano() != 0 || value.getSeconds() < min || value.getSeconds() > max) {
            throw new IllegalArgumentException("The " + name + " is " + min + " to " + max + " whole seconds");
        }
        return value;
    }
}
