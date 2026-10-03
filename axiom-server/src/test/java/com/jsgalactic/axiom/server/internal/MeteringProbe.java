package com.jsgalactic.axiom.server.internal;

import com.jsgalactic.axiom.observability.Metrics;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;

/** A {@link Metrics} test double that keeps every series under its name and tags. */
final class MeteringProbe implements Metrics {
    private final ConcurrentHashMap<String, AtomicLong> counters = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, List<Long>> timers = new ConcurrentHashMap<>();

    private static String key(String name, String... tags) {
        var key = new StringBuilder(name).append('{');
        for (int i = 0; i < tags.length; i += 2) {
            if (i > 0) { key.append(','); }
            key.append(tags[i]).append('=').append(tags[i + 1]);
        }
        return key.append('}').toString();
    }

    long count(String name, String... tags) {
        var value = counters.get(key(name, tags));
        return value == null ? 0 : value.get();
    }

    List<Long> observations(String name, String... tags) { return timers.getOrDefault(key(name, tags), List.of()); }

    @Override public Counter counter(String name, String... tags) {
        var value = counters.computeIfAbsent(key(name, tags), ignored -> new AtomicLong());
        return new Counter() {
            @Override public void increment() { value.incrementAndGet(); }
            @Override public void add(long amount) { value.addAndGet(amount); }
        };
    }

    @Override public Gauge gauge(String name, String... tags) { return delta -> { }; }

    @Override public Timer timer(String name, String... tags) {
        var values = timers.computeIfAbsent(key(name, tags), ignored -> new CopyOnWriteArrayList<>());
        return values::add;
    }
}
