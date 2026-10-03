package com.jsgalactic.axiom.http.internal;

import com.jsgalactic.axiom.codec.spi.BodyCodec;
import java.nio.charset.StandardCharsets;
import java.util.Set;

/** Test-only application/json codec that encodes with toString and cannot decode. */
public final class ToStringCodec implements BodyCodec {
    /** Creates the codec. */
    public ToStringCodec() { }
    @Override public Set<String> mediaTypes() { return Set.of("application/json"); }
    @Override public <T> T decode(byte[] content, Class<T> type) { throw new UnsupportedOperationException(); }
    @Override public byte[] encode(Object value) { return value.toString().getBytes(StandardCharsets.UTF_8); }
}
