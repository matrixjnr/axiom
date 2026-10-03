package com.jsgalactic.axiom.security.jwt;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A strict, bounded RFC 8259 parser for JOSE headers and JWT claims sets. Values are maps
 * (objects, duplicate names rejected), lists, strings, {@link JsonNumber} values holding the exact
 * number text, booleans and {@link #NULL}. Input must be well-formed UTF-8; nesting is limited.
 * Error messages never contain the input.
 */
final class Json {
    /** The JSON {@code null} literal, distinct from an absent member. */
    static final Object NULL = new Object() {
        @Override public String toString() { return "null"; }
    };
    private static final int MAX_DEPTH = 16;

    /** A JSON number kept as its exact text. */
    record JsonNumber(String text) {
        /** Returns the value if it is a non-negative integer of at most 15 digits, else -1. */
        long nonNegativeInteger() {
            if (text.isEmpty() || text.length() > 15 || text.charAt(0) == '-') { return -1; }
            for (int i = 0; i < text.length(); i++) {
                if (text.charAt(i) < '0' || text.charAt(i) > '9') { return -1; }
            }
            return Long.parseLong(text);
        }
    }

    private final String text;
    private int position;

    private Json(String text) {
        this.text = text;
    }

    /**
     * Parses a UTF-8 document whose top level must be an object.
     *
     * @throws IllegalArgumentException for malformed input
     */
    static Map<String, Object> parseObject(byte[] utf8) {
        String text;
        try {
            text = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(utf8)).toString();
        } catch (CharacterCodingException malformed) {
            throw new IllegalArgumentException("JSON is not valid UTF-8");
        }
        var parser = new Json(text);
        parser.whitespace();
        if (parser.peek() != '{') { throw new IllegalArgumentException("JSON document is not an object"); }
        var value = parser.value(0);
        parser.whitespace();
        if (parser.position != text.length()) { throw new IllegalArgumentException("Trailing content after JSON"); }
        @SuppressWarnings("unchecked") var object = (Map<String, Object>) value;
        return object;
    }

    private Object value(int depth) {
        if (depth > MAX_DEPTH) { throw new IllegalArgumentException("JSON nested too deeply"); }
        whitespace();
        char c = peek();
        return switch (c) {
            case '{' -> object(depth);
            case '[' -> array(depth);
            case '"' -> string();
            case 't' -> literal("true", Boolean.TRUE);
            case 'f' -> literal("false", Boolean.FALSE);
            case 'n' -> literal("null", NULL);
            default -> {
                if (c == '-' || (c >= '0' && c <= '9')) { yield number(); }
                throw new IllegalArgumentException("Unexpected JSON character");
            }
        };
    }

    private Map<String, Object> object(int depth) {
        position++;
        var members = new LinkedHashMap<String, Object>();
        whitespace();
        if (peek() == '}') { position++; return Collections.unmodifiableMap(members); }
        while (true) {
            whitespace();
            if (peek() != '"') { throw new IllegalArgumentException("Expected a JSON member name"); }
            var name = string();
            whitespace();
            expect(':');
            var value = value(depth + 1);
            if (members.put(name, value) != null) {
                throw new IllegalArgumentException("Duplicate JSON member name");
            }
            whitespace();
            if (peek() == ',') { position++; continue; }
            expect('}');
            return Collections.unmodifiableMap(members);
        }
    }

    private List<Object> array(int depth) {
        position++;
        var elements = new ArrayList<Object>();
        whitespace();
        if (peek() == ']') { position++; return List.of(); }
        while (true) {
            elements.add(value(depth + 1));
            whitespace();
            if (peek() == ',') { position++; continue; }
            expect(']');
            return Collections.unmodifiableList(elements);
        }
    }

    private String string() {
        position++;
        var result = new StringBuilder();
        while (true) {
            char c = next();
            if (c == '"') { break; }
            if (c < 0x20) { throw new IllegalArgumentException("Control character in JSON string"); }
            if (c != '\\') { result.append(c); continue; }
            char escape = next();
            switch (escape) {
                case '"', '\\', '/' -> result.append(escape);
                case 'b' -> result.append('\b');
                case 'f' -> result.append('\f');
                case 'n' -> result.append('\n');
                case 'r' -> result.append('\r');
                case 't' -> result.append('\t');
                case 'u' -> result.append(hex4());
                default -> throw new IllegalArgumentException("Invalid JSON escape");
            }
        }
        // Escapes can produce lone surrogates; a claim must be well-formed text.
        for (int i = 0; i < result.length(); i++) {
            char c = result.charAt(i);
            if (Character.isHighSurrogate(c) && i + 1 < result.length() && Character.isLowSurrogate(result.charAt(i + 1))) {
                i++;
            } else if (Character.isSurrogate(c)) {
                throw new IllegalArgumentException("Unpaired surrogate in JSON string");
            }
        }
        return result.toString();
    }

    private char hex4() {
        int value = 0;
        for (int i = 0; i < 4; i++) {
            int digit = Character.digit(next(), 16);
            if (digit < 0) { throw new IllegalArgumentException("Invalid JSON unicode escape"); }
            value = value * 16 + digit;
        }
        return (char) value;
    }

    private JsonNumber number() {
        int start = position;
        if (peek() == '-') { position++; }
        if (peek() == '0') {
            position++;
        } else if (peek() >= '1' && peek() <= '9') {
            digits();
        } else {
            throw new IllegalArgumentException("Invalid JSON number");
        }
        if (peek() == '.') { position++; requireDigit(); digits(); }
        if (peek() == 'e' || peek() == 'E') {
            position++;
            if (peek() == '+' || peek() == '-') { position++; }
            requireDigit();
            digits();
        }
        if (position - start > 64) { throw new IllegalArgumentException("JSON number too long"); }
        return new JsonNumber(text.substring(start, position));
    }

    private void requireDigit() {
        if (peek() < '0' || peek() > '9') { throw new IllegalArgumentException("Invalid JSON number"); }
    }

    private void digits() {
        while (peek() >= '0' && peek() <= '9') { position++; }
    }

    private Object literal(String word, Object value) {
        if (!text.startsWith(word, position)) { throw new IllegalArgumentException("Invalid JSON literal"); }
        position += word.length();
        return value;
    }

    private void whitespace() {
        while (position < text.length()) {
            char c = text.charAt(position);
            if (c != ' ' && c != '\t' && c != '\n' && c != '\r') { return; }
            position++;
        }
    }

    private void expect(char c) {
        if (next() != c) { throw new IllegalArgumentException("Malformed JSON"); }
    }

    private char peek() {
        return position < text.length() ? text.charAt(position) : '\0';
    }

    private char next() {
        if (position >= text.length()) { throw new IllegalArgumentException("Unexpected end of JSON"); }
        return text.charAt(position++);
    }
}
