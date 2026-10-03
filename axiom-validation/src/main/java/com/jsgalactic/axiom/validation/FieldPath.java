package com.jsgalactic.axiom.validation;

import com.jsgalactic.axiom.error.Violation;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Immutable builder for violation field paths such as {@code items[2].name}.
 *
 * <p>Segments are developer-defined property names and element indexes, never client input.
 * Paths are bounded: once appending a segment would exceed {@link #MAX_LENGTH} characters, or an
 * index exceeds the nine digits a violation field allows, the path stops at the last ancestor that
 * fits and is marked {@linkplain #isTruncated() truncated}; later appends are ignored so a
 * violation is never attributed to an unrelated field. The empty path is reported as
 * {@value #ROOT}, for checks on the whole value.
 */
public final class FieldPath {
    /** Field reported for violations of the whole value. */
    public static final String ROOT = "_root";
    /** Longest field path, matching the limit of {@link Violation#field()}. */
    public static final int MAX_LENGTH = 256;
    private static final int MAX_INDEX = 999_999_999;
    private static final Pattern NAME = Pattern.compile("[A-Za-z_][A-Za-z0-9_-]*");
    private static final FieldPath ROOT_PATH = new FieldPath("", false);

    private final String path;
    private final boolean truncated;

    private FieldPath(String path, boolean truncated) {
        this.path = path;
        this.truncated = truncated;
    }

    /**
     * Returns the empty path of the validated value itself.
     *
     * @return root path
     */
    public static FieldPath root() { return ROOT_PATH; }

    /**
     * Tells whether a name may be used as one property segment: an ASCII letter or {@code _}
     * followed by ASCII letters, digits, {@code _} or {@code -}.
     *
     * @param name candidate segment
     * @return whether {@link #property(String)} accepts it
     */
    public static boolean isPropertyName(String name) {
        return name != null && name.length() <= MAX_LENGTH && NAME.matcher(name).matches();
    }

    /**
     * Appends a property segment.
     *
     * @param name property name, see {@link #isPropertyName(String)}
     * @return extended path, or this path if it is truncated or would become too long
     * @throws IllegalArgumentException if the name is not a safe property segment
     */
    public FieldPath property(String name) {
        Objects.requireNonNull(name, "name");
        if (!isPropertyName(name)) {
            throw new IllegalArgumentException("Property names use [A-Za-z_][A-Za-z0-9_-]*");
        }
        return extend(path.isEmpty() ? name : path + "." + name);
    }

    /**
     * Appends an element index to the last property.
     *
     * @param index zero-based element index
     * @return extended path, or a truncated path when this is the root, the index has more than
     *         nine digits, or the path would become too long
     * @throws IllegalArgumentException if the index is negative
     */
    public FieldPath index(int index) {
        if (index < 0) {
            throw new IllegalArgumentException("Indexes must not be negative");
        }
        if (truncated) {
            return this;
        }
        if (path.isEmpty() || index > MAX_INDEX) {
            return new FieldPath(path, true);
        }
        return extend(path + "[" + index + "]");
    }

    /**
     * Appends a field reported by a nested validator; {@value #ROOT} maps to this path.
     *
     * @param field a valid {@link Violation#field()} value
     * @return extended path, or this path marked truncated if the result would be too long
     * @throws IllegalArgumentException if the field is not a valid violation field
     */
    public FieldPath append(String field) {
        Objects.requireNonNull(field, "field");
        new Violation(field, "valid");
        if (field.equals(ROOT) || truncated) {
            return this;
        }
        return extend(path.isEmpty() ? field : path + "." + field);
    }

    private FieldPath extend(String candidate) {
        if (truncated) {
            return this;
        }
        if (candidate.length() > MAX_LENGTH) {
            return new FieldPath(path, true);
        }
        return new FieldPath(candidate, false);
    }

    /**
     * Tells whether this is the empty root path.
     *
     * @return true for the root
     */
    public boolean isRoot() { return path.isEmpty(); }

    /**
     * Tells whether a segment was dropped because of the length or index limit.
     *
     * @return true when the path stopped early
     */
    public boolean isTruncated() { return truncated; }

    /**
     * Returns the path as a violation field.
     *
     * @return the path, or {@value #ROOT} for the root
     */
    public String toField() { return path.isEmpty() ? ROOT : path; }

    @Override public boolean equals(Object other) {
        return other instanceof FieldPath that && path.equals(that.path) && truncated == that.truncated;
    }

    @Override public int hashCode() { return path.hashCode() * 31 + Boolean.hashCode(truncated); }

    @Override public String toString() { return toField(); }
}
