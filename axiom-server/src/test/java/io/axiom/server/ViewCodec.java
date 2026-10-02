package io.axiom.server;

import io.axiom.codec.spi.BodyCodec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Set;

/**
 * Test codec that reads request bodies through the buffer view only. It returns a description of
 * the view it received and fails if the runtime falls back to the copying array method.
 */
public final class ViewCodec implements BodyCodec {
    /** Media type handled by this codec. */
    public static final String MEDIA_TYPE = "application/x-view";

    /** Creates the codec. */
    public ViewCodec() { }

    @Override public Set<String> mediaTypes() { return Set.of(MEDIA_TYPE); }

    @Override public <T> T decode(ByteBuffer content, Class<T> type) {
        int first = content.hasRemaining() ? content.get(content.position()) : -1;
        return type.cast("readOnly=" + content.isReadOnly() + ";remaining=" + content.remaining()
                + ";first=" + (char) first);
    }

    @Override public <T> T decode(byte[] content, Class<T> type) {
        throw new AssertionError("The runtime copied the body instead of passing a view");
    }

    @Override public byte[] encode(Object value) { return value.toString().getBytes(StandardCharsets.UTF_8); }
}
