package io.axiom.validation;

import io.axiom.error.Violation;
import java.lang.reflect.Array;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.Collection;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Predicate;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * An immutable check of one value with a fixed violation code.
 *
 * <p>Rules are used with {@link Rules} to describe a type's constraints without annotations. Most
 * rules accept {@code null} so that presence is stated explicitly with {@link #notNull()};
 * {@link #notNull()}, {@link #notBlank()} and {@link #notEmpty()} reject it. Codes are fixed,
 * validated when the rule is created, and never derived from the checked value. Rules are
 * thread-safe as long as their predicates are.
 *
 * @param <T> type of value checked
 */
public final class Rule<T> {
    /** Longest input {@link #pattern(String)} hands to the regular expression engine. */
    public static final int DEFAULT_PATTERN_INPUT_LIMIT = 1000;
    private static final int MAX_EMAIL = 254;

    private final Predicate<? super T> test;
    private final String code;
    private final boolean nullable;

    private Rule(Predicate<? super T> test, String code, boolean nullable) {
        this.test = Objects.requireNonNull(test, "test");
        this.code = requireCode(code);
        this.nullable = nullable;
    }

    static String requireCode(String code) {
        Objects.requireNonNull(code, "code");
        new Violation(FieldPath.ROOT, code);
        return code;
    }

    /**
     * Creates a custom rule; {@code null} values pass without calling the predicate.
     *
     * @param test predicate that returns true for valid, non-null values
     * @param code violation code, {@code [a-z][a-z0-9_.-]{0,63}}
     * @param <T> value type
     * @return the rule
     * @throws IllegalArgumentException if the code is not a safe code
     */
    public static <T> Rule<T> check(Predicate<? super T> test, String code) {
        return new Rule<>(test, code, false);
    }

    /**
     * Creates a custom rule whose predicate also decides whether {@code null} is valid.
     *
     * @param test predicate that returns true for valid values, including null if allowed
     * @param code violation code, {@code [a-z][a-z0-9_.-]{0,63}}
     * @param <T> value type
     * @return the rule
     * @throws IllegalArgumentException if the code is not a safe code
     */
    public static <T> Rule<T> checkNullable(Predicate<? super T> test, String code) {
        return new Rule<>(test, code, true);
    }

    /**
     * Returns this rule with another code.
     *
     * @param code violation code, {@code [a-z][a-z0-9_.-]{0,63}}
     * @return a rule with the same check
     */
    public Rule<T> withCode(String code) {
        return new Rule<>(test, code, nullable);
    }

    /**
     * Returns the code reported when the check fails.
     *
     * @return safe machine-readable code
     */
    public String code() { return code; }

    /**
     * Checks a value.
     *
     * @param value value to check
     * @return true if the value is valid
     */
    public boolean test(T value) {
        return value == null && !nullable || test.test(value);
    }

    /**
     * Rejects {@code null}. Code {@code not_null}.
     *
     * @return the rule
     */
    public static Rule<Object> notNull() {
        return checkNullable(Objects::nonNull, "not_null");
    }

    /**
     * Rejects {@code null} and text that is empty or only whitespace. Code {@code not_blank}.
     *
     * @return the rule
     */
    public static Rule<CharSequence> notBlank() {
        return checkNullable(value -> value != null && !value.toString().isBlank(), "not_blank");
    }

    /**
     * Rejects {@code null} and empty text, collections, maps and arrays; other non-null values
     * pass. Code {@code not_empty}.
     *
     * @return the rule
     */
    public static Rule<Object> notEmpty() {
        return checkNullable(value -> {
            if (value == null) {
                return false;
            }
            if (value instanceof CharSequence text) {
                return !text.isEmpty();
            }
            if (value instanceof Collection<?> collection) {
                return !collection.isEmpty();
            }
            if (value instanceof Map<?, ?> map) {
                return !map.isEmpty();
            }
            return !value.getClass().isArray() || Array.getLength(value) > 0;
        }, "not_empty");
    }

    /**
     * Bounds text length in Unicode code points, inclusive. Code {@code size}.
     *
     * @param min minimum length, at least 0
     * @param max maximum length, at least {@code min}
     * @return the rule
     */
    public static Rule<CharSequence> length(int min, int max) {
        requireBounds(min, max);
        return check(value -> {
            var chars = value.length();
            if (chars < min || chars > 2L * max) {
                return false;
            }
            var codePoints = Character.codePointCount(value, 0, chars);
            return codePoints >= min && codePoints <= max;
        }, "size");
    }

    /**
     * Requires at least {@code min} code points. Code {@code size}.
     *
     * @param min minimum length, at least 0
     * @return the rule
     */
    public static Rule<CharSequence> minLength(int min) {
        return length(min, Integer.MAX_VALUE);
    }

    /**
     * Allows at most {@code max} code points. Code {@code size}.
     *
     * @param max maximum length, at least 0
     * @return the rule
     */
    public static Rule<CharSequence> maxLength(int max) {
        return length(0, max);
    }

    /**
     * Bounds the number of elements in a collection, inclusive. Code {@code size}.
     *
     * @param min minimum size, at least 0
     * @param max maximum size, at least {@code min}
     * @return the rule
     */
    public static Rule<Collection<?>> size(int min, int max) {
        requireBounds(min, max);
        return check(value -> value.size() >= min && value.size() <= max, "size");
    }

    /**
     * Requires a number of at least {@code min}, compared exactly; NaN fails. Code {@code min}.
     *
     * @param min inclusive lower bound
     * @return the rule
     */
    public static Rule<Number> min(long min) {
        return check(value -> !isNaN(value) && compare(value, min) >= 0, "min");
    }

    /**
     * Requires a number of at most {@code max}, compared exactly; NaN fails. Code {@code max}.
     *
     * @param max inclusive upper bound
     * @return the rule
     */
    public static Rule<Number> max(long max) {
        return check(value -> !isNaN(value) && compare(value, max) <= 0, "max");
    }

    /**
     * Requires a number between {@code min} and {@code max}, inclusive; NaN fails. Code
     * {@code range}.
     *
     * @param min inclusive lower bound
     * @param max inclusive upper bound, at least {@code min}
     * @return the rule
     */
    public static Rule<Number> range(long min, long max) {
        if (min > max) {
            throw new IllegalArgumentException("min must not exceed max");
        }
        return check(value -> !isNaN(value) && compare(value, min) >= 0 && compare(value, max) <= 0, "range");
    }

    /**
     * Requires the whole text to match a regular expression, refusing input longer than
     * {@value #DEFAULT_PATTERN_INPUT_LIMIT} characters without matching it. Code {@code pattern}.
     *
     * @param regex Java regular expression, compiled once
     * @return the rule
     * @throws IllegalArgumentException if the expression is invalid
     * @see #pattern(String, int)
     */
    public static Rule<CharSequence> pattern(String regex) {
        return pattern(regex, DEFAULT_PATTERN_INPUT_LIMIT);
    }

    /**
     * Requires the whole text to match a regular expression. Code {@code pattern}.
     *
     * <p>Java's regular expression engine backtracks, so expressions with nested or overlapping
     * repetition such as {@code (a+)+b} can take exponential time on crafted input. The expression
     * is compiled once; input longer than {@code maxInputLength} characters fails without
     * reaching the engine. Prefer simple, anchored-by-construction expressions with a small limit
     * and never build expressions from client input.
     *
     * @param regex Java regular expression, compiled once
     * @param maxInputLength longest input matched, at least 1
     * @return the rule
     * @throws IllegalArgumentException if the expression is invalid or the limit is below 1
     */
    public static Rule<CharSequence> pattern(String regex, int maxInputLength) {
        Objects.requireNonNull(regex, "regex");
        if (maxInputLength < 1) {
            throw new IllegalArgumentException("maxInputLength must be at least 1");
        }
        Pattern compiled;
        try {
            compiled = Pattern.compile(regex);
        } catch (PatternSyntaxException e) {
            throw new IllegalArgumentException("Invalid regular expression", e);
        }
        return check(value -> value.length() <= maxInputLength && compiled.matcher(value).matches(), "pattern");
    }

    /**
     * Checks e-mail address syntax only, without a regular expression: one {@code @}, a local part
     * of 1 to 64 printable ASCII characters other than spaces and {@code "(),:;<>@[\]}, and a
     * domain of at least two dot-separated labels of ASCII letters, digits and inner hyphens; at
     * most 254 characters. Quoted local parts, IP literals and internationalized addresses are
     * rejected. It does not check that the address exists or can receive mail. Code {@code email}.
     *
     * @return the rule
     */
    public static Rule<CharSequence> email() {
        return check(Rule::isEmail, "email");
    }

    /**
     * Requires text equal to one of the listed values. Code {@code one_of}.
     *
     * @param values allowed values, at least one
     * @return the rule
     */
    public static Rule<CharSequence> oneOf(String... values) {
        if (values.length == 0) {
            throw new IllegalArgumentException("At least one value is required");
        }
        var allowed = Set.of(values);
        return check(value -> allowed.contains(value.toString()), "one_of");
    }

    private static void requireBounds(int min, int max) {
        if (min < 0 || max < min) {
            throw new IllegalArgumentException("Bounds must satisfy 0 <= min <= max");
        }
    }

    private static int compare(Number value, long bound) {
        if (value instanceof Double || value instanceof Float) {
            var number = value.doubleValue();
            if (Double.isInfinite(number)) {
                return number > 0 ? 1 : -1;
            }
            return new BigDecimal(number).compareTo(BigDecimal.valueOf(bound));
        }
        if (value instanceof BigDecimal decimal) {
            return decimal.compareTo(BigDecimal.valueOf(bound));
        }
        if (value instanceof BigInteger integer) {
            return integer.compareTo(BigInteger.valueOf(bound));
        }
        if (value instanceof Long || value instanceof Integer || value instanceof Short || value instanceof Byte
                || value instanceof java.util.concurrent.atomic.AtomicInteger
                || value instanceof java.util.concurrent.atomic.AtomicLong) {
            return Long.compare(value.longValue(), bound);
        }
        return Double.compare(value.doubleValue(), bound);
    }

    private static boolean isNaN(Number value) {
        return !(value instanceof BigDecimal || value instanceof BigInteger) && Double.isNaN(value.doubleValue());
    }

    private static boolean isEmail(CharSequence value) {
        var length = value.length();
        if (length > MAX_EMAIL) {
            return false;
        }
        var at = -1;
        for (int i = 0; i < length; i++) {
            if (value.charAt(i) == '@') {
                if (at >= 0) {
                    return false;
                }
                at = i;
            }
        }
        if (at < 1 || at > 64 || at == length - 1) {
            return false;
        }
        for (int i = 0; i < at; i++) {
            var c = value.charAt(i);
            if (c <= ' ' || c > '~' || "\"(),:;<>[\\]".indexOf(c) >= 0) {
                return false;
            }
        }
        var labels = 0;
        var labelStart = at + 1;
        for (int i = at + 1; i <= length; i++) {
            if (i == length || value.charAt(i) == '.') {
                if (!isLabel(value, labelStart, i)) {
                    return false;
                }
                labels++;
                labelStart = i + 1;
            }
        }
        return labels >= 2;
    }

    private static boolean isLabel(CharSequence value, int start, int end) {
        if (end <= start || end - start > 63 || value.charAt(start) == '-' || value.charAt(end - 1) == '-') {
            return false;
        }
        for (int i = start; i < end; i++) {
            var c = value.charAt(i);
            if (!(c >= 'a' && c <= 'z' || c >= 'A' && c <= 'Z' || c >= '0' && c <= '9' || c == '-')) {
                return false;
            }
        }
        return true;
    }
}
