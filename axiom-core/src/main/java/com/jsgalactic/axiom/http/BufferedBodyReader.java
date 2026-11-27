package com.jsgalactic.axiom.http;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Objects;

/** A reader over a private byte array; all state is guarded by {@code this}. */
final class BufferedBodyReader implements BodyReader {
    private final ArrayDeque<byte[]> chunks = new ArrayDeque<>();
    private boolean paused;
    private boolean ended;
    private boolean closed;
    private long bytesRead;

    BufferedBodyReader(byte[] bytes) {
        if (bytes.length > 0) {
            chunks.add(bytes);
        } else {
            ended = true;
        }
    }

    @Override public synchronized int read(byte[] bytes, int offset, int length) throws IOException {
        Objects.checkFromIndexSize(offset, length, bytes.length);
        while (true) {
            if (closed) { throw new BodyReadException(BodyReadException.Reason.CANCELLED); }
            if (paused) {
                waitForResume();
                continue;
            }
            if (chunks.isEmpty()) {
                if (ended) { return -1; }
                waitForResume();
                continue;
            }
            var chunk = chunks.peek();
            int count = Math.min(length, chunk.length);
            System.arraycopy(chunk, 0, bytes, offset, count);
            bytesRead += count;
            chunks.removeFirst();
            if (count < chunk.length) {
                chunks.addFirst(Arrays.copyOfRange(chunk, count, chunk.length));
            } else if (chunks.isEmpty()) {
                ended = true;
            }
            return count;
        }
    }

    /** Waits for a signal; an interrupted handler fails with its interrupt flag restored. */
    private void waitForResume() throws IOException {
        try {
            wait();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new BodyReadException(BodyReadException.Reason.INTERRUPTED);
        }
    }

    @Override public synchronized void pause() { paused = true; }

    @Override public synchronized void resume() {
        paused = false;
        notifyAll();
    }

    @Override public synchronized boolean paused() { return paused; }

    @Override public synchronized long bytesRead() { return bytesRead; }

    @Override public synchronized void close() {
        closed = true;
        chunks.clear();
        notifyAll();
    }
}
