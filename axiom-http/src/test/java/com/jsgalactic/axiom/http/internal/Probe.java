package com.jsgalactic.axiom.http.internal;

import com.jsgalactic.axiom.observability.Metrics;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.LongPredicate;

/**
 * A {@link Metrics} test double that lets a test wait for a measurement instead of sleeping: every
 * update wakes the waiters, which re-check their condition. Waiting is bounded, so a measurement
 * that never arrives fails the test instead of hanging it.
 */
final class Probe implements Metrics {
    private final ReentrantLock lock = new ReentrantLock();
    private final Condition changed = lock.newCondition();
    private final Map<String, Long> values = new ConcurrentHashMap<>();
    private final Set<String> tagKeys = new TreeSet<>();
    private final Set<String> tagValues = new TreeSet<>();

    static String key(String name, String... tags) {
        var key = new StringBuilder(name).append('{');
        for (int i = 0; i < tags.length; i += 2) {
            if (i > 0) { key.append(','); }
            key.append(tags[i]).append('=').append(tags[i + 1]);
        }
        return key.append('}').toString();
    }

    long value(String name, String... tags) { return values.getOrDefault(key(name, tags), 0L); }

    /** Every tag key ("name:key") and tag value ("name:key=value") requested so far, for any metric. */
    synchronized Set<String> tagKeys() { return Set.copyOf(tagKeys); }
    synchronized Set<String> tagValues() { return Set.copyOf(tagValues); }

    /** Waits until the series satisfies the condition. */
    void await(LongPredicate condition, String name, String... tags) throws Exception {
        var key = key(name, tags);
        long remaining = TimeUnit.SECONDS.toNanos(30);
        lock.lock();
        try {
            while (!condition.test(values.getOrDefault(key, 0L))) {
                if (remaining <= 0) { throw new TimeoutException("Measurement not reached: " + key + " = " + values.get(key)); }
                remaining = changed.awaitNanos(remaining);
            }
        } finally { lock.unlock(); }
    }

    private void update(String key, long delta) {
        lock.lock();
        try {
            values.merge(key, delta, Long::sum);
            changed.signalAll();
        } finally { lock.unlock(); }
    }

    private synchronized void record(String name, String... tags) {
        for (int i = 0; i < tags.length; i += 2) {
            tagKeys.add(name + ":" + tags[i]);
            tagValues.add(name + ":" + tags[i] + "=" + tags[i + 1]);
        }
    }

    @Override public Counter counter(String name, String... tags) {
        record(name, tags);
        var key = key(name, tags);
        return new Counter() {
            @Override public void increment() { update(key, 1); }
            @Override public void add(long amount) { update(key, amount); }
        };
    }

    @Override public Gauge gauge(String name, String... tags) {
        record(name, tags);
        var key = key(name, tags);
        return delta -> update(key, delta);
    }

    @Override public Timer timer(String name, String... tags) {
        record(name, tags);
        return nanos -> { };
    }
}
