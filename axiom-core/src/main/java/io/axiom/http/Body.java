package io.axiom.http;

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
