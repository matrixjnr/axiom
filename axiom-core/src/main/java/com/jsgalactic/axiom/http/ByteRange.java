package com.jsgalactic.axiom.http;

/**
 * One inclusive byte range of a representation, with offsets measured from
 * zero (RFC 9110 section 14.1.2). A range always describes at least one byte;
 * {@link ByteRanges} resolves open-ended and suffix forms into these values.
 *
 * @param first offset of the first byte, inclusive
 * @param last offset of the last byte, inclusive
 */
public record ByteRange(long first, long last) {
    /**
     * Validates the bounds.
     *
     * @throws IllegalArgumentException if the first offset is negative or the last is before it
     */
    public ByteRange {
        if (first < 0 || last < first) { throw new IllegalArgumentException("Invalid byte range"); }
    }

    /**
     * Returns how many bytes the range covers, including both endpoints.
     *
     * @return the number of bytes, at least one
     */
    public long length() {
        return last - first + 1;
    }

    /**
     * Formats the range as the value of a {@code Content-Range} field for a
     * representation of the given length.
     *
     * @param completeLength total length of the representation in bytes
     * @return a value such as {@code bytes 2-5/10}
     */
    public String contentRange(long completeLength) {
        return "bytes " + first + '-' + last + '/' + completeLength;
    }
}
