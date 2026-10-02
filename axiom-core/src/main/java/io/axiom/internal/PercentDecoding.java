package io.axiom.internal;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;

/**
 * Strict, single-pass percent-decoding shared by path captures and query parameters.
 * <p>
 * Not application API. Decoding is strict: every {@code %} must be followed by two hex digits,
 * the decoded bytes must be well-formed UTF-8 (no overlong forms, no encoded surrogates), and a
 * raw non-ASCII character must not be a lone surrogate. Failures throw
 * {@link IllegalArgumentException} with a fixed message that never contains the input, because
 * the input is client data that may hold credentials.
 */
public final class PercentDecoding {
    private PercentDecoding() {}

    /**
     * Decodes {@code raw[start, end)} once.
     *
     * @param raw text containing the range
     * @param start first index, inclusive
     * @param end last index, exclusive
     * @param plusAsSpace whether {@code +} decodes to a space (form encoding) instead of itself
     * @return decoded text
     * @throws IllegalArgumentException for a malformed escape or malformed UTF-8
     */
    public static String decode(String raw, int start, int end, boolean plusAsSpace) {
        boolean plain = true;
        for (int i = start; i < end && plain; i++) {
            char c = raw.charAt(i);
            plain = c != '%' && (c != '+' || !plusAsSpace) && !Character.isSurrogate(c);
        }
        if (plain) { return raw.substring(start, end); }
        var bytes = new byte[(end - start) * 3];
        int length = 0;
        for (int i = start; i < end; i++) {
            char c = raw.charAt(i);
            if (c == '%') {
                int high = i + 2 < end ? hex(raw.charAt(i + 1)) : -1;
                int low = i + 2 < end ? hex(raw.charAt(i + 2)) : -1;
                if (high < 0 || low < 0) { throw new IllegalArgumentException("Malformed percent-escape"); }
                bytes[length++] = (byte) (high * 16 + low);
                i += 2;
            } else if (c == '+' && plusAsSpace) {
                bytes[length++] = ' ';
            } else if (c < 0x80) {
                bytes[length++] = (byte) c;
            } else {
                int next = i + 1;
                if (Character.isHighSurrogate(c) && next < end && Character.isLowSurrogate(raw.charAt(next))) {
                    next++;
                } else if (Character.isSurrogate(c)) {
                    throw new IllegalArgumentException("Unpaired surrogate");
                }
                for (byte b : raw.substring(i, next).getBytes(StandardCharsets.UTF_8)) { bytes[length++] = b; }
                i = next - 1;
            }
        }
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes, 0, length)).toString();
        } catch (CharacterCodingException malformed) {
            throw new IllegalArgumentException("Percent-escapes are not valid UTF-8", malformed);
        }
    }

    /**
     * Returns the value of a hex digit.
     *
     * @param c character
     * @return 0 to 15, or -1 when {@code c} is not a hex digit
     */
    public static int hex(char c) {
        if (c >= '0' && c <= '9') { return c - '0'; }
        if (c >= 'a' && c <= 'f') { return c - 'a' + 10; }
        if (c >= 'A' && c <= 'F') { return c - 'A' + 10; }
        return -1;
    }
}
