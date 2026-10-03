package com.jsgalactic.axiom.server.internal;

import com.jsgalactic.axiom.codec.spi.BodyCodec;
import com.jsgalactic.axiom.observability.Metrics;
import java.nio.ByteBuffer;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * A codec that times its encoding and decoding and counts its failures for the one media type it is
 * registered under. Series are {@code axiom.codec.duration} (timer) and {@code axiom.codec.failures}
 * (counter), tagged {@code operation} ({@code decode} or {@code encode}) and {@code media_type}, the
 * type the codec declared, so the tag set is bounded by the installed codecs and never comes from a
 * request. A failure is any exception the codec throws; it is rethrown unchanged. A failing
 * {@link Metrics} implementation never fails a request: the first failure is logged and the
 * measurement dropped.
 */
final class MeteredCodec implements BodyCodec {
    static final String DURATION = "axiom.codec.duration";
    static final String FAILURES = "axiom.codec.failures";
    private static final System.Logger LOG = System.getLogger(MeteredCodec.class.getName());

    private final BodyCodec delegate;
    private final Metrics.Timer decodeTime;
    private final Metrics.Timer encodeTime;
    private final Metrics.Counter decodeFailures;
    private final Metrics.Counter encodeFailures;
    private final AtomicBoolean reported = new AtomicBoolean();

    private MeteredCodec(BodyCodec delegate, Metrics.Timer decodeTime, Metrics.Timer encodeTime,
            Metrics.Counter decodeFailures, Metrics.Counter encodeFailures) {
        this.delegate = delegate;
        this.decodeTime = decodeTime;
        this.encodeTime = encodeTime;
        this.decodeFailures = decodeFailures;
        this.encodeFailures = encodeFailures;
    }

    /** Wraps a codec for one of its media types; the codec itself when the instruments cannot be created. */
    static BodyCodec of(BodyCodec codec, String mediaType, Metrics metrics) {
        try {
            return new MeteredCodec(codec,
                    metrics.timer(DURATION, "operation", "decode", "media_type", mediaType),
                    metrics.timer(DURATION, "operation", "encode", "media_type", mediaType),
                    metrics.counter(FAILURES, "operation", "decode", "media_type", mediaType),
                    metrics.counter(FAILURES, "operation", "encode", "media_type", mediaType));
        } catch (RuntimeException failure) {
            LOG.log(System.Logger.Level.WARNING, "The metrics implementation failed; codec measurements are off", failure);
            return codec;
        }
    }

    /** The codec an installed codec was wrapped from. */
    static BodyCodec unwrap(BodyCodec codec) { return codec instanceof MeteredCodec metered ? metered.delegate : codec; }

    @Override public Set<String> mediaTypes() { return delegate.mediaTypes(); }

    @Override public boolean supports(String mediaType) { return delegate.supports(mediaType); }

    @Override public <T> T decode(byte[] content, Class<T> type) {
        long start = System.nanoTime();
        try {
            return delegate.decode(content, type);
        } catch (RuntimeException failure) {
            count(decodeFailures);
            throw failure;
        } finally {
            record(decodeTime, System.nanoTime() - start);
        }
    }

    @Override public <T> T decode(ByteBuffer content, Class<T> type) {
        long start = System.nanoTime();
        try {
            return delegate.decode(content, type);
        } catch (RuntimeException failure) {
            count(decodeFailures);
            throw failure;
        } finally {
            record(decodeTime, System.nanoTime() - start);
        }
    }

    @Override public byte[] encode(Object value) {
        long start = System.nanoTime();
        try {
            return delegate.encode(value);
        } catch (RuntimeException failure) {
            count(encodeFailures);
            throw failure;
        } finally {
            record(encodeTime, System.nanoTime() - start);
        }
    }

    private void count(Metrics.Counter counter) {
        try { counter.increment(); } catch (RuntimeException failure) { dropped(failure); }
    }

    private void record(Metrics.Timer timer, long nanos) {
        try { timer.record(nanos); } catch (RuntimeException failure) { dropped(failure); }
    }

    private void dropped(RuntimeException failure) {
        if (reported.compareAndSet(false, true)) {
            LOG.log(System.Logger.Level.WARNING,
                    "The metrics implementation failed; this and later failing measurements are dropped", failure);
        }
    }
}
