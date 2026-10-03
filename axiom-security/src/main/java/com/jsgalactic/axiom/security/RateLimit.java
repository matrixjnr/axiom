package com.jsgalactic.axiom.security;

import com.jsgalactic.axiom.context.Context;
import com.jsgalactic.axiom.context.Middleware;
import com.jsgalactic.axiom.error.TooManyRequestsException;
import com.jsgalactic.axiom.http.Response;
import com.jsgalactic.axiom.observability.Metrics;
import java.time.Clock;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Objects;
import java.util.concurrent.locks.ReentrantLock;
import java.util.regex.Pattern;

/**
 * Rate limiting middleware: at most a number of requests per window for each key, with memory that
 * stays bounded however many distinct keys arrive.
 *
 * <pre>{@code
 * // 100 requests per minute and client address, global
 * app.use(RateLimit.builder(100, Duration.ofMinutes(1)).key(RateLimitKey.clientAddress(proxies)).build());
 *
 * // a tighter budget for one group, keyed by user once authenticated
 * var login = RateLimit.builder(5, Duration.ofMinutes(1)).name("login").build();
 * app.group("/login", group -> group.post("/", handler, login));
 * }</pre>
 *
 * <p><b>Algorithms.</b> {@link Algorithm#TOKEN_BUCKET} (the default) lets a key burst up to the
 * bucket capacity ({@link Builder#burst}, by default the whole limit) and refills continuously at
 * {@code requests / window}. {@link Algorithm#SLIDING_WINDOW} counts requests in the current and
 * the previous window and weights the previous one by how much of it still overlaps the last
 * window length, which smooths the doubled burst a fixed window allows at its boundary. Both keep
 * constant state per key.
 *
 * <p><b>Rejection.</b> A request over its budget fails with {@link TooManyRequestsException}: 429
 * {@code application/problem+json}, code {@code rate_limited}, and {@code Retry-After} in whole
 * seconds (rounded up, so waiting that long is enough). The request does not reach the rest of the
 * chain. Allowed responses carry the {@code RateLimit-Limit}, {@code RateLimit-Remaining},
 * {@code RateLimit-Reset} (seconds) and {@code RateLimit-Policy} headers of the IETF rate limit
 * headers draft when {@link Builder#headers(boolean)} is enabled; nested limiters keep the headers
 * of the most constraining one. Like every middleware, it cannot add headers to the response built
 * from the exception.
 *
 * <p><b>Bounded memory.</b> At most {@link Builder#maxKeys} keys are tracked (10,000 by default).
 * When a new key arrives at capacity, the least recently used key of its shard is forgotten, so a
 * flood of random keys cannot grow memory. The trade-off is inherent: an evicted key starts with
 * a full budget, so size {@code maxKeys} well above the number of keys that are legitimately active
 * within one window. A key function that an attacker can vary freely defeats any limiter; see
 * {@link RateLimitKey}.
 *
 * <p><b>Time.</b> All arithmetic uses the injected {@link Clock}. A clock that jumps backwards never
 * leaves a key blocked for longer than one window (stale future state is discarded), and one that
 * jumps forwards simply refills the budget.
 *
 * <p><b>Metrics.</b> With {@link Builder#metrics}: counter {@code axiom.security.ratelimit.requests}
 * (tags {@code limiter}, {@code outcome} = {@code allowed} or {@code limited}), counter
 * {@code axiom.security.ratelimit.evictions} and gauge {@code axiom.security.ratelimit.keys} (tag
 * {@code limiter}). The limiter name is configured by the application; keys are never tags.
 *
 * <p><b>Composition.</b> Register the instance globally, on a group or on a route; each instance
 * has its own state and budget, and several may guard one route (all must allow).
 *
 * <p>Thread-safe; owned by the application it is registered with, and holds no resources to close.
 */
public final class RateLimit implements Middleware {
    /** Longest key tracked; longer keys are counted under {@link RateLimitKey#ANONYMOUS}. */
    public static final int MAX_KEY_LENGTH = 512;

    private static final Pattern NAME = Pattern.compile("[a-z][a-z0-9_]{0,31}");
    private static final int SHARDS = 16;
    private static final long NANOS = 1_000_000_000L;

    /** The counting algorithm. */
    public enum Algorithm {
        /** Capacity that refills continuously; allows bursts up to the capacity. */
        TOKEN_BUCKET,
        /** Weighted count over the current and previous window; smooths boundary bursts. */
        SLIDING_WINDOW
    }

    /**
     * The outcome of counting one request.
     *
     * @param allowed whether the request fits the budget
     * @param limit requests allowed per window
     * @param remaining requests still allowed now, after this one when allowed
     * @param retryAfter how long to wait before a request would be allowed; zero when allowed
     * @param reset time until the budget is fully restored (bucket) or the window turns (sliding)
     */
    public record Decision(boolean allowed, long limit, long remaining, Duration retryAfter, Duration reset) { }

    private static final class Entry {
        long tat;
        long windowStart;
        long previous;
        long current;
    }

    private static final class Shard {
        final ReentrantLock lock = new ReentrantLock();
        final int capacity;
        final LinkedHashMap<String, Entry> entries = new LinkedHashMap<>(16, 0.75f, true);

        Shard(int capacity) { this.capacity = capacity; }
    }

    private final long limit;
    private final long windowNanos;
    private final Algorithm algorithm;
    private final long emissionNanos;
    private final long toleranceNanos;
    private final RateLimitKey keyFunction;
    private final Clock clock;
    private final boolean headers;
    private final String policyHeader;
    private final Shard[] shards;
    private final Metrics.Counter allowedCounter;
    private final Metrics.Counter limitedCounter;
    private final Metrics.Counter evictionCounter;
    private final Metrics.Gauge keyGauge;

    private RateLimit(Builder builder) {
        limit = builder.requests;
        windowNanos = builder.window.toNanos();
        algorithm = builder.algorithm;
        emissionNanos = Math.max(1, windowNanos / limit);
        toleranceNanos = Math.multiplyExact(builder.burst - 1, emissionNanos);
        keyFunction = builder.key;
        clock = builder.clock;
        headers = builder.headers;
        policyHeader = limit + ";w=" + Math.max(1, builder.window.toSeconds());
        int count = builder.maxKeys >= 256 ? SHARDS : 1;
        shards = new Shard[count];
        for (int i = 0; i < count; i++) { shards[i] = new Shard(builder.maxKeys / count + (i < builder.maxKeys % count ? 1 : 0)); }
        var metrics = builder.metrics;
        allowedCounter = metrics.counter("axiom.security.ratelimit.requests", "limiter", builder.name, "outcome", "allowed");
        limitedCounter = metrics.counter("axiom.security.ratelimit.requests", "limiter", builder.name, "outcome", "limited");
        evictionCounter = metrics.counter("axiom.security.ratelimit.evictions", "limiter", builder.name);
        keyGauge = metrics.gauge("axiom.security.ratelimit.keys", "limiter", builder.name);
    }

    /**
     * Starts a limiter allowing a number of requests per window for each key.
     *
     * @param requests requests per window, 1 to 1,000,000,000
     * @param window the window length, 1 millisecond to 1 hour
     * @return a builder with a token bucket, the transport peer address as key and 10,000 keys
     * @throws IllegalArgumentException if a value is out of range
     */
    public static Builder builder(long requests, Duration window) {
        return new Builder(requests, window);
    }

    /** Builds {@link RateLimit}s; not thread-safe, used during configuration. */
    public static final class Builder {
        private final long requests;
        private final Duration window;
        private long burst;
        private Algorithm algorithm = Algorithm.TOKEN_BUCKET;
        private RateLimitKey key = RateLimitKey.peerAddress();
        private int maxKeys = 10_000;
        private Clock clock = Clock.systemUTC();
        private Metrics metrics = Metrics.NOOP;
        private String name = "default";
        private boolean headers;

        private Builder(long requests, Duration window) {
            if (requests < 1 || requests > 1_000_000_000L) {
                throw new IllegalArgumentException("requests must be between 1 and 1,000,000,000");
            }
            Objects.requireNonNull(window, "window");
            if (window.compareTo(Duration.ofMillis(1)) < 0 || window.compareTo(Duration.ofHours(1)) > 0) {
                throw new IllegalArgumentException("window must be between 1 millisecond and 1 hour");
            }
            if (requests > window.toNanos()) { throw new IllegalArgumentException("More than one request per nanosecond"); }
            this.requests = requests;
            this.window = window;
            this.burst = requests;
        }

        /**
         * Selects the algorithm.
         *
         * @param algorithm the algorithm
         * @return this builder
         */
        public Builder algorithm(Algorithm algorithm) {
            this.algorithm = Objects.requireNonNull(algorithm, "algorithm");
            return this;
        }

        /**
         * Sets the token bucket capacity: the most requests a idle key may send at once. By default
         * it equals the limit. Ignored by the sliding window.
         *
         * @param burst capacity, 1 to the limit
         * @return this builder
         */
        public Builder burst(long burst) {
            if (burst < 1 || burst > requests) { throw new IllegalArgumentException("burst must be between 1 and the limit"); }
            this.burst = burst;
            return this;
        }

        /**
         * Sets the key function; the default is {@link RateLimitKey#peerAddress()}.
         *
         * @param key key function
         * @return this builder
         */
        public Builder key(RateLimitKey key) {
            this.key = Objects.requireNonNull(key, "key");
            return this;
        }

        /**
         * Sets the most keys tracked at once (10,000 by default).
         *
         * @param maxKeys 1 to 10,000,000
         * @return this builder
         */
        public Builder maxKeys(int maxKeys) {
            if (maxKeys < 1 || maxKeys > 10_000_000) { throw new IllegalArgumentException("maxKeys must be between 1 and 10,000,000"); }
            this.maxKeys = maxKeys;
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
         * Records the limiter's metrics.
         *
         * @param metrics the metrics sink
         * @return this builder
         */
        public Builder metrics(Metrics metrics) {
            this.metrics = Objects.requireNonNull(metrics, "metrics");
            return this;
        }

        /**
         * Names the limiter in metrics (tag {@code limiter}); default {@code default}.
         *
         * @param name lower-case letters, digits and underscores, starting with a letter, up to 32
         * @return this builder
         */
        public Builder name(String name) {
            if (!NAME.matcher(Objects.requireNonNull(name, "name")).matches()) {
                throw new IllegalArgumentException("Limiter names use [a-z][a-z0-9_]{0,31}");
            }
            this.name = name;
            return this;
        }

        /**
         * Adds the {@code RateLimit-*} headers to allowed responses (off by default).
         *
         * @param enabled whether to add them
         * @return this builder
         */
        public Builder headers(boolean enabled) {
            this.headers = enabled;
            return this;
        }

        /**
         * Builds the limiter.
         *
         * @return a thread-safe limiter
         */
        public RateLimit build() {
            return new RateLimit(this);
        }
    }

    /**
     * Returns how many keys are tracked now, at most {@link Builder#maxKeys}.
     *
     * @return tracked keys
     */
    public int trackedKeys() {
        int total = 0;
        for (var shard : shards) {
            shard.lock.lock();
            try { total += shard.entries.size(); } finally { shard.lock.unlock(); }
        }
        return total;
    }

    /**
     * Counts one request under a key. Public so that non-HTTP code and tests can use the same
     * budgets; keys longer than {@value #MAX_KEY_LENGTH} characters share the anonymous key.
     *
     * @param key the key
     * @return the decision
     */
    public Decision acquire(String key) {
        Objects.requireNonNull(key, "key");
        var name = key.isEmpty() || key.length() > MAX_KEY_LENGTH ? RateLimitKey.ANONYMOUS : key;
        var instant = clock.instant();
        long now = Math.addExact(Math.multiplyExact(instant.getEpochSecond(), NANOS), instant.getNano());
        var shard = shards[shards.length == 1 ? 0 : ((name.hashCode() * 0x9E3779B1) >>> 28) & (SHARDS - 1)];
        shard.lock.lock();
        try {
            var entry = shard.entries.get(name);
            if (entry == null) {
                entry = new Entry();
                entry.tat = now;
                entry.windowStart = now;
                shard.entries.put(name, entry);
                keyGauge.add(1);
                if (shard.entries.size() > shard.capacity) {
                    var eldest = shard.entries.entrySet().iterator();
                    eldest.next();
                    eldest.remove();
                    keyGauge.add(-1);
                    evictionCounter.increment();
                }
            }
            return algorithm == Algorithm.TOKEN_BUCKET ? bucket(entry, now) : sliding(entry, now);
        } finally {
            shard.lock.unlock();
        }
    }

    private Decision bucket(Entry entry, long now) {
        long tat = entry.tat;
        if (tat - now > toleranceNanos + emissionNanos) { tat = now; } // The clock went backwards.
        tat = Math.max(tat, now);
        long next = tat + emissionNanos;
        long allowAt = next - (toleranceNanos + emissionNanos);
        if (now < allowAt) {
            entry.tat = tat;
            return new Decision(false, limit, 0, Duration.ofNanos(allowAt - now), Duration.ofNanos(tat - now));
        }
        entry.tat = next;
        long remaining = Math.max(0, (now + toleranceNanos - tat) / emissionNanos);
        return new Decision(true, limit, remaining, Duration.ZERO, Duration.ofNanos(next - now));
    }

    private Decision sliding(Entry entry, long now) {
        if (now < entry.windowStart) {
            entry.windowStart = now; // The clock went backwards: restart the window, keeping its count.
            entry.previous = 0;
        }
        long elapsed = now - entry.windowStart;
        if (elapsed >= 2 * windowNanos) {
            entry.previous = 0;
            entry.current = 0;
            entry.windowStart = now;
            elapsed = 0;
        } else if (elapsed >= windowNanos) {
            entry.previous = entry.current;
            entry.current = 0;
            entry.windowStart += windowNanos;
            elapsed -= windowNanos;
        }
        double fraction = (double) elapsed / windowNanos;
        double estimate = entry.previous * (1.0 - fraction) + entry.current;
        var reset = Duration.ofNanos(windowNanos - elapsed);
        if (estimate + 1.0 > limit + 1e-9) {
            return new Decision(false, limit, 0, Duration.ofNanos(slidingWait(entry, elapsed)), reset);
        }
        entry.current++;
        long remaining = Math.max(0, (long) Math.floor(limit - (estimate + 1.0) + 1e-9));
        return new Decision(true, limit, remaining, Duration.ZERO, reset);
    }

    /** Nanoseconds until the weighted count drops far enough for one more request. */
    private long slidingWait(Entry entry, long elapsed) {
        long free = limit - entry.current - 1; // Room the previous window may still occupy.
        double wait;
        if (free >= 0 && entry.previous > 0) {
            wait = windowNanos * (1.0 - (double) free / entry.previous) - elapsed;
        } else {
            // The current window alone is full: wait for it to end, then for it to weigh less.
            double next = windowNanos * (1.0 - (double) (limit - 1) / Math.max(1, entry.current));
            wait = windowNanos - elapsed + Math.max(0, next);
        }
        return Math.max(1, (long) Math.ceil(wait));
    }

    @Override
    public Response handle(Context context, Next next) throws Exception {
        var found = keyFunction.key(Objects.requireNonNull(context, "context")).orElse(RateLimitKey.ANONYMOUS);
        var decision = acquire(found);
        if (!decision.allowed()) {
            limitedCounter.increment();
            var wait = decision.retryAfter();
            throw new TooManyRequestsException(wait.compareTo(Duration.ofDays(1)) > 0 ? Duration.ofDays(1) : wait, "rate_limited");
        }
        allowedCounter.increment();
        var response = next.run();
        return headers ? decorate(response, decision) : response;
    }

    private Response decorate(Response response, Decision decision) {
        var existing = response.headers().get("RateLimit-Remaining");
        if (existing != null) {
            try {
                if (Long.parseLong(existing) <= decision.remaining()) { return response; }
            } catch (NumberFormatException ignored) {
                // A malformed value from the handler is replaced.
            }
        }
        long reset = (decision.reset().toNanos() + NANOS - 1) / NANOS;
        return response.withHeader("RateLimit-Limit", Long.toString(decision.limit()))
                .withHeader("RateLimit-Remaining", Long.toString(decision.remaining()))
                .withHeader("RateLimit-Reset", Long.toString(reset))
                .withHeader("RateLimit-Policy", policyHeader);
    }

    @Override
    public String toString() {
        return "RateLimit[" + limit + " per " + Duration.ofNanos(windowNanos) + ", " + algorithm + "]";
    }
}
