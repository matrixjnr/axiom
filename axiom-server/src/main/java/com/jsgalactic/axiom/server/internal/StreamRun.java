package com.jsgalactic.axiom.server.internal;

import com.jsgalactic.axiom.execution.ExecutionContext;
import com.jsgalactic.axiom.http.Response;
import com.jsgalactic.axiom.http.StreamAbortedException;
import com.jsgalactic.axiom.http.StreamOutcome;
import com.jsgalactic.axiom.server.internal.execution.StreamMetrics;
import java.time.Duration;
import java.util.function.Consumer;

/**
 * What every transport does around the body of a streamed response, so the listener and the test
 * client agree: give the stream its own lifetime once the head is out, then classify how it ended,
 * count it and tell the observers the response carries. One instance per stream, used on the
 * handler's thread.
 */
public final class StreamRun {
    private static final System.Logger LOG = System.getLogger(StreamRun.class.getName());

    private final ExecutionContext execution;
    private final StreamMetrics.Scope metrics;
    private final Consumer<StreamOutcome> observer;
    private final long startedAt = System.nanoTime();

    private StreamRun(ExecutionContext execution, StreamMetrics.Scope metrics, Consumer<StreamOutcome> observer) {
        this.execution = execution;
        this.metrics = metrics;
        this.observer = observer;
    }

    /**
     * Starts a stream whose head has just been sent: applies the response's own lifetime to the
     * request deadline and counts the stream as active.
     * @param response the streamed response
     * @param execution the request's identity and deadline
     * @param metrics recorder of the endpoint's stream measurements
     * @return the run to {@linkplain #end end} when the body is over
     */
    public static StreamRun begin(Response response, ExecutionContext execution, StreamMetrics.Scope metrics) {
        Duration lifetime = response.streamLifetime();
        if (lifetime != null) { execution.extendDeadline(lifetime); }
        metrics.started();
        return new StreamRun(execution, metrics, response.streamEndObserver());
    }

    /**
     * Records the end of the body and runs the response's observers. Call it exactly once, after
     * the body returned or failed and before the request's outcome is final.
     * @param written body bytes accepted by the writer
     * @param aborted why the writer was aborted, or null
     * @param failure what the body threw, or null
     * @return how the stream ended
     */
    public StreamOutcome end(long written, StreamAbortedException.Reason aborted, Throwable failure) {
        var kind = aborted != null ? kindOf(aborted)
                : failure != null ? (execution.isExpired() ? StreamOutcome.Kind.TIMEOUT : StreamOutcome.Kind.FAILED)
                : StreamOutcome.Kind.COMPLETED;
        var outcome = new StreamOutcome(kind, written, Duration.ofNanos(System.nanoTime() - startedAt), failure);
        metrics.finished(kind, written);
        try {
            observer.accept(outcome);
        } catch (RuntimeException broken) {
            LOG.log(System.Logger.Level.WARNING,
                    "A stream observer of request " + execution.requestId() + " failed", broken);
        }
        return outcome;
    }

    private static StreamOutcome.Kind kindOf(StreamAbortedException.Reason reason) {
        return switch (reason) {
            case CLIENT_DISCONNECTED -> StreamOutcome.Kind.CLIENT_DISCONNECTED;
            case LIMIT_EXCEEDED -> StreamOutcome.Kind.LIMIT_EXCEEDED;
            case TIMEOUT -> StreamOutcome.Kind.TIMEOUT;
            case SHUTDOWN -> StreamOutcome.Kind.SHUTDOWN;
        };
    }
}
