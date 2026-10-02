package io.axiom.codec.spi;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import org.junit.jupiter.api.Test;

class BodyCodecTest {
    /** A codec written before the buffer method existed: it implements only the array form. */
    private static final class ArrayOnlyCodec implements BodyCodec {
        byte[] received;

        @Override public Set<String> mediaTypes() { return Set.of("text/x-array"); }

        @Override public <T> T decode(byte[] content, Class<T> type) {
            received = content.clone();
            content[0] = 'X'; // The codec owns the array it was given.
            return type.cast(new String(content, StandardCharsets.UTF_8));
        }

        @Override public byte[] encode(Object value) { throw new UnsupportedOperationException(); }
    }

    @Test void bufferDecodingDelegatesToArrayCodecsWithAPrivateCopyOfTheRemainingBytes() {
        var codec = new ArrayOnlyCodec();
        var source = "--payload--".getBytes(StandardCharsets.US_ASCII);
        var view = ByteBuffer.wrap(source).position(2).limit(9).asReadOnlyBuffer();

        assertThat(codec.decode(view, String.class)).isEqualTo("Xayload");

        assertThat(new String(codec.received, StandardCharsets.US_ASCII)).isEqualTo("payload");
        assertThat(view.position()).isEqualTo(2);
        assertThat(view.remaining()).isEqualTo(7);
        assertThat(new String(source, StandardCharsets.US_ASCII)).isEqualTo("--payload--");
    }
}
