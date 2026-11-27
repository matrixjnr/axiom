package com.jsgalactic.axiom.http;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * The satisfiable ranges of a {@code Range: bytes=...} header field (RFC 9110 section 14.2), in the
 * order the client sent them.
 *
 * <p>Parsing is strict and bounded: only the {@code bytes} unit is accepted, a range list holds at
 * most {@value #MAX_RANGES} specifications, every number is ASCII digits, and whitespace is allowed
 * only around commas. A specification whose first byte is past the end of the representation, or a
 * suffix of zero, is unsatisfiable and is dropped; if none remain, parsing yields empty. Numbers
 * too large for a {@code long} are clamped rather than overflowing, so they become unsatisfiable
 * instead of wrapping around. Open-ended ranges are clamped to the representation's length.
 *
 * @param ranges the ranges to send, in request order; at least one and at most {@value #MAX_RANGES}
 */
public record ByteRanges(List<ByteRange> ranges) {
  /** Most specifications a parsed {@code Range} field may contain: eight. */
  public static final int MAX_RANGES = 8;

  /**
   * Validates the bound and takes an immutable copy.
   *
   * @throws IllegalArgumentException if there are no ranges or more than {@link #MAX_RANGES}
   */
  public ByteRanges {
    Objects.requireNonNull(ranges, "ranges");
    if (ranges.isEmpty() || ranges.size() > MAX_RANGES) {
      throw new IllegalArgumentException("A range list must hold one to " + MAX_RANGES + " ranges");
    }
    ranges = List.copyOf(ranges);
  }

  /**
   * Parses a {@code Range} field against a representation of the given length.
   *
   * @param header the complete {@code Range} field value, or null when absent
   * @param length total length of the representation in bytes, positive
   * @return the satisfiable ranges, or empty when the header is absent, malformed, has too many
   *     specifications, or has no satisfiable range
   */
  public static Optional<ByteRanges> parse(String header, long length) {
    if (header == null
        || length <= 0
        || header.length() <= 6
        || !header.regionMatches(true, 0, "bytes=", 0, 6)) {
      return Optional.empty();
    }
    return new Parser(header, length).parse();
  }

  /** One bounded, non-overflowing parse of a single header value. */
  private static final class Parser {
    private final String text;
    private final long representationLength;
    private final List<ByteRange> ranges = new ArrayList<>();
    private int index = 6;
    private int specifications;

    private Parser(String text, long representationLength) {
      this.text = text;
      this.representationLength = representationLength;
    }

    private Optional<ByteRanges> parse() {
      while (true) {
        if (++specifications > MAX_RANGES) {
          return Optional.empty();
        }
        if (!specification()) {
          return Optional.empty();
        }
        int beforeWhitespace = index;
        while (index < text.length() && whitespace(text.charAt(index))) {
          index++;
        }
        if (index == text.length()) {
          return index == beforeWhitespace
              ? ranges.isEmpty() ? Optional.empty() : Optional.of(new ByteRanges(ranges))
              : Optional.empty();
        }
        if (text.charAt(index) != ',') {
          return Optional.empty();
        }
        index++;
        while (index < text.length() && whitespace(text.charAt(index))) {
          index++;
        }
        if (index == text.length()) {
          return Optional.empty();
        } // Trailing comma.
      }
    }

    /** Reads one range specification and adds it when it can be satisfied. */
    private boolean specification() {
      if (index >= text.length()) {
        return false;
      }
      if (text.charAt(index) == '-') {
        index++;
        long suffix = digits();
        if (suffix < 0) {
          return false;
        }
        if (suffix > 0) {
          add(
              suffix >= representationLength ? 0 : representationLength - suffix,
              representationLength - 1);
        }
        return true;
      }
      long first = digits();
      if (first < 0 || index >= text.length() || text.charAt(index) != '-') {
        return false;
      }
      index++;
      long last = representationLength - 1;
      if (index < text.length() && digit(text.charAt(index))) {
        last = digits();
        if (last < 0 || last < first) {
          return false;
        }
        last = Math.min(last, representationLength - 1);
      }
      if (first < representationLength) {
        add(first, last);
      }
      return true;
    }

    private void add(long first, long last) {
      ranges.add(new ByteRange(first, last));
    }

    /** Reads ASCII digits, clamping at {@code Long.MAX_VALUE}; -1 means none. */
    private long digits() {
      if (index >= text.length() || !digit(text.charAt(index))) {
        return -1;
      }
      long value = 0;
      while (index < text.length() && digit(text.charAt(index))) {
        int next = text.charAt(index++) - '0';
        if (value > (Long.MAX_VALUE - next) / 10) {
          while (index < text.length() && digit(text.charAt(index))) {
            index++;
          }
          return Long.MAX_VALUE;
        }
        value = value * 10 + next;
      }
      return value;
    }

    private static boolean digit(char c) {
      return c >= '0' && c <= '9';
    }

    private static boolean whitespace(char c) {
      return c == ' ' || c == '\t';
    }
  }
}
