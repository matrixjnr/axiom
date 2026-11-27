package com.jsgalactic.axiom.graphql;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Small strict JSON reader and writer for GraphQL request envelopes and responses.
 *
 * <p>Only the data shapes graphql-java returns from JDK values are written: maps with string keys,
 * lists, arrays, strings, numbers, booleans, nulls and enums. An unsupported value is written as
 * null rather than serialized by reflection, so an application object cannot leak through this
 * adapter. Both directions are bounded in depth and output size.
 */
final class Json {
  private static final int MAX_DEPTH = 32;
  private static final int MAX_OUTPUT = 256 * 1024;

  private Json() {}

  static Object parse(String text) {
    var parser = new Parser(text);
    var value = parser.value(0);
    parser.skipWhitespace();
    if (!parser.atEnd()) {
      throw parser.fail();
    }
    return value;
  }

  static String write(Object value) {
    var out = new StringBuilder();
    writeValue(value, out, 0);
    return out.toString();
  }

  private static void writeValue(Object value, StringBuilder out, int depth) {
    if (depth > MAX_DEPTH || out.length() > MAX_OUTPUT) {
      throw new IllegalArgumentException("GraphQL response exceeds the JSON bounds");
    }
    if (value == null) {
      out.append("null");
    } else if (value instanceof String text) {
      writeString(text, out);
    } else if (value instanceof Boolean
        || value instanceof Integer
        || value instanceof Long
        || value instanceof Short
        || value instanceof Byte) {
      out.append(value);
    } else if (value instanceof Number number) {
      var text = number.toString();
      if (!isFinite(text)) {
        out.append("null");
      } else {
        out.append(text);
      }
    } else if (value instanceof Enum<?> value1) {
      writeString(value1.name(), out);
    } else if (value instanceof Map<?, ?> map) {
      out.append('{');
      boolean first = true;
      for (var entry : map.entrySet()) {
        if (!(entry.getKey() instanceof String key)) {
          continue;
        }
        if (!first) {
          out.append(',');
        }
        first = false;
        writeString(key, out);
        out.append(':');
        writeValue(entry.getValue(), out, depth + 1);
      }
      out.append('}');
    } else if (value instanceof Iterable<?> values) {
      writeSequence(values.iterator(), out, depth);
    } else if (value instanceof Object[] values) {
      writeSequence(List.of(values).iterator(), out, depth);
    } else {
      out.append("null");
    }
    if (out.length() > MAX_OUTPUT) {
      throw new IllegalArgumentException("GraphQL response exceeds the JSON bounds");
    }
  }

  private static void writeSequence(Iterator<?> values, StringBuilder out, int depth) {
    out.append('[');
    boolean first = true;
    while (values.hasNext()) {
      if (!first) {
        out.append(',');
      }
      first = false;
      writeValue(values.next(), out, depth + 1);
    }
    out.append(']');
  }

  private static boolean isFinite(String text) {
    try {
      var number = Double.parseDouble(text);
      return !Double.isNaN(number) && !Double.isInfinite(number);
    } catch (NumberFormatException e) {
      return false;
    }
  }

  private static void writeString(String text, StringBuilder out) {
    out.append('"');
    for (int i = 0; i < text.length(); i++) {
      char c = text.charAt(i);
      switch (c) {
        case '"' -> out.append("\\\"");
        case '\\' -> out.append("\\\\");
        case '\b' -> out.append("\\b");
        case '\f' -> out.append("\\f");
        case '\n' -> out.append("\\n");
        case '\r' -> out.append("\\r");
        case '\t' -> out.append("\\t");
        default -> {
          if (c < 0x20 || c == 0x7f) {
            out.append(String.format("\\u%04x", (int) c));
          } else {
            out.append(c);
          }
        }
      }
    }
    out.append('"');
  }

  /** Bounded recursive-descent reader; it never builds a value from client-chosen class names. */
  private static final class Parser {
    private final String text;
    private int index;

    Parser(String text) {
      this.text = text;
    }

    boolean atEnd() {
      return index == text.length();
    }

    IllegalArgumentException fail() {
      return new IllegalArgumentException("Malformed GraphQL request JSON");
    }

    void skipWhitespace() {
      while (index < text.length()) {
        char c = text.charAt(index);
        if (c != ' ' && c != '\t' && c != '\n' && c != '\r') {
          return;
        }
        index++;
      }
    }

    Object value(int depth) {
      if (depth > MAX_DEPTH) {
        throw fail();
      }
      skipWhitespace();
      if (atEnd()) {
        throw fail();
      }
      char c = text.charAt(index);
      return switch (c) {
        case '{' -> object(depth);
        case '[' -> array(depth);
        case '"' -> string();
        case 't' -> literal("true", Boolean.TRUE);
        case 'f' -> literal("false", Boolean.FALSE);
        case 'n' -> literal("null", null);
        default -> number();
      };
    }

    private Map<String, Object> object(int depth) {
      index++;
      var result = new LinkedHashMap<String, Object>();
      skipWhitespace();
      if (!atEnd() && text.charAt(index) == '}') {
        index++;
        return result;
      }
      while (true) {
        skipWhitespace();
        if (atEnd() || text.charAt(index) != '"') {
          throw fail();
        }
        var key = string();
        skipWhitespace();
        if (atEnd() || text.charAt(index) != ':') {
          throw fail();
        }
        index++;
        var parsed = value(depth + 1);
        if (result.containsKey(key)) {
          throw fail();
        }
        result.put(key, parsed);
        skipWhitespace();
        if (atEnd()) {
          throw fail();
        }
        char c = text.charAt(index++);
        if (c == '}') {
          return result;
        }
        if (c != ',') {
          throw fail();
        }
      }
    }

    private List<Object> array(int depth) {
      index++;
      var result = new ArrayList<Object>();
      skipWhitespace();
      if (!atEnd() && text.charAt(index) == ']') {
        index++;
        return result;
      }
      while (true) {
        result.add(value(depth + 1));
        skipWhitespace();
        if (atEnd()) {
          throw fail();
        }
        char c = text.charAt(index++);
        if (c == ']') {
          return result;
        }
        if (c != ',') {
          throw fail();
        }
      }
    }

    private String string() {
      index++;
      var out = new StringBuilder();
      while (index < text.length()) {
        char c = text.charAt(index++);
        if (c == '"') {
          return out.toString();
        }
        if (c < 0x20) {
          throw fail();
        }
        if (c != '\\') {
          out.append(c);
          continue;
        }
        if (index == text.length()) {
          throw fail();
        }
        char escape = text.charAt(index++);
        switch (escape) {
          case '"', '\\', '/' -> out.append(escape);
          case 'b' -> out.append('\b');
          case 'f' -> out.append('\f');
          case 'n' -> out.append('\n');
          case 'r' -> out.append('\r');
          case 't' -> out.append('\t');
          case 'u' -> {
            if (index + 4 > text.length()) {
              throw fail();
            }
            int code = 0;
            for (int i = 0; i < 4; i++) {
              int digit = Character.digit(text.charAt(index++), 16);
              if (digit < 0) {
                throw fail();
              }
              code = code * 16 + digit;
            }
            out.append((char) code);
          }
          default -> throw fail();
        }
      }
      throw fail();
    }

    private Object literal(String word, Object result) {
      if (!text.startsWith(word, index)) {
        throw fail();
      }
      index += word.length();
      return result;
    }

    private Object number() {
      int start = index;
      if (!atEnd() && text.charAt(index) == '-') {
        index++;
      }
      int digits = consumeDigits();
      if (digits == 0) {
        throw fail();
      }
      boolean floating = false;
      if (!atEnd() && text.charAt(index) == '.') {
        floating = true;
        index++;
        if (consumeDigits() == 0) {
          throw fail();
        }
      }
      if (!atEnd() && (text.charAt(index) == 'e' || text.charAt(index) == 'E')) {
        floating = true;
        index++;
        if (!atEnd() && (text.charAt(index) == '+' || text.charAt(index) == '-')) {
          index++;
        }
        if (consumeDigits() == 0) {
          throw fail();
        }
      }
      var number = text.substring(start, index);
      try {
        if (!floating) {
          return Long.valueOf(number);
        }
        double value = Double.parseDouble(number);
        if (!Double.isFinite(value)) {
          throw fail();
        }
        return value;
      } catch (NumberFormatException e) {
        throw fail();
      }
    }

    private int consumeDigits() {
      int start = index;
      while (index < text.length() && text.charAt(index) >= '0' && text.charAt(index) <= '9') {
        index++;
      }
      return index - start;
    }
  }
}
