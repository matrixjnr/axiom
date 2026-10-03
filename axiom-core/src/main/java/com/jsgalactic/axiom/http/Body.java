package com.jsgalactic.axiom.http;

import java.nio.BufferOverflowException;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Immutable request content and its declared content type.
 * <p>
 * A body owns a private copy of its bytes: factories copy their input, and {@link #bytes()}
 * returns a fresh copy, so neither the producer nor any reader can change what others see.
 * Bodies are safe to share between threads. Transports enforce size limits before creating a
 * body; a body itself has no limit beyond the array size. {@link #toString()} reports only the
 * length and media type, never content.
 */
public final class Body {
    private static final Body EMPTY = new Body(null, new byte[0]);
    private static final Pattern MEDIA_TYPE =
            Pattern.compile("[!#$%&'*+.^_`|~0-9A-Za-z-]+/[!#$%&'*+.^_`|~0-9A-Za-z-]+");
    private final String contentType;
    private final byte[] bytes;

    private Body(String contentType, byte[] bytes) {
        if (contentType != null && (contentType.length() > 1024
                || contentType.chars().anyMatch(c -> (c < 32 && c != '\t') || c == 127 || c > 255))) {
            throw new IllegalArgumentException("Invalid content type");
        }
        this.contentType = contentType;
        this.bytes = bytes;
    }

    /**
     * Returns the shared empty body without a content type.
     *
     * @return empty body
     */
    public static Body empty() { return EMPTY; }

    /**
     * Creates a body from a copy of the supplied bytes.
     *
     * @param contentType Content-Type header value, or null when absent
     * @param bytes content; copied
     * @return body
     * @throws IllegalArgumentException if the content type contains control or non-Latin-1 characters
     */
    public static Body of(String contentType, byte[] bytes) {
        Objects.requireNonNull(bytes, "bytes");
        return new Body(contentType, bytes.clone());
    }

    /**
     * Creates a body by concatenating the remaining bytes of each buffer in order, copying them
     * once. The buffers' positions are not changed.
     *
     * @param contentType Content-Type header value, or null when absent
     * @param parts content buffers; read, not retained
     * @return body
     * @throws IllegalArgumentException if the combined length exceeds the maximum array size or
     *         the content type is invalid
     */
    public static Body of(String contentType, ByteBuffer... parts) {
        long total = 0;
        for (var part : parts) { total += part.remaining(); }
        if (total > Integer.MAX_VALUE - 8) { throw new IllegalArgumentException("Body too large"); }
        var bytes = new byte[(int) total];
        int offset = 0;
        for (var part : parts) {
            int length = part.remaining();
            part.duplicate().get(bytes, offset, length);
            offset += length;
        }
        return new Body(contentType, bytes);
    }

    /**
     * Starts a body whose array is filled incrementally and then handed to the body without
     * another copy. Transports use it to read network buffers straight into the array that
     * becomes the request body, so a body is copied exactly once on its way in.
     *
     * @param contentType Content-Type header value, or null when absent
     * @param capacity initial capacity in bytes; not negative
     * @return an empty builder
     * @throws IllegalArgumentException for a negative or oversized capacity
     */
    public static Builder builder(String contentType, int capacity) {
        return new Builder(contentType, capacity);
    }

    /**
     * Collects bytes into a private array and turns it into a {@link Body} without copying it
     * again. The builder owns its array until {@link #build()}; it never exposes it, so the body's
     * immutability cannot be broken through the builder. Not thread-safe, single use: after
     * {@code build()} every method throws {@link IllegalStateException}.
     */
    public static final class Builder {
        private final String contentType;
        private byte[] array;
        private int length;

        private Builder(String contentType, int capacity) {
            if (capacity < 0 || capacity > Integer.MAX_VALUE - 8) {
                throw new IllegalArgumentException("Invalid capacity: " + capacity);
            }
            this.contentType = contentType;
            array = new byte[capacity];
        }

        /**
         * Returns the number of bytes written so far.
         *
         * @return length in bytes
         */
        public int length() {
            open();
            return length;
        }

        /**
         * Returns the number of bytes that can be written before the capacity must grow.
         *
         * @return capacity in bytes
         */
        public int capacity() { return open().length; }

        /**
         * Grows the capacity to exactly {@code capacity} bytes, keeping the content. The old and
         * the new array coexist while the content is moved.
         *
         * @param capacity new capacity; at least the current length
         * @return this builder
         * @throws IllegalArgumentException if the capacity is below the current length
         */
        public Builder capacity(int capacity) {
            var current = open();
            if (capacity < length || capacity > Integer.MAX_VALUE - 8) {
                throw new IllegalArgumentException("Invalid capacity: " + capacity);
            }
            if (capacity != current.length) { array = Arrays.copyOf(current, capacity); }
            return this;
        }

        /**
         * Appends the remaining bytes of a buffer, which are consumed, so one read goes straight
         * from the source into the array.
         *
         * @param source bytes to append
         * @return this builder
         * @throws BufferOverflowException if the bytes do not fit in the capacity
         */
        public Builder write(ByteBuffer source) {
            var current = open();
            int count = source.remaining();
            if (count > current.length - length) { throw new BufferOverflowException(); }
            source.get(current, length, count);
            length += count;
            return this;
        }

        /**
         * Finishes the body. When the array is exactly full it becomes the body as is; otherwise
         * it is trimmed once.
         *
         * @return the body
         * @throws IllegalArgumentException if the content type contains control or non-Latin-1 characters
         * @throws IllegalStateException if the builder was already used
         */
        public Body build() {
            var current = open();
            var bytes = length == current.length ? current : Arrays.copyOf(current, length);
            var body = new Body(contentType, bytes);
            array = null;
            return body;
        }

        private byte[] open() {
            if (array == null) { throw new IllegalStateException("Builder already built"); }
            return array;
        }
    }

    /**
     * Returns the Content-Type header value as received.
     *
     * @return raw content type, if declared
     */
    public Optional<String> contentType() { return Optional.ofNullable(contentType); }

    /**
     * Returns the lowercase {@code type/subtype} of the content type, without parameters.
     *
     * @return media type, or empty when absent or malformed
     */
    public Optional<String> mediaType() {
        if (contentType == null) { return Optional.empty(); }
        int end = contentType.indexOf(';');
        var type = (end < 0 ? contentType : contentType.substring(0, end)).trim();
        return MEDIA_TYPE.matcher(type).matches() ? Optional.of(type.toLowerCase(Locale.ROOT)) : Optional.empty();
    }

    /**
     * Returns the lowercase value of the content type's {@code charset} parameter, unquoted.
     *
     * @return charset name, or empty when absent
     */
    public Optional<String> charset() {
        if (contentType == null) { return Optional.empty(); }
        var parameters = contentType.split(";");
        for (int i = 1; i < parameters.length; i++) {
            var parameter = parameters[i].trim();
            int equals = parameter.indexOf('=');
            if (equals > 0 && parameter.substring(0, equals).trim().equalsIgnoreCase("charset")) {
                var value = parameter.substring(equals + 1).trim();
                if (value.length() >= 2 && value.startsWith("\"") && value.endsWith("\"")) {
                    value = value.substring(1, value.length() - 1);
                }
                return Optional.of(value.toLowerCase(Locale.ROOT));
            }
        }
        return Optional.empty();
    }

    /**
     * Returns the content length in bytes.
     *
     * @return length
     */
    public int length() { return bytes.length; }

    /**
     * Reports whether there is no content.
     *
     * @return true for zero bytes
     */
    public boolean isEmpty() { return bytes.length == 0; }

    /**
     * Returns a copy of the content.
     *
     * @return fresh array owned by the caller
     */
    public byte[] bytes() { return bytes.clone(); }

    /**
     * Returns a read-only view of the content without copying it.
     *
     * @return read-only buffer positioned at zero
     */
    public ByteBuffer asReadOnlyBuffer() { return ByteBuffer.wrap(bytes).asReadOnlyBuffer(); }

    @Override public boolean equals(Object other) {
        return other instanceof Body that && Objects.equals(contentType, that.contentType)
                && Arrays.equals(bytes, that.bytes);
    }

    @Override public int hashCode() { return 31 * Objects.hashCode(contentType) + Arrays.hashCode(bytes); }

    /**
     * Describes the body without revealing its content.
     *
     * @return length and media type
     */
    @Override public String toString() {
        return "Body[length=" + bytes.length + ", mediaType=" + mediaType().orElse("none") + "]";
    }
}
