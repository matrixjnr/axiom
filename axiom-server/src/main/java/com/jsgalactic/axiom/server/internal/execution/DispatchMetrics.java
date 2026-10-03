package com.jsgalactic.axiom.server.internal.execution;

import com.jsgalactic.axiom.observability.Metrics;
import com.jsgalactic.axiom.routing.Route;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReferenceArray;

/**
 * The dispatcher's measurements. Series are tagged only by the endpoint's method and route
 * template, the status class and a fixed rejection reason, never by anything a request carries.
 * Instruments are looked up once per series and kept, so recording allocates nothing. A failing
 * {@link Metrics} implementation never fails a request: the first failure is logged and the
 * measurement dropped.
 */
final class DispatchMetrics {
    static final String REQUESTS = "axiom.http.requests";
    static final String DURATION = "axiom.http.request.duration";
    static final String REJECTED = "axiom.admission.rejected";
    static final String QUEUE_WAIT = "axiom.admission.queue.wait";
    static final String ACTIVE = "axiom.admission.active";
    static final String QUEUED = "axiom.admission.queued";

    /** Most endpoints that get their own series; later ones share one "other" series. */
    static final int MAX_ENDPOINTS = 1024;
    /** Outcome code of a request that was cancelled and has no status. */
    static final int CANCELLED = -2;
    /** Outcome code of a request whose status the submitter did not supply. */
    static final int UNKNOWN = -1;

    /** Why a request was refused or expired by admission. */
    enum Reason {
        CAPACITY("capacity"), SHUTDOWN("shutdown"), EXECUTOR("executor"), QUEUE_TIMEOUT("queue_timeout");
        private final String tag;
        Reason(String tag) { this.tag = tag; }
    }

    private static final System.Logger LOG = System.getLogger(DispatchMetrics.class.getName());
    private static final String[] CLASSES = {"1xx", "2xx", "3xx", "4xx", "5xx", "cancelled", "unknown"};
    private static final Reason[] REASONS = Reason.values();

    static final DispatchMetrics DISABLED = new DispatchMetrics(Metrics.NOOP);

    private final Metrics metrics;
    private final boolean enabled;
    private final ConcurrentHashMap<Object, Series> endpoints = new ConcurrentHashMap<>();
    private final Series overflow;
    private final AtomicBoolean reported = new AtomicBoolean();
    private volatile Metrics.Gauge active;
    private volatile Metrics.Gauge queued;

    DispatchMetrics(Metrics metrics) {
        this.metrics = metrics;
        this.enabled = metrics != Metrics.NOOP;
        this.overflow = new Series("other", "other");
    }

    boolean enabled() { return enabled; }

    /** The series of an endpoint key, or null when metrics are disabled. */
    Series series(Object key) {
        if (!enabled) { return null; }
        var series = endpoints.get(key);
        if (series != null) { return series; }
        if (endpoints.size() >= MAX_ENDPOINTS) { return overflow; }
        return endpoints.computeIfAbsent(key, DispatchMetrics::describe);
    }

    private static Series describe(Object key) {
        return key instanceof Route route ? new Series(route.method(), route.path()) : new Series("none", "unmatched");
    }

    /** Counts a finished request by status class and records its latency. */
    void request(Series series, int code, long nanos) {
        if (series == null) { return; }
        try {
            countRequest(series, slot(code));
            var timer = series.duration;
            if (timer == null) {
                timer = metrics.timer(DURATION, "method", series.method, "route", series.route);
                series.duration = timer;
            }
            timer.record(nanos);
        } catch (RuntimeException failure) { dropped(failure); }
    }

    /** Counts a request refused before it was accepted: a rejection and a 503 outcome, without latency. */
    void refused(Series series, Reason reason) {
        if (series == null) { return; }
        rejected(series, reason);
        try { countRequest(series, slot(503)); }
        catch (RuntimeException failure) { dropped(failure); }
    }

    private void countRequest(Series series, int slot) {
        var counter = series.requests.get(slot);
        if (counter == null) {
            counter = metrics.counter(REQUESTS, "method", series.method, "route", series.route,
                    "status_class", CLASSES[slot]);
            series.requests.set(slot, counter);
        }
        counter.increment();
    }

    void rejected(Series series, Reason reason) {
        if (series == null) { return; }
        try {
            var counter = series.rejections.get(reason.ordinal());
            if (counter == null) {
                counter = metrics.counter(REJECTED, "method", series.method, "route", series.route,
                        "reason", reason.tag);
                series.rejections.set(reason.ordinal(), counter);
            }
            counter.increment();
        } catch (RuntimeException failure) { dropped(failure); }
    }

    void queueWait(Series series, long nanos) {
        if (series == null) { return; }
        try {
            var timer = series.queueWait;
            if (timer == null) {
                timer = metrics.timer(QUEUE_WAIT, "method", series.method, "route", series.route);
                series.queueWait = timer;
            }
            timer.record(nanos);
        } catch (RuntimeException failure) { dropped(failure); }
    }

    void active(int delta) {
        if (!enabled) { return; }
        try {
            var gauge = active;
            if (gauge == null) { gauge = metrics.gauge(ACTIVE); active = gauge; }
            gauge.add(delta);
        } catch (RuntimeException failure) { dropped(failure); }
    }

    void queued(int delta) {
        if (!enabled) { return; }
        try {
            var gauge = queued;
            if (gauge == null) { gauge = metrics.gauge(QUEUED); queued = gauge; }
            gauge.add(delta);
        } catch (RuntimeException failure) { dropped(failure); }
    }

    private static int slot(int code) {
        if (code == CANCELLED) { return 5; }
        return code >= 100 && code < 600 ? code / 100 - 1 : 6;
    }

    private void dropped(RuntimeException failure) {
        if (reported.compareAndSet(false, true)) {
            LOG.log(System.Logger.Level.WARNING,
                    "The metrics implementation failed; this and later failing measurements are dropped", failure);
        }
    }

    /** The cached instruments of one endpoint. Created lazily; a benign race creates the same series twice. */
    static final class Series {
        private final String method;
        private final String route;
        private final AtomicReferenceArray<Metrics.Counter> requests = new AtomicReferenceArray<>(CLASSES.length);
        private final AtomicReferenceArray<Metrics.Counter> rejections = new AtomicReferenceArray<>(REASONS.length);
        private volatile Metrics.Timer duration;
        private volatile Metrics.Timer queueWait;

        private Series(String method, String route) {
            this.method = method;
            this.route = route;
        }
    }
}
