package com.jsgalactic.axiom.metrics;

import com.jsgalactic.axiom.context.Handler;
import com.jsgalactic.axiom.http.Response;
import java.util.List;
import java.util.Objects;

/**
 * Renders a {@link MetricsRegistry} in the Prometheus text exposition format, version 0.0.4.
 *
 * <p>Dots in names become underscores. Counters get a {@code _total} suffix, and timers are
 * exposed in seconds as a histogram: cumulative {@code _bucket} series with an {@code le} tag,
 * {@code _sum} and {@code _count}. Every metric with help text (set on the registry, or built in for
 * the runtime's own metrics) gets a {@code # HELP} line, and the registry's own size and dropped
 * series are exposed as {@code axiom_metrics_series} and {@code axiom_metrics_dropped_series_total}. Output order is stable. A timer's {@code _count} and its
 * {@code +Inf} bucket are always equal; its {@code _sum} may lag them by observations recorded
 * while the registry was being read. Tag values are escaped; no value is ever interpreted.
 *
 * <p>The metrics describe the application's routes and load. Serve them on a private network or
 * behind authentication, never publicly; see the observability guide.
 */
public final class PrometheusText {
    /** The media type of the exposition format. */
    public static final String CONTENT_TYPE = "text/plain; version=0.0.4; charset=utf-8";

    private PrometheusText() { }

    /**
     * Renders every series of a registry.
     * @param registry registry to read
     * @return exposition text, empty for an empty registry
     */
    public static String render(MetricsRegistry registry) {
        Objects.requireNonNull(registry, "registry");
        var out = new StringBuilder(1024);
        String family = null;
        for (var series : registry.sortedSeries()) {
            var base = series.name().replace('.', '_');
            switch (series.kind()) {
                case COUNTER -> {
                    var name = base + "_total";
                    family = type(out, family, name, "counter", registry, series.name());
                    sample(out, name, series.tags(), null, ((MetricsRegistry.CounterSeries) series).value.sum());
                }
                case GAUGE -> {
                    family = type(out, family, base, "gauge", registry, series.name());
                    sample(out, base, series.tags(), null, ((MetricsRegistry.GaugeSeries) series).value.sum());
                }
                case TIMER -> {
                    var name = base + "_seconds";
                    family = type(out, family, name, "histogram", registry, series.name());
                    var timer = (MetricsRegistry.TimerSeries) series;
                    long cumulative = 0;
                    for (int i = 0; i < timer.buckets.length; i++) {
                        cumulative += timer.buckets[i].sum();
                        var bound = i < timer.bounds.length ? bound(timer.bounds[i]) : "+Inf";
                        sample(out, name + "_bucket", series.tags(), bound, cumulative);
                    }
                    sampleSeconds(out, name + "_sum", series.tags(), timer.totalNanos.sum());
                    sample(out, name + "_count", series.tags(), null, cumulative);
                }
            }
        }
        out.append("# HELP axiom_metrics_series Distinct series the registry stores.\n")
                .append("# TYPE axiom_metrics_series gauge\n")
                .append("axiom_metrics_series ").append(registry.seriesCount()).append('\n')
                .append("# HELP axiom_metrics_dropped_series_total Series refused because the registry was full.\n")
                .append("# TYPE axiom_metrics_dropped_series_total counter\n")
                .append("axiom_metrics_dropped_series_total ").append(registry.droppedSeries()).append('\n');
        return out.toString();
    }

    /**
     * Returns a handler that answers with the registry's current exposition, for example
     * {@code app.get("/metrics", PrometheusText.handler(registry), security.hasRole("ops"))}.
     * @param registry registry to read on every request
     * @return a handler answering 200 with {@link #CONTENT_TYPE}
     */
    public static Handler handler(MetricsRegistry registry) {
        Objects.requireNonNull(registry, "registry");
        return context -> Response.of(200, render(registry))
                .withHeader("Content-Type", CONTENT_TYPE).withHeader("Cache-Control", "no-store");
    }

    private static String type(StringBuilder out, String current, String name, String type,
            MetricsRegistry registry, String metric) {
        if (!name.equals(current)) {
            var text = registry.help(metric).orElseGet(() -> RuntimeHelp.of(metric));
            if (text != null) {
                out.append("# HELP ").append(name).append(' ');
                for (int i = 0; i < text.length(); i++) {
                    char c = text.charAt(i);
                    if (c == '\\') { out.append("\\\\"); } else { out.append(c); }
                }
                out.append('\n');
            }
            out.append("# TYPE ").append(name).append(' ').append(type).append('\n');
        }
        return name;
    }

    private static void sample(StringBuilder out, String name, List<String> tags, String le, long value) {
        labels(out.append(name), tags, le).append(' ').append(value).append('\n');
    }

    private static void sampleSeconds(StringBuilder out, String name, List<String> tags, long nanos) {
        labels(out.append(name), tags, null).append(' ').append(nanos / 1_000_000_000L).append('.')
                .append(String.format("%09d", nanos % 1_000_000_000L)).append('\n');
    }

    private static StringBuilder labels(StringBuilder out, List<String> tags, String le) {
        if (tags.isEmpty() && le == null) { return out; }
        out.append('{');
        for (int i = 0; i < tags.size(); i += 2) {
            if (i > 0) { out.append(','); }
            out.append(tags.get(i)).append("=\"");
            escape(out, tags.get(i + 1));
            out.append('"');
        }
        if (le != null) { out.append(tags.isEmpty() ? "" : ",").append("le=\"").append(le).append('"'); }
        return out.append('}');
    }

    private static void escape(StringBuilder out, String value) {
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '\\' -> out.append("\\\\");
                case '"' -> out.append("\\\"");
                case '\n' -> out.append("\\n");
                default -> out.append(c);
            }
        }
    }

    private static String bound(long nanos) {
        return java.math.BigDecimal.valueOf(nanos, 9).stripTrailingZeros().toPlainString();
    }
}
