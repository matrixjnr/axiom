package com.jsgalactic.axiom.validation;

import java.util.List;
import java.util.Objects;

/**
 * What a built-in {@link Rule} checks, as plain data for tools that document or mirror a rule
 * set, such as an OpenAPI generator. It carries the rule's parameters and never a value.
 *
 * <p>Which components apply depends on the {@linkplain Kind kind}; unused components are 0,
 * {@code null} and an empty list. For {@link Kind#LENGTH} and {@link Kind#SIZE} a {@code max} of
 * {@link Integer#MAX_VALUE} means unbounded. For {@link Kind#RANGE} (the {@code min}, {@code max}
 * and {@code range} rules) an unbounded end is {@link Long#MIN_VALUE} or {@link Long#MAX_VALUE}.
 *
 * @param kind kind of check
 * @param min lower bound, inclusive
 * @param max upper bound, inclusive
 * @param pattern the Java regular expression of {@link Kind#PATTERN}, otherwise null
 * @param values the allowed values of {@link Kind#ONE_OF}, in declaration order
 */
public record Constraint(Kind kind, long min, long max, String pattern, List<String> values) {
    /** Kinds of built-in checks. */
    public enum Kind {
        /** The value must not be {@code null}. */
        NOT_NULL,
        /** Text must contain a non-whitespace character. */
        NOT_BLANK,
        /** Text, collections, maps and arrays must not be empty. */
        NOT_EMPTY,
        /** Text length in code points is between {@code min} and {@code max}. */
        LENGTH,
        /** Collection size is between {@code min} and {@code max}. */
        SIZE,
        /** A number is between {@code min} and {@code max}. */
        RANGE,
        /** The whole text matches {@code pattern}. */
        PATTERN,
        /** The text is an e-mail address. */
        EMAIL,
        /** The text equals one of {@code values}. */
        ONE_OF
    }

    /**
     * Creates a constraint.
     *
     * @param kind kind of check
     * @param min lower bound
     * @param max upper bound
     * @param pattern regular expression or null
     * @param values allowed values
     */
    public Constraint {
        Objects.requireNonNull(kind, "kind");
        values = List.copyOf(values);
    }
}
