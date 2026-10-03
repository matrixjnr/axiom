package com.jsgalactic.axiom.openapi;

import java.util.List;
import java.util.Map;

/**
 * A tiny JSON writer for documents built from maps, lists, strings, numbers and booleans. Maps
 * keep their iteration order, so callers decide the (deterministic) key order. Output is indented
 * with two spaces and ends with a newline.
 */
final class Json {
    private Json() { }

    /**
     * Writes a value.
     *
     * @param value map, list, string, number, boolean or null
     * @return the document text
     * @throws IllegalArgumentException for any other value
     */
    static String write(Object value) {
        var out = new StringBuilder(4096);
        write(out, value, 0);
        return out.append('\n').toString();
    }

    private static void write(StringBuilder out, Object value, int depth) {
        if (value == null) {
            out.append("null");
        } else if (value instanceof String text) {
            string(out, text);
        } else if (value instanceof Boolean || value instanceof Integer || value instanceof Long) {
            out.append(value);
        } else if (value instanceof Map<?, ?> map) {
            if (map.isEmpty()) {
                out.append("{}");
                return;
            }
            out.append('{');
            var first = true;
            for (var entry : map.entrySet()) {
                out.append(first ? "\n" : ",\n");
                first = false;
                indent(out, depth + 1);
                string(out, (String) entry.getKey());
                out.append(": ");
                write(out, entry.getValue(), depth + 1);
            }
            out.append('\n');
            indent(out, depth);
            out.append('}');
        } else if (value instanceof List<?> list) {
            if (list.isEmpty()) {
                out.append("[]");
                return;
            }
            out.append('[');
            var first = true;
            for (var element : list) {
                out.append(first ? "\n" : ",\n");
                first = false;
                indent(out, depth + 1);
                write(out, element, depth + 1);
            }
            out.append('\n');
            indent(out, depth);
            out.append(']');
        } else {
            throw new IllegalArgumentException("Cannot write " + value.getClass().getSimpleName() + " as JSON");
        }
    }

    private static void indent(StringBuilder out, int depth) {
        for (int i = 0; i < depth; i++) {
            out.append("  ");
        }
    }

    private static void string(StringBuilder out, String text) {
        out.append('"');
        for (int i = 0; i < text.length(); i++) {
            var c = text.charAt(i);
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                case '\b' -> out.append("\\b");
                case '\f' -> out.append("\\f");
                default -> {
                    if (c < 0x20 || Character.isSurrogate(c) && !validSurrogate(text, i)) {
                        out.append(String.format("\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
                }
            }
        }
        out.append('"');
    }

    private static boolean validSurrogate(String text, int i) {
        var c = text.charAt(i);
        return Character.isHighSurrogate(c)
                ? i + 1 < text.length() && Character.isLowSurrogate(text.charAt(i + 1))
                : i > 0 && Character.isHighSurrogate(text.charAt(i - 1));
    }
}
