package io.axiom.codec.spi;

import java.nio.ByteBuffer;
import java.util.Set;

/**
 * Converts request and response bodies for a fixed set of media types. Implementations are
 * discovered with {@link java.util.ServiceLoader} when an application starts; two installed
 * codecs that declare the same media type fail startup. Codecs are shared by all requests and
 * must be thread-safe.
 * <p>
 * This SPI is experimental. Failures must never expose input or parser messages: decoding
 * reports {@link io.axiom.error.DecodeException} with a safe code, and encoding failures are
 * server errors. Implementations should not depend on the {@code charset} parameter; the runtime
 * accepts only UTF-8 (or no charset parameter) before calling a codec.
 */
public interface BodyCodec {
    /**
     * Returns the media types this codec handles.
     *
     * @return non-empty set of lowercase {@code type/subtype} names without parameters
     */
    Set<String> mediaTypes();

    /**
     * Reports whether this codec handles a media type.
     *
     * @param mediaType lowercase {@code type/subtype} without parameters
     * @return true if the type is one of {@link #mediaTypes()}
     */
    default boolean supports(String mediaType) { return mediaTypes().contains(mediaType); }

    /**
     * Decodes a complete request body.
     *
     * @param content UTF-8 content owned by the codec for the duration of the call
     * @param type target type
     * @param <T> target type
     * @return decoded value, never null
     * @throws io.axiom.error.DecodeException if the content is malformed or does not match the type
     */
    <T> T decode(byte[] content, Class<T> type);

    /**
     * Decodes a complete request body from a read-only view of its content. The runtime calls
     * this method, passing a view of the request's {@link io.axiom.http.Body} so that the
     * content is not copied for the codec.
     * <p>
     * The default implementation copies the remaining bytes into a new array once and calls
     * {@link #decode(byte[], Class)}, so codecs that implement only that method keep working.
     * Codecs that can read a buffer directly should override this method. Implementations may
     * move the buffer's position, must not retain the buffer or a view of it after returning,
     * and cannot modify the content because the buffer is read-only.
     *
     * @param content read-only view of the UTF-8 content, from its position to its limit
     * @param type target type
     * @param <T> target type
     * @return decoded value, never null
     * @throws io.axiom.error.DecodeException if the content is malformed or does not match the type
     */
    default <T> T decode(ByteBuffer content, Class<T> type) {
        var copy = new byte[content.remaining()];
        content.duplicate().get(copy);
        return decode(copy, type);
    }

    /**
     * Encodes a response value.
     *
     * @param value value to encode, never a String or byte array
     * @return encoded UTF-8 bytes
     * @throws IllegalStateException if the value cannot be encoded
     */
    byte[] encode(Object value);
}
