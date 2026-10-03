package com.jsgalactic.axiom.http.internal;

import com.jsgalactic.axiom.execution.ExecutionContext;
import com.jsgalactic.axiom.http.BodyWriter;
import com.jsgalactic.axiom.http.StreamAbortedException;
import com.jsgalactic.axiom.http.StreamAbortedException.Reason;
import com.jsgalactic.axiom.server.internal.execution.StreamMetrics;
import io.netty.channel.Channel;
import io.netty.handler.codec.http.DefaultHttpContent;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

/**
 * The body writer of a streamed response, used by the handler's virtual thread while the
 * connection's event loop sends what it enqueues.
 *
 * <p>Backpressure: a write is split into pieces of at most {@link #PIECE} bytes, and before each
 * piece the writer waits until the channel is writable, that is until its outbound buffer is below
 * the low water mark again after exceeding the high water mark. So the bytes queued for a slow
 * client never exceed the high water mark plus one piece, however much the handler writes. A wait
 * ends when the channel becomes writable (the connection signals {@link #signal}), when the request
 * deadline passes, when the client has made no progress for the stall bound, or when the stream is
 * aborted. An interrupted wait aborts the stream too.
 *
 * <p>The first {@linkplain #abort abort} reason wins and is permanent: every later write throws
 * it. {@link #end} marks the writer finished; it then rejects writes as a programming error.
 */
final class ChannelBodyWriter implements BodyWriter {
    /** Largest number of bytes handed to the channel in one chunk. */
    static final int PIECE = 16 * 1024;

    private final Channel channel;
    private final ExecutionContext execution;
    private final long limit;
    private final long stallNanos;
    private final StreamMetrics.Scope metrics;
    private final ReentrantLock lock = new ReentrantLock();
    private final Condition changed = lock.newCondition();
    private final AtomicReference<Reason> aborted = new AtomicReference<>();
    private final List<Runnable> shutdownActions = new ArrayList<>();
    private volatile boolean shutdown;
    private volatile boolean ended;
    private volatile long written;

    ChannelBodyWriter(Channel channel, ExecutionContext execution, long limit, long stallNanos,
            StreamMetrics.Scope metrics) {
        this.channel = channel;
        this.execution = execution;
        this.limit = limit;
        this.stallNanos = stallNanos;
        this.metrics = metrics;
    }

    @Override public void write(byte[] bytes, int offset, int length) throws IOException {
        Objects.requireNonNull(bytes, "bytes");
        Objects.checkFromIndexSize(offset, length, bytes.length);
        if (ended) { throw new IllegalStateException("The stream body has returned; its writer is no longer valid"); }
        check();
        if (length > limit - written) {
            // The cap is on what the handler asks for, so a write that would cross it sends nothing.
            abort(Reason.LIMIT_EXCEEDED);
            throw failure();
        }
        for (int sent = 0; sent < length;) {
            int piece = Math.min(PIECE, length - sent);
            awaitWritable();
            var buffer = channel.alloc().buffer(piece);
            buffer.writeBytes(bytes, offset + sent, piece);
            // A failed write means the connection is gone; the next write or wait reports it.
            channel.writeAndFlush(new DefaultHttpContent(buffer)).addListener(done -> {
                if (!done.isSuccess()) { abort(shutdown ? Reason.SHUTDOWN : Reason.CLIENT_DISCONNECTED); }
            });
            sent += piece;
            written += piece;
        }
    }

    @Override public long bytesWritten() { return written; }

    @Override public boolean shutdownRequested() { return shutdown; }

    @Override public void onShutdown(Runnable action) {
        Objects.requireNonNull(action, "action");
        lock.lock();
        try {
            if (!shutdown) { shutdownActions.add(action); return; }
        } finally {
            lock.unlock();
        }
        run(action);
    }

    /**
     * Tells the body that the listener is closing, once: from now on {@link #shutdownRequested}
     * is true and the registered actions run. Writes keep working until the grace period ends.
     */
    void beginShutdown() {
        List<Runnable> actions;
        lock.lock();
        try {
            if (shutdown) { return; }
            shutdown = true;
            actions = List.copyOf(shutdownActions);
            shutdownActions.clear();
            changed.signalAll();
        } finally {
            lock.unlock();
        }
        actions.forEach(ChannelBodyWriter::run);
    }

    private static void run(Runnable action) {
        try {
            action.run();
        } catch (RuntimeException failure) {
            System.getLogger(ChannelBodyWriter.class.getName())
                    .log(System.Logger.Level.WARNING, "A stream shutdown action failed", failure);
        }
    }

    /** The reason the stream was aborted, or null. */
    Reason aborted() { return aborted.get(); }

    /** Aborts the stream if it is not aborted yet and wakes a writer that is waiting. */
    void abort(Reason reason) {
        if (aborted.compareAndSet(null, reason)) { signal(); }
    }

    /** Wakes a writer waiting for the channel to become writable, after a change of state. */
    void signal() {
        lock.lock();
        try { changed.signalAll(); } finally { lock.unlock(); }
    }

    /** Marks the stream body as returned. */
    void end() { ended = true; }

    private void check() throws StreamAbortedException {
        if (aborted.get() == null) {
            if (!channel.isActive()) { abort(shutdown ? Reason.SHUTDOWN : Reason.CLIENT_DISCONNECTED); }
            else if (execution.isExpired()) { abort(Reason.TIMEOUT); }
        }
        if (aborted.get() != null) { throw failure(); }
    }

    private StreamAbortedException failure() { return new StreamAbortedException(aborted.get()); }

    private void awaitWritable() throws StreamAbortedException {
        if (channel.isWritable()) { return; }
        metrics.blocked();
        long stalledUntil = System.nanoTime() + stallNanos;
        lock.lock();
        try {
            while (!channel.isWritable()) {
                check();
                long remaining = Math.min(execution.remainingTime().toNanos(), stalledUntil - System.nanoTime());
                if (remaining <= 0) {
                    // Either the deadline passed or the client has taken no data for the whole stall bound.
                    abort(execution.isExpired() ? Reason.TIMEOUT : Reason.CLIENT_DISCONNECTED);
                    throw failure();
                }
                changed.awaitNanos(remaining);
            }
        } catch (InterruptedException interrupted) {
            // The dispatcher interrupts at the deadline and on cancellation.
            Thread.currentThread().interrupt();
            abort(execution.isExpired() ? Reason.TIMEOUT : Reason.SHUTDOWN);
            throw failure();
        } finally {
            lock.unlock();
        }
        check();
    }
}
