package com.jsgalactic.axiom.observability;

/** The metrics that record nothing: one shared instrument serves every kind and series. */
final class NoopMetrics implements Metrics, Metrics.Counter, Metrics.Gauge, Metrics.Timer {
    static final NoopMetrics INSTANCE = new NoopMetrics();

    private NoopMetrics() { }

    @Override public Counter counter(String name, String... tags) { return this; }
    @Override public Gauge gauge(String name, String... tags) { return this; }
    @Override public Timer timer(String name, String... tags) { return this; }
    @Override public void increment() { }
    @Override public void add(long amount) { }
    @Override public void record(long nanos) { }
    @Override public String toString() { return "Metrics.NOOP"; }
}
