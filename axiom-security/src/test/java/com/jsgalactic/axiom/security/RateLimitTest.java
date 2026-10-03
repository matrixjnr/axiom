package com.jsgalactic.axiom.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import com.jsgalactic.axiom.Axiom;
import com.jsgalactic.axiom.context.SecurityIdentity;
import com.jsgalactic.axiom.http.Request;
import com.jsgalactic.axiom.observability.Metrics;
import com.jsgalactic.axiom.test.TestClient;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

/** Token bucket, sliding window, bounded memory and HTTP behaviour of the rate limiter. */
class RateLimitTest {
    /** A clock the test moves by hand. */
    static final class ManualClock extends Clock {
        private final AtomicLong nanos = new AtomicLong(1_000_000_000_000L);

        void advance(Duration by) { nanos.addAndGet(by.toNanos()); }

        @Override public ZoneOffset getZone() { return ZoneOffset.UTC; }

        @Override public Clock withZone(java.time.ZoneId zone) { return this; }

        @Override public Instant instant() { return Instant.ofEpochSecond(0, nanos.get()); }
    }

    /** Counts what a limiter records. */
    static final class RecordingMetrics implements Metrics {
        final Map<String, AtomicLong> values = new ConcurrentHashMap<>();

        private AtomicLong value(String name, String... tags) {
            return values.computeIfAbsent(name + String.join(",", tags), k -> new AtomicLong());
        }

        @Override public Counter counter(String name, String... tags) {
            var v = value(name, tags);
            return new Counter() {
                @Override public void increment() { v.incrementAndGet(); }

                @Override public void add(long amount) { v.addAndGet(amount); }
            };
        }

        @Override public Gauge gauge(String name, String... tags) {
            var v = value(name, tags);
            return v::addAndGet;
        }

        @Override public Timer timer(String name, String... tags) { return nanos -> { }; }

        long get(String name, String... tags) { return value(name, tags).get(); }
    }

    private final ManualClock clock = new ManualClock();

    private RateLimit.Builder bucket(long requests, Duration window) {
        return RateLimit.builder(requests, window).clock(clock);
    }

    @Test
    void bucketAllowsABurstThenRefillsContinuously() {
        var limit = bucket(4, Duration.ofSeconds(4)).build();
        for (int i = 0; i < 4; i++) { assertThat(limit.acquire("a").allowed()).isTrue(); }
        var denied = limit.acquire("a");
        assertThat(denied.allowed()).isFalse();
        assertThat(denied.retryAfter()).isEqualTo(Duration.ofSeconds(1));
        clock.advance(Duration.ofMillis(999));
        assertThat(limit.acquire("a").allowed()).isFalse();
        clock.advance(Duration.ofMillis(1));
        assertThat(limit.acquire("a").allowed()).isTrue();
        assertThat(limit.acquire("a").allowed()).isFalse();
        assertThat(limit.acquire("b").allowed()).isTrue(); // Keys have separate budgets.
    }

    @Test
    void burstCapsTheBucketBelowTheLimit() {
        var limit = bucket(60, Duration.ofSeconds(60)).burst(2).build();
        assertThat(limit.acquire("a").remaining()).isEqualTo(1);
        assertThat(limit.acquire("a").allowed()).isTrue();
        assertThat(limit.acquire("a").allowed()).isFalse();
        clock.advance(Duration.ofHours(1)); // Idle time never banks more than the burst.
        assertThat(limit.acquire("a").allowed()).isTrue();
        assertThat(limit.acquire("a").allowed()).isTrue();
        assertThat(limit.acquire("a").allowed()).isFalse();
    }

    @Test
    void clockJumpingBackwardsDoesNotBlockAKeyForLong() {
        var limit = bucket(2, Duration.ofSeconds(2)).build();
        assertThat(limit.acquire("a").allowed()).isTrue();
        assertThat(limit.acquire("a").allowed()).isTrue();
        clock.advance(Duration.ofHours(-5));
        var after = limit.acquire("a");
        assertThat(after.allowed()).isTrue();
        assertThat(limit.acquire("a").allowed()).isTrue();
        var denied = limit.acquire("a");
        assertThat(denied.allowed()).isFalse();
        assertThat(denied.retryAfter()).isLessThanOrEqualTo(Duration.ofSeconds(2));
    }

    @Test
    void clockJumpingForwardsRefillsAndLeavesNoOverflow() {
        var limit = bucket(2, Duration.ofSeconds(2)).build();
        limit.acquire("a");
        limit.acquire("a");
        clock.advance(Duration.ofDays(3650));
        assertThat(limit.acquire("a").remaining()).isEqualTo(1);
    }

    @Test
    void slidingWindowWeightsThePreviousWindow() {
        var limit = bucket(10, Duration.ofSeconds(10)).algorithm(RateLimit.Algorithm.SLIDING_WINDOW).build();
        for (int i = 0; i < 10; i++) { assertThat(limit.acquire("a").allowed()).isTrue(); }
        var denied = limit.acquire("a");
        assertThat(denied.allowed()).isFalse();
        assertThat(denied.retryAfter()).isGreaterThan(Duration.ofSeconds(10));
        // Right after the boundary the previous window still weighs fully: a fixed window would allow 10 more.
        clock.advance(Duration.ofSeconds(10));
        assertThat(limit.acquire("a").allowed()).isFalse();
        clock.advance(Duration.ofMillis(1500)); // 85% of the previous window remains: room for 1.5 requests.
        assertThat(limit.acquire("a").allowed()).isTrue();
        assertThat(limit.acquire("a").allowed()).isFalse();
    }

    @Test
    void slidingWindowRetryAfterIsSufficient() {
        var limit = bucket(4, Duration.ofSeconds(8)).algorithm(RateLimit.Algorithm.SLIDING_WINDOW).build();
        for (int i = 0; i < 4; i++) { limit.acquire("a"); }
        clock.advance(Duration.ofSeconds(8));
        limit.acquire("a"); // previous window 4, current 1 would exceed: denied or allowed per weights.
        var denied = limit.acquire("a");
        if (!denied.allowed()) {
            clock.advance(denied.retryAfter());
            assertThat(limit.acquire("a").allowed()).isTrue();
        }
    }

    @Test
    void slidingWindowSurvivesIdleGapsAndBackwardsClocks() {
        var limit = bucket(2, Duration.ofSeconds(2)).algorithm(RateLimit.Algorithm.SLIDING_WINDOW).build();
        limit.acquire("a");
        limit.acquire("a");
        clock.advance(Duration.ofSeconds(60));
        assertThat(limit.acquire("a").allowed()).isTrue();
        clock.advance(Duration.ofSeconds(-30));
        assertThat(limit.acquire("a").allowed()).isTrue();
        assertThat(limit.acquire("a").allowed()).isFalse();
    }

    @Test
    void keyFloodingNeverGrowsMemoryPastTheBound() {
        var metrics = new RecordingMetrics();
        var limit = bucket(5, Duration.ofMinutes(1)).maxKeys(300).metrics(metrics).name("flood").build();
        for (int i = 0; i < 50_000; i++) { limit.acquire("k" + i); }
        assertThat(limit.trackedKeys()).isLessThanOrEqualTo(300);
        assertThat(metrics.get("axiom.security.ratelimit.keys", "limiter", "flood")).isEqualTo(limit.trackedKeys());
        assertThat(metrics.get("axiom.security.ratelimit.evictions", "limiter", "flood")).isEqualTo(50_000 - limit.trackedKeys());
    }

    @Test
    void smallLimitersUseOneShardAndEvictLeastRecentlyUsed() {
        var limit = bucket(1, Duration.ofMinutes(1)).maxKeys(2).build();
        limit.acquire("a");
        limit.acquire("b");
        limit.acquire("a"); // Touch a: b is now the least recently used.
        limit.acquire("c");
        assertThat(limit.trackedKeys()).isEqualTo(2);
        assertThat(limit.acquire("a").allowed()).isFalse(); // Still tracked and exhausted.
        assertThat(limit.acquire("b").allowed()).isTrue(); // Forgotten, so fresh.
    }

    @Test
    void oversizedAndEmptyKeysShareTheAnonymousBudget() {
        var limit = bucket(1, Duration.ofMinutes(1)).build();
        assertThat(limit.acquire("x".repeat(RateLimit.MAX_KEY_LENGTH + 1)).allowed()).isTrue();
        assertThat(limit.acquire("").allowed()).isFalse();
        assertThat(limit.acquire(RateLimitKey.ANONYMOUS).allowed()).isFalse();
        assertThat(limit.trackedKeys()).isEqualTo(1);
    }

    @Test
    void concurrentRequestsNeverExceedTheBudget() throws Exception {
        var limit = bucket(50, Duration.ofMinutes(1)).build();
        int threads = 8;
        var start = new CountDownLatch(1);
        var allowed = new AtomicInteger();
        try (var pool = Executors.newFixedThreadPool(threads)) {
            var futures = new java.util.ArrayList<java.util.concurrent.Future<?>>();
            for (int t = 0; t < threads; t++) {
                futures.add(pool.submit(() -> {
                    start.await();
                    for (int i = 0; i < 100; i++) { if (limit.acquire("shared").allowed()) { allowed.incrementAndGet(); } }
                    return null;
                }));
            }
            start.countDown();
            for (var future : futures) { future.get(); }
        }
        assertThat(allowed.get()).isEqualTo(50);
    }

    @Test
    void configurationIsValidated() {
        assertThatIllegalArgumentException().isThrownBy(() -> RateLimit.builder(0, Duration.ofSeconds(1)));
        assertThatIllegalArgumentException().isThrownBy(() -> RateLimit.builder(1, Duration.ofHours(2)));
        assertThatIllegalArgumentException().isThrownBy(() -> RateLimit.builder(1, Duration.ZERO));
        assertThatIllegalArgumentException().isThrownBy(() -> RateLimit.builder(5, Duration.ofSeconds(1)).burst(6));
        assertThatIllegalArgumentException().isThrownBy(() -> RateLimit.builder(5, Duration.ofSeconds(1)).maxKeys(0));
        assertThatIllegalArgumentException().isThrownBy(() -> RateLimit.builder(5, Duration.ofSeconds(1)).name("Bad Name"));
    }

    private static Request from(String path, String address, String... headers) throws Exception {
        var map = new java.util.HashMap<String, String>();
        for (int i = 0; i < headers.length; i += 2) { map.put(headers[i], headers[i + 1]); }
        return Request.fromTarget("GET", path).withHeaders(map)
                .withRemoteAddress(new InetSocketAddress(InetAddress.getByName(address), 5000));
    }

    @Test
    void answersWithProblemJsonAndRetryAfterAndAddsHeaders() throws Exception {
        var metrics = new RecordingMetrics();
        var app = Axiom.create();
        app.use(bucket(2, Duration.ofSeconds(10)).headers(true).metrics(metrics).name("api").build());
        app.get("/x", ctx -> "ok");
        try (var client = TestClient.start(app)) {
            var first = client.execute(from("/x", "192.0.2.1"));
            assertThat(first.headers()).containsEntry("RateLimit-Limit", "2").containsEntry("RateLimit-Remaining", "1")
                    .containsEntry("RateLimit-Reset", "5").containsEntry("RateLimit-Policy", "2;w=10");
            client.execute(from("/x", "192.0.2.1"));
            var limited = client.execute(from("/x", "192.0.2.1"));
            assertThat(limited.status()).isEqualTo(429);
            assertThat(limited.headers()).containsEntry("Content-Type", "application/problem+json").containsEntry("Retry-After", "5");
            assertThat(new String((byte[]) limited.body(), StandardCharsets.UTF_8)).contains("\"code\":\"rate_limited\"");
            assertThat(client.execute(from("/x", "192.0.2.2")).status()).isEqualTo(200);
        }
        assertThat(metrics.get("axiom.security.ratelimit.requests", "limiter", "api", "outcome", "allowed")).isEqualTo(3);
        assertThat(metrics.get("axiom.security.ratelimit.requests", "limiter", "api", "outcome", "limited")).isEqualTo(1);
    }

    @Test
    void headersAreOffByDefaultAndSpoofedForwardingHeadersAreIgnored() throws Exception {
        var app = Axiom.create();
        app.use(bucket(1, Duration.ofMinutes(1)).build());
        app.get("/x", ctx -> "ok");
        try (var client = TestClient.start(app)) {
            var ok = client.execute(from("/x", "192.0.2.1", "X-Forwarded-For", "198.51.100.1"));
            assertThat(ok.headers()).doesNotContainKey("RateLimit-Limit");
            // A different spoofed address does not buy a new budget: the peer is the key.
            assertThat(client.execute(from("/x", "192.0.2.1", "X-Forwarded-For", "198.51.100.2")).status()).isEqualTo(429);
        }
    }

    @Test
    void trustedProxyAddressAndPrincipalKeys() throws Exception {
        var proxies = TrustedProxies.of("10.0.0.0/8");
        var app = Axiom.create();
        app.use(bucket(1, Duration.ofMinutes(1)).key(RateLimitKey.clientAddress(proxies)).build());
        app.get("/x", ctx -> "ok");
        try (var client = TestClient.start(app)) {
            assertThat(client.execute(from("/x", "10.0.0.1", "X-Forwarded-For", "203.0.113.5")).status()).isEqualTo(200);
            assertThat(client.execute(from("/x", "10.0.0.1", "X-Forwarded-For", "203.0.113.6")).status()).isEqualTo(200);
            assertThat(client.execute(from("/x", "10.0.0.1", "X-Forwarded-For", "203.0.113.5")).status()).isEqualTo(429);
            // From an untrusted peer the header is ignored.
            assertThat(client.execute(from("/x", "192.0.2.9", "X-Forwarded-For", "203.0.113.7")).status()).isEqualTo(200);
            assertThat(client.execute(from("/x", "192.0.2.9", "X-Forwarded-For", "203.0.113.8")).status()).isEqualTo(429);
        }
        var byUser = Axiom.create();
        byUser.use((ctx, next) -> {
            ctx.identity(new SecurityIdentity(ctx.header("X-User").orElse("anon"), Set.of(), Set.of()));
            return next.run();
        });
        byUser.use(bucket(1, Duration.ofMinutes(1)).key(RateLimitKey.principal(RateLimitKey.peerAddress())).build());
        byUser.get("/x", ctx -> "ok");
        try (var client = TestClient.start(byUser)) {
            assertThat(client.execute(from("/x", "192.0.2.1", "X-User", "ada")).status()).isEqualTo(200);
            assertThat(client.execute(from("/x", "192.0.2.1", "X-User", "bob")).status()).isEqualTo(200);
            assertThat(client.execute(from("/x", "192.0.2.2", "X-User", "ada")).status()).isEqualTo(429);
        }
    }

    @Test
    void requestsWithoutAPeerShareTheAnonymousBudgetAndGroupsCompose() throws Exception {
        var app = Axiom.create();
        var strict = bucket(1, Duration.ofMinutes(1)).name("login").build();
        app.use(bucket(100, Duration.ofMinutes(1)).build());
        app.get("/open", ctx -> "ok");
        app.post("/login", ctx -> "ok", strict);
        try (var client = TestClient.start(app)) {
            assertThat(client.get("/open").status()).isEqualTo(200);
            assertThat(client.post("/login", "text/plain", "x").status()).isEqualTo(200);
            assertThat(client.post("/login", "text/plain", "x").status()).isEqualTo(429);
            assertThat(client.get("/open").status()).isEqualTo(200);
        }
    }

    @Test
    void nestedLimitersKeepTheMostConstrainingHeaders() throws Exception {
        var app = Axiom.create();
        app.use(bucket(100, Duration.ofMinutes(1)).headers(true).build());
        app.get("/x", ctx -> "ok", bucket(3, Duration.ofMinutes(1)).headers(true).build());
        try (var client = TestClient.start(app)) {
            assertThat(client.get("/x").headers()).containsEntry("RateLimit-Limit", "3").containsEntry("RateLimit-Remaining", "2");
        }
    }
}
