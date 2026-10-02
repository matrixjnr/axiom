package io.axiom.server;

import io.axiom.codec.spi.BodyCodec;
import java.util.Set;

/** Second application/json codec, installed only by the duplicate-discovery test. */
public final class OtherJsonCodec implements BodyCodec {
    /** Creates the codec. */
    public OtherJsonCodec() { }
    @Override public Set<String> mediaTypes() { return Set.of("text/csv", "application/json"); }
    @Override public <T> T decode(byte[] content, Class<T> type) { throw new UnsupportedOperationException(); }
    @Override public byte[] encode(Object value) { throw new UnsupportedOperationException(); }
}
