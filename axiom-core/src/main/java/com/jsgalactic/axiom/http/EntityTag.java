package com.jsgalactic.axiom.http;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * An HTTP entity tag, the validator carried by {@code ETag}, {@code If-Match}, {@code
 * If-None-Match} and {@code If-Range} (RFC 9110 section 8.8.3).
 *
 * <p>Parsing is strict: a tag is an optional {@code W/} prefix followed by a double-quoted opaque
 * value containing only the characters HTTP allows there. Whitespace, embedded quotes, control
 * characters and characters beyond Latin-1 are rejected. A wildcard {@code *} is not an entity tag;
 * it is handled by the condition that receives it.
 *
 * <p>Equality compares both the weakness flag and the opaque value. Use {@link
 * #strongMatches(EntityTag)} and {@link #weakMatches(EntityTag)} for the two comparison rules HTTP
 * defines: strong comparison requires both tags to be strong, weak comparison ignores the prefix.
 *
 * @param weak whether the tag carries the {@code W/} prefix
 * @param opaqueTag the characters between the quotes, never empty
 */
public record EntityTag(boolean weak, String opaqueTag) {
  /**
   * Validates the opaque value.
   *
   * @throws IllegalArgumentException if the opaque value is empty or contains a character that HTTP
   *     does not allow in it
   */
  public EntityTag {
    Objects.requireNonNull(opaqueTag, "opaqueTag");
    if (opaqueTag.isEmpty()) {
      throw new IllegalArgumentException("An entity tag cannot be empty");
    }
    for (int i = 0; i < opaqueTag.length(); i++) {
      if (!tagCharacter(opaqueTag.charAt(i))) {
        throw new IllegalArgumentException("Invalid character in an entity tag");
      }
    }
  }

  /**
   * Parses one entity tag. A list of tags, a wildcard, or surrounding whitespace is not one tag and
   * yields empty.
   *
   * @param value the header value, or null
   * @return the tag, or empty when the value is not exactly one well-formed tag
   */
  public static Optional<EntityTag> parse(String value) {
    var tags = parseList(value);
    return tags.isPresent() && tags.get().size() == 1
        ? Optional.of(tags.get().getFirst())
        : Optional.empty();
  }

  /**
   * Parses a comma-separated list of entity tags. Optional whitespace may surround a comma but not
   * a tag. A quoted opaque value may itself contain a comma, so elements are read by their quotes
   * rather than by splitting the string. An empty list, an empty element, a wildcard or a malformed
   * tag yields empty rather than a partial list.
   *
   * @param value the header value, or null
   * @return the tags in order, or empty when the whole value is not a valid list
   */
  static Optional<List<EntityTag>> parseList(String value) {
    if (value == null) {
      return Optional.empty();
    }
    var tags = new ArrayList<EntityTag>();
    int i = 0;
    while (true) {
      // The first element has no leading whitespace; elements after a comma may.
      if (i == value.length()) {
        return tags.isEmpty() ? Optional.empty() : Optional.of(List.copyOf(tags));
      }
      boolean weak = false;
      if (value.startsWith("W/", i)) {
        weak = true;
        i += 2;
      }
      if (i == value.length() || value.charAt(i) != '"') {
        return Optional.empty();
      }
      int end = i + 1;
      while (end < value.length() && value.charAt(end) != '"') {
        if (!tagCharacter(value.charAt(end))) {
          return Optional.empty();
        }
        end++;
      }
      if (end == value.length()) {
        return Optional.empty();
      }
      tags.add(new EntityTag(weak, value.substring(i + 1, end)));
      i = end + 1;
      while (i < value.length() && whitespace(value.charAt(i))) {
        i++;
      }
      if (i == value.length()) {
        break;
      }
      if (value.charAt(i) != ',') {
        return Optional.empty();
      }
      i++;
      while (i < value.length() && whitespace(value.charAt(i))) {
        i++;
      }
      if (i == value.length()) {
        return Optional.empty();
      } // Trailing comma.
    }
    // Reaching the break means the value ended with a tag, never with a comma.
    return Optional.of(List.copyOf(tags));
  }

  /**
   * Compares two tags with the strong rule: neither may be weak and the opaque values must be
   * identical.
   *
   * @param other the tag to compare with
   * @return whether they match strongly
   */
  public boolean strongMatches(EntityTag other) {
    return !weak && !other.weak && opaqueTag.equals(other.opaqueTag);
  }

  /**
   * Compares two tags with the weak rule: only the opaque values must be identical, so a weak tag
   * can match a strong one.
   *
   * @param other the tag to compare with
   * @return whether they match weakly
   */
  public boolean weakMatches(EntityTag other) {
    return opaqueTag.equals(other.opaqueTag);
  }

  @Override
  public String toString() {
    return (weak ? "W/" : "") + '"' + opaqueTag + '"';
  }

  private static boolean tagCharacter(char c) {
    return c == 0x21 || c >= 0x23 && c <= 0x7e || c >= 0x80 && c <= 0xff;
  }

  private static boolean whitespace(char c) {
    return c == ' ' || c == '\t';
  }
}
