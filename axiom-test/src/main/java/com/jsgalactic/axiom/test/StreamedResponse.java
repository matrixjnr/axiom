package com.jsgalactic.axiom.test;

import com.jsgalactic.axiom.execution.ExecutionContext;
import com.jsgalactic.axiom.http.BodyWriter;
import com.jsgalactic.axiom.http.StreamAbortedException;
import com.jsgalactic.axiom.http.StreamAbortedException.Reason;
import com.jsgalactic.axiom.server.internal.execution.StreamMetrics;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

/**
 * A response whose body is read piece by piece, returned by {@link TestClient#stream}. It plays the
 * client of a streamed response (see {@code Response.stream}) without a network, and makes the
 * timing of the exchange deterministic: the handler's writer and this reader meet at a bounded
 * hand-off.
 *
 * <p><strong>Backpressure.</strong> The handler can be at most one write ahead of the reader. Its
 * next write blocks until {@link #next()} has taken the previous chunk, so a test that does not read
 * holds the handler exactly where a slow client would, with no timing involved, and a test that
 * reads one chunk at a time sees every write as its own chunk, in order. A blocked write is still
 * bounded by the request deadline.
 *
 * <p><strong>Ending.</strong> {@link #next()} returns empty once the body has ended, whether it
 * completed or failed; {@link #completion()} then says which. {@link #close()} plays a client that
 * disconnects: the handler's writes throw {@link StreamAbortedException} with
 * {@link Reason#CLIENT_DISCONNECTED}, it is interrupted if it is blocked elsewhere, and its admission
 * slot is released when it returns. A response that was not streamed is delivered as a single chunk.
 * Thread-safe; closing is idempotent.
 */
public final class StreamedResponse implements AutoCloseable {
    private final int status;
    private final Map<String, String> headers;
    private final ReentrantLock lock = new ReentrantLock();
    private final Condition changed = lock.newCondition();
    private final ArrayDeque<byte[]> chunks = new ArrayDeque<>();
    private final CompletableFuture<Void> completion = new CompletableFuture<>();
    private final CompletableFuture<Runnable> cancel = new CompletableFuture<>();
    private final Writer writer;
    private boolean ended;
    private boolean closed;

    StreamedResponse(int status, Map<String, String> headers, long limit, ExecutionContext execution,
            StreamBuffering buffering, StreamMetrics.Scope metrics) {
        this.status = status;
        this.headers = headers;
        this.writer = new Writer(limit, execution, buffering, metrics);
    }

    /**
     * Returns the status of the response head.
     *
     * @return the status
     */
    public int status() { return status; }

    /**
     * Returns the headers of the response head.
     *
     * @return an immutable, case-insensitive map
     */
    public Map<String, String> headers() { return headers; }

    /**
     * Waits for the next chunk. Each write of the handler is one chunk, and chunks are never joined
     * or split.
     *
     * @return the chunk, or empty once the body has ended
     * @throws InterruptedException if the calling thread is interrupted while waiting
     */
    public Optional<byte[]> next() throws InterruptedException {
        lock.lockInterruptibly();
        try {
            while (chunks.isEmpty() && !ended) { changed.await(); }
            var chunk = chunks.poll();
            if (chunk != null) { writer.taken(chunk.length); }
            changed.signalAll();
            return Optional.ofNullable(chunk);
        } finally {
            lock.unlock();
        }
    }

    /**
     * Waits for the next chunk and decodes it as UTF-8.
     *
     * @return the chunk as text, or empty once the body has ended
     * @throws InterruptedException if the calling thread is interrupted while waiting
     */
    public Optional<String> nextText() throws InterruptedException {
        return next().map(chunk -> new String(chunk, StandardCharsets.UTF_8));
    }

    /**
     * Reads the rest of the body, which makes sense for a body that ends. A body that never ends
     * is bounded by its byte cap and the request deadline.
     *
     * @return the remaining bytes
     * @throws InterruptedException if the calling thread is interrupted while waiting
     */
    public byte[] readAll() throws InterruptedException {
        var all = new ByteArrayOutputStream();
        for (Optional<byte[]> chunk; (chunk = next()).isPresent();) { all.writeBytes(chunk.get()); }
        return all.toByteArray();
    }

    /**
     * Reads the rest of the body as UTF-8 text.
     *
     * @return the remaining text
     * @throws InterruptedException if the calling thread is interrupted while waiting
     */
    public String readAllText() throws InterruptedException {
        return new String(readAll(), StandardCharsets.UTF_8);
    }

    /**
     * Returns the outcome of the request. It completes normally when the body returned without
     * the stream being aborted, and exceptionally with what ended it otherwise: the exception the
     * handler threw (including a {@link StreamAbortedException}), a deadline, or a cancellation
     * after {@link #close()}. In the listener the same outcomes close the connection without a
     * final chunk.
     *
     * @return the outcome
     */
    public CompletableFuture<Void> completion() { return completion; }

    /**
     * Disconnects: the handler can no longer write, and is interrupted if it is not writing.
     * Chunks that were not read are dropped.
     */
    @Override public void close() {
        lock.lock();
        try {
            closed = true;
            ended = true;
            chunks.clear();
            writer.unread = 0;
            changed.signalAll();
        } finally {
            lock.unlock();
        }
        cancel.thenAccept(Runnable::run);
    }

    /**
     * Plays the listener closing: the handler's writer reports {@code shutdownRequested()} and runs
     * the actions registered with {@code onShutdown}, once. Writes keep working, as they do during
     * the listener's grace period, so a body that ends normally is seen to complete, with its
     * final chunk. Use {@link #close()} afterwards to play the end of the grace period instead.
     * Idempotent.
     */
    public void beginShutdown() { writer.beginShutdown(); }

    /** Where the request's cancellation is attached once the request has been submitted. */
    void attach(Runnable cancellation) { cancel.complete(cancellation); }

    /** The writer the handler's stream body receives. */
    BodyWriter writer() { return writer; }

    /** The reason the stream was aborted, or null. */
    Reason aborted() { return writer.aborted; }

    /** Marks the body as ended and wakes the reader; the pieces already queued stay readable. */
    void end(Throwable failure) {
        lock.lock();
        try {
            ended = true;
            changed.signalAll();
        } finally {
            lock.unlock();
        }
        if (failure == null) { completion.complete(null); } else { completion.completeExceptionally(failure); }
    }

    /** A response that was not streamed: its body is the one and only chunk. */
    static StreamedResponse whole(int status, Map<String, String> headers, byte[] body) {
        var response = new StreamedResponse(status, headers, Long.MAX_VALUE, null, StreamBuffering.HAND_OFF, null);
        if (body.length > 0) { response.chunks.add(body); }
        response.ended = true;
        return response;
    }

    private final class Writer implements BodyWriter {
        private final long limit;
        private final ExecutionContext execution;
        private final StreamBuffering buffering;
        private final StreamMetrics.Scope metrics;
        private final java.util.List<Runnable> shutdownActions = new java.util.ArrayList<>();
        private Reason aborted;
        private long written;
        /** Bytes queued and not yet taken by the reader. */
        private long unread;
        /** False from the moment the unread bytes reach the high water mark until they fall below the low one. */
        private boolean writable = true;
        private boolean shutdown;

        private Writer(long limit, ExecutionContext execution, StreamBuffering buffering, StreamMetrics.Scope metrics) {
            this.metrics = metrics;
            this.limit = limit;
            this.execution = execution;
            this.buffering = buffering;
        }

        @Override public void write(byte[] bytes, int offset, int length) throws StreamAbortedException {
            Objects.requireNonNull(bytes, "bytes");
            Objects.checkFromIndexSize(offset, length, bytes.length);
            lock.lock();
            try {
                check();
                if (length > limit - written) { aborted = Reason.LIMIT_EXCEEDED; throw failure(); }
                for (int sent = 0; sent < length;) {
                    int piece = (int) Math.min(buffering.pieceBytes(), (long) length - sent);
                    awaitWritable();
                    chunks.add(java.util.Arrays.copyOfRange(bytes, offset + sent, offset + sent + piece));
                    unread += piece;
                    if (unread >= buffering.highWaterBytes()) { writable = false; }
                    written += piece;
                    sent += piece;
                    changed.signalAll();
                }
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                if (aborted == null) { aborted = closed ? Reason.CLIENT_DISCONNECTED : execution.isExpired() ? Reason.TIMEOUT : Reason.SHUTDOWN; }
                throw failure();
            } finally {
                lock.unlock();
            }
        }

        /** Waits, like a channel that is not writable, until the reader has taken enough. */
        private void awaitWritable() throws InterruptedException, StreamAbortedException {
            if (writable) { return; }
            if (metrics != null) { metrics.blocked(); }
            var stall = buffering.stallTimeout();
            long stalledUntil = stall == null ? Long.MAX_VALUE : System.nanoTime() + stall.toNanos();
            while (!writable) {
                long remaining = execution.remainingTime().toNanos();
                if (stall != null) { remaining = Math.min(remaining, stalledUntil - System.nanoTime()); }
                if (remaining <= 0) {
                    aborted = execution.isExpired() ? Reason.TIMEOUT : Reason.CLIENT_DISCONNECTED;
                    throw failure();
                }
                changed.awaitNanos(remaining);
                check();
            }
        }

        /** The reader took a chunk; called with the lock held. */
        void taken(int length) {
            unread -= length;
            if (!writable && unread < buffering.lowWaterBytes()) { writable = true; }
        }

        @Override public long bytesWritten() {
            lock.lock();
            try { return written; } finally { lock.unlock(); }
        }

        @Override public boolean shutdownRequested() {
            lock.lock();
            try { return shutdown; } finally { lock.unlock(); }
        }

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

        void beginShutdown() {
            java.util.List<Runnable> actions;
            lock.lock();
            try {
                if (shutdown) { return; }
                shutdown = true;
                actions = java.util.List.copyOf(shutdownActions);
                shutdownActions.clear();
            } finally {
                lock.unlock();
            }
            actions.forEach(Writer::run);
        }

        private static void run(Runnable action) {
            try {
                action.run();
            } catch (RuntimeException failure) {
                System.getLogger(StreamedResponse.class.getName())
                        .log(System.Logger.Level.WARNING, "A stream shutdown action failed", failure);
            }
        }

        private void check() throws StreamAbortedException {
            if (aborted == null) {
                if (closed) { aborted = Reason.CLIENT_DISCONNECTED; }
                else if (execution.isExpired()) { aborted = Reason.TIMEOUT; }
            }
            if (aborted != null) { throw failure(); }
        }

        private StreamAbortedException failure() { return new StreamAbortedException(aborted); }
    }

    /** Collects a whole body in memory, with the same cap and deadline rules as a live stream. */
    static final class Collector implements BodyWriter {
        private final long limit;
        private final ExecutionContext execution;
        private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        private Reason aborted;

        Collector(long limit, ExecutionContext execution) {
            this.limit = limit;
            this.execution = execution;
        }

        @Override public void write(byte[] source, int offset, int length) throws StreamAbortedException {
            Objects.requireNonNull(source, "bytes");
            Objects.checkFromIndexSize(offset, length, source.length);
            if (aborted == null) {
                if (execution.isExpired()) { aborted = Reason.TIMEOUT; }
                else if (length > limit - bytes.size()) { aborted = Reason.LIMIT_EXCEEDED; }
            }
            if (aborted != null) { throw new StreamAbortedException(aborted); }
            bytes.write(source, offset, length);
        }

        @Override public long bytesWritten() { return bytes.size(); }

        Reason aborted() { return aborted; }

        byte[] toByteArray() { return bytes.toByteArray(); }
    }
}
