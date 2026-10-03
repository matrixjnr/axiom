package com.jsgalactic.axiom.security;

import com.jsgalactic.axiom.observability.Metrics;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.UnaryOperator;

/**
 * A {@link SessionStore} in process memory with idle and absolute timeouts and a hard bound on the
 * number of sessions.
 *
 * <pre>{@code
 * var store = InMemorySessionStore.builder()
 *         .idleTimeout(Duration.ofMinutes(30)).absoluteTimeout(Duration.ofHours(8)).maxSessions(50_000).build();
 * }</pre>
 *
 * <p><b>Bounds.</b> At most {@code maxSessions} (10,000 by default) sessions exist. A new session at
 * capacity first drops expired sessions, then the least recently used <em>anonymous</em> session
 * (no identity), and only when there is none the least recently used authenticated one, so a flood
 * of anonymous sessions cannot push signed-in users out while anonymous sessions remain to evict.
 * An evicted user simply has to sign in again.
 *
 * <p><b>Timeouts.</b> A session expires when it has been idle for {@code idleTimeout} (30 minutes by
 * default) or lives longer than {@code absoluteTimeout} (8 hours), whichever comes first; expired
 * sessions are never returned. A clock that moves backwards never expires a session early.
 *
 * <p><b>Storage.</b> Entries are keyed by the SHA-256 digest of the identifier, so a heap dump of
 * the map keys does not reveal usable identifiers. Sessions do not survive a restart and are not
 * shared between instances; use another {@link SessionStore} for that.
 *
 * <p>Metrics, when configured: gauge {@code axiom.security.sessions}, counter
 * {@code axiom.security.sessions.evictions} (tag {@code reason} = {@code expired} or
 * {@code capacity}). Thread-safe; nothing to close.
 */
public final class InMemorySessionStore implements SessionStore {
    private static final Duration SWEEP_INTERVAL = Duration.ofMinutes(1);

    private final Duration idleTimeout;
    private final Duration absoluteTimeout;
    private final int maxSessions;
    private final Clock clock;
    private final Metrics.Gauge sessionGauge;
    private final Metrics.Counter expiredCounter;
    private final Metrics.Counter capacityCounter;
    private final ReentrantLock lock = new ReentrantLock();
    private final LinkedHashMap<String, SessionState> anonymous = new LinkedHashMap<>(16, 0.75f, true);
    private final LinkedHashMap<String, SessionState> authenticated = new LinkedHashMap<>(16, 0.75f, true);
    private Instant nextSweep = Instant.MIN;

    private InMemorySessionStore(Builder builder) {
        idleTimeout = builder.idleTimeout;
        absoluteTimeout = builder.absoluteTimeout;
        maxSessions = builder.maxSessions;
        clock = builder.clock;
        sessionGauge = builder.metrics.gauge("axiom.security.sessions");
        expiredCounter = builder.metrics.counter("axiom.security.sessions.evictions", "reason", "expired");
        capacityCounter = builder.metrics.counter("axiom.security.sessions.evictions", "reason", "capacity");
    }

    /**
     * Starts a builder with 30 minutes idle, 8 hours absolute and 10,000 sessions.
     *
     * @return a builder
     */
    public static Builder builder() {
        return new Builder();
    }

    /** Builds {@link InMemorySessionStore}s; used during configuration, not thread-safe. */
    public static final class Builder {
        private Duration idleTimeout = Duration.ofMinutes(30);
        private Duration absoluteTimeout = Duration.ofHours(8);
        private int maxSessions = 10_000;
        private Clock clock = Clock.systemUTC();
        private Metrics metrics = Metrics.NOOP;

        private Builder() { }

        /**
         * Sets the idle timeout.
         *
         * @param idleTimeout 1 second to 30 days
         * @return this builder
         */
        public Builder idleTimeout(Duration idleTimeout) {
            this.idleTimeout = range(idleTimeout, "idleTimeout");
            return this;
        }

        /**
         * Sets the absolute timeout, which must not be shorter than the idle timeout.
         *
         * @param absoluteTimeout 1 second to 30 days
         * @return this builder
         */
        public Builder absoluteTimeout(Duration absoluteTimeout) {
            this.absoluteTimeout = range(absoluteTimeout, "absoluteTimeout");
            return this;
        }

        /**
         * Sets the most sessions held at once.
         *
         * @param maxSessions 1 to 10,000,000
         * @return this builder
         */
        public Builder maxSessions(int maxSessions) {
            if (maxSessions < 1 || maxSessions > 10_000_000) { throw new IllegalArgumentException("maxSessions must be between 1 and 10,000,000"); }
            this.maxSessions = maxSessions;
            return this;
        }

        /**
         * Sets the time source, for deterministic tests.
         *
         * @param clock the clock
         * @return this builder
         */
        public Builder clock(Clock clock) {
            this.clock = Objects.requireNonNull(clock, "clock");
            return this;
        }

        /**
         * Records the store's metrics.
         *
         * @param metrics the sink
         * @return this builder
         */
        public Builder metrics(Metrics metrics) {
            this.metrics = Objects.requireNonNull(metrics, "metrics");
            return this;
        }

        /**
         * Builds the store.
         *
         * @return a thread-safe store
         * @throws IllegalArgumentException if the absolute timeout is shorter than the idle timeout
         */
        public InMemorySessionStore build() {
            if (absoluteTimeout.compareTo(idleTimeout) < 0) {
                throw new IllegalArgumentException("absoluteTimeout must not be shorter than idleTimeout");
            }
            return new InMemorySessionStore(this);
        }

        private static Duration range(Duration value, String name) {
            Objects.requireNonNull(value, name);
            if (value.compareTo(Duration.ofSeconds(1)) < 0 || value.compareTo(Duration.ofDays(30)) > 0) {
                throw new IllegalArgumentException(name + " must be between 1 second and 30 days");
            }
            return value;
        }
    }

    /**
     * Returns the number of sessions held, expired ones included until they are noticed.
     *
     * @return the count
     */
    public int size() {
        lock.lock();
        try { return anonymous.size() + authenticated.size(); } finally { lock.unlock(); }
    }

    @Override
    public boolean create(String id, SessionState state) {
        var key = key(id);
        Objects.requireNonNull(state, "state");
        lock.lock();
        try {
            var now = clock.instant();
            if (anonymous.containsKey(key) || authenticated.containsKey(key)) {
                var live = find(key, now);
                if (live != null) { return false; }
            }
            makeRoom(now);
            put(key, state.withTimes(now, now));
            return true;
        } finally {
            lock.unlock();
        }
    }

    @Override
    public Optional<SessionState> find(String id) {
        var key = key(id);
        lock.lock();
        try {
            var now = clock.instant();
            var state = find(key, now);
            if (state == null) { return Optional.empty(); }
            var touched = state.withTimes(state.createdAt(), now);
            mapFor(touched).put(key, touched);
            return Optional.of(touched);
        } finally {
            lock.unlock();
        }
    }

    @Override
    public boolean update(String id, UnaryOperator<SessionState> change) {
        var key = key(id);
        Objects.requireNonNull(change, "change");
        lock.lock();
        try {
            var now = clock.instant();
            var state = find(key, now);
            if (state == null) { return false; }
            var next = Objects.requireNonNull(change.apply(state), "The change returned null");
            var stamped = next.withTimes(state.createdAt(), now);
            drop(key);
            put(key, stamped);
            return true;
        } finally {
            lock.unlock();
        }
    }

    @Override
    public boolean rename(String id, String newId) {
        var key = key(id);
        var newKey = key(newId);
        lock.lock();
        try {
            var now = clock.instant();
            var state = find(key, now);
            if (state == null) { return false; }
            if (!key.equals(newKey) && find(newKey, now) != null) { return false; }
            drop(key);
            put(newKey, state.withTimes(state.createdAt(), now));
            return true;
        } finally {
            lock.unlock();
        }
    }

    @Override
    public void remove(String id) {
        var key = key(id);
        lock.lock();
        try { drop(key); } finally { lock.unlock(); }
    }

    // The methods below run with the lock held.

    private SessionState find(String key, Instant now) {
        var state = anonymous.get(key);
        if (state == null) { state = authenticated.get(key); }
        if (state == null) { return null; }
        if (expired(state, now)) {
            drop(key);
            expiredCounter.increment();
            return null;
        }
        return state;
    }

    private boolean expired(SessionState state, Instant now) {
        return elapsed(state.lastAccessedAt(), now).compareTo(idleTimeout) >= 0
                || elapsed(state.createdAt(), now).compareTo(absoluteTimeout) >= 0;
    }

    private static Duration elapsed(Instant from, Instant to) {
        var elapsed = Duration.between(from, to);
        return elapsed.isNegative() ? Duration.ZERO : elapsed; // A clock that moved back expires nothing.
    }

    private java.util.Map<String, SessionState> mapFor(SessionState state) {
        return state.identity().isPresent() ? authenticated : anonymous;
    }

    private void put(String key, SessionState state) {
        mapFor(state).put(key, state);
        sessionGauge.add(1);
    }

    private void drop(String key) {
        if (anonymous.remove(key) != null || authenticated.remove(key) != null) { sessionGauge.add(-1); }
    }

    private void makeRoom(Instant now) {
        if (anonymous.size() + authenticated.size() < maxSessions) { return; }
        dropExpiredHeads(anonymous, now);
        dropExpiredHeads(authenticated, now);
        if (anonymous.size() + authenticated.size() >= maxSessions && now.isAfter(nextSweep)) {
            nextSweep = now.plus(SWEEP_INTERVAL);
            sweep(anonymous, now);
            sweep(authenticated, now);
        }
        while (anonymous.size() + authenticated.size() >= maxSessions) {
            var victims = anonymous.isEmpty() ? authenticated : anonymous;
            var eldest = victims.keySet().iterator();
            eldest.next();
            eldest.remove();
            sessionGauge.add(-1);
            capacityCounter.increment();
        }
    }

    /** Access order puts idle sessions first, so only a prefix needs checking. */
    private void dropExpiredHeads(LinkedHashMap<String, SessionState> map, Instant now) {
        var iterator = map.values().iterator();
        while (iterator.hasNext() && expired(iterator.next(), now)) {
            iterator.remove();
            sessionGauge.add(-1);
            expiredCounter.increment();
        }
    }

    private void sweep(LinkedHashMap<String, SessionState> map, Instant now) {
        var iterator = map.values().iterator();
        while (iterator.hasNext()) {
            if (expired(iterator.next(), now)) {
                iterator.remove();
                sessionGauge.add(-1);
                expiredCounter.increment();
            }
        }
    }

    private static String key(String id) {
        Objects.requireNonNull(id, "id");
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(id.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is a required JDK algorithm", impossible);
        }
    }

    @Override
    public String toString() {
        return "InMemorySessionStore[max=" + maxSessions + "]";
    }
}
