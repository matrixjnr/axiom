package com.jsgalactic.axiom.validation;

import static com.jsgalactic.axiom.validation.Rule.email;
import static com.jsgalactic.axiom.validation.Rule.length;
import static com.jsgalactic.axiom.validation.Rule.max;
import static com.jsgalactic.axiom.validation.Rule.maxLength;
import static com.jsgalactic.axiom.validation.Rule.min;
import static com.jsgalactic.axiom.validation.Rule.minLength;
import static com.jsgalactic.axiom.validation.Rule.notBlank;
import static com.jsgalactic.axiom.validation.Rule.notEmpty;
import static com.jsgalactic.axiom.validation.Rule.notNull;
import static com.jsgalactic.axiom.validation.Rule.oneOf;
import static com.jsgalactic.axiom.validation.Rule.pattern;
import static com.jsgalactic.axiom.validation.Rule.range;
import static com.jsgalactic.axiom.validation.Rule.size;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class RuleTest {
    @Test
    void notNullRejectsOnlyNull() {
        assertThat(notNull().code()).isEqualTo("not_null");
        assertThat(notNull().test(null)).isFalse();
        assertThat(notNull().test("")).isTrue();
    }

    @Test
    void notBlankRejectsNullEmptyAndWhitespace() {
        assertThat(notBlank().code()).isEqualTo("not_blank");
        for (var value : new String[] {null, "", " ", "\t\n", " "}) {
            assertThat(notBlank().test(value)).as("%s", value).isFalse();
        }
        assertThat(notBlank().test(" a ")).isTrue();
        assertThat(notBlank().test(new StringBuilder("x"))).isTrue();
    }

    @Test
    void notEmptyHandlesTextCollectionsMapsAndArrays() {
        assertThat(notEmpty().code()).isEqualTo("not_empty");
        for (var value : new Object[] {null, "", List.of(), Map.of(), new int[0], new String[0]}) {
            assertThat(notEmpty().test(value)).isFalse();
        }
        for (var value : new Object[] {" ", List.of(1), Map.of("k", 1), new int[1], 0}) {
            assertThat(notEmpty().test(value)).isTrue();
        }
    }

    @Test
    void lengthCountsCodePointsAndSkipsNull() {
        assertThat(length(2, 3).code()).isEqualTo("size");
        assertThat(length(2, 3).test("a")).isFalse();
        assertThat(length(2, 3).test("ab")).isTrue();
        assertThat(length(2, 3).test("abc")).isTrue();
        assertThat(length(2, 3).test("abcd")).isFalse();
        assertThat(length(2, 3).test(null)).isTrue();
        assertThat(maxLength(2).test("😀😀")).as("two emoji are two code points").isTrue();
        assertThat(maxLength(2).test("abc")).isFalse();
        assertThat(minLength(2).test("😀")).isFalse();
        assertThat(minLength(2).test("ab")).isTrue();
        assertThatThrownBy(() -> length(3, 2)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> length(-1, 2)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void sizeBoundsCollections() {
        assertThat(size(1, 2).code()).isEqualTo("size");
        assertThat(size(1, 2).test(List.of())).isFalse();
        assertThat(size(1, 2).test(List.of(1, 2))).isTrue();
        assertThat(size(1, 2).test(List.of(1, 2, 3))).isFalse();
        assertThat(size(1, 2).test(null)).isTrue();
        assertThatThrownBy(() -> size(2, 1)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void numericBoundsCompareExactlyAcrossNumberTypes() {
        assertThat(min(5).code()).isEqualTo("min");
        assertThat(max(5).code()).isEqualTo("max");
        assertThat(range(1, 10).code()).isEqualTo("range");
        assertThat(min(5).test(4)).isFalse();
        assertThat(min(5).test(5L)).isTrue();
        assertThat(max(5).test((short) 6)).isFalse();
        assertThat(max(5).test(new BigDecimal("5.0000000001"))).isFalse();
        assertThat(max(5).test(new BigDecimal("5.000"))).isTrue();
        assertThat(min(Long.MAX_VALUE).test(new BigInteger("9223372036854775808"))).isTrue();
        assertThat(max(Long.MAX_VALUE).test(new BigInteger("9223372036854775808"))).isFalse();
        assertThat(max(5).test(5.0000001d)).isFalse();
        assertThat(min(5).test(4.9999f)).isFalse();
        assertThat(max(5).test(Double.NaN)).isFalse();
        assertThat(min(5).test(Double.NaN)).isFalse();
        assertThat(max(5).test(Double.POSITIVE_INFINITY)).isFalse();
        assertThat(min(5).test(Double.NEGATIVE_INFINITY)).isFalse();
        assertThat(range(1, 10).test(new AtomicInteger(10))).isTrue();
        assertThat(range(1, 10).test(0)).isFalse();
        assertThat(range(1, 10).test(11)).isFalse();
        assertThat(range(1, 10).test(null)).isTrue();
        assertThatThrownBy(() -> range(2, 1)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void patternMatchesWholeInputAndRefusesOverlongInputWithoutMatching() {
        var sku = pattern("[A-Z]{3}-[0-9]{4}");
        assertThat(sku.code()).isEqualTo("pattern");
        assertThat(sku.test("ABC-1234")).isTrue();
        assertThat(sku.test("xABC-1234")).isFalse();
        assertThat(sku.test(null)).isTrue();
        assertThat(pattern("a*").test("a".repeat(Rule.DEFAULT_PATTERN_INPUT_LIMIT))).isTrue();
        assertThat(pattern("a*").test("a".repeat(Rule.DEFAULT_PATTERN_INPUT_LIMIT + 1))).isFalse();
        // A catastrophic-backtracking pattern stays fast because long inputs never reach the engine.
        var risky = pattern("(a+)+b", 8);
        assertThat(risky.test("a".repeat(100_000))).isFalse();
        assertThatThrownBy(() -> pattern("(")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> pattern("a", 0)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void emailChecksSyntaxOnly() {
        assertThat(email().code()).isEqualTo("email");
        for (var valid : new String[] {"ada@example.org", "a.b+tag@sub.example-mail.co", "x@a.io"}) {
            assertThat(email().test(valid)).as(valid).isTrue();
        }
        for (var invalid : new String[] {"", "ada", "@example.org", "ada@", "ada@@example.org", "a b@example.org",
                "ada@example", "ada@-example.org", "ada@example-.org", "ada@exa..mple.org", "ada@.org",
                "ada@example.org.", "a\n@example.org", "ädä@example.org", "x".repeat(65) + "@example.org",
                "a@" + "b".repeat(250) + ".org", "ada@exa_mple.org"}) {
            assertThat(email().test(invalid)).as(invalid).isFalse();
        }
        assertThat(email().test(null)).isTrue();
    }

    @Test
    void oneOfAcceptsOnlyListedValues() {
        var status = oneOf("open", "closed");
        assertThat(status.code()).isEqualTo("one_of");
        assertThat(status.test("open")).isTrue();
        assertThat(status.test("OPEN")).isFalse();
        assertThat(status.test(null)).isTrue();
        assertThatThrownBy(() -> oneOf()).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void customRulesUseSafeCodesAndSkipNullUnlessAskedNotTo() {
        var even = Rule.<Integer>check(value -> value % 2 == 0, "even");
        assertThat(even.code()).isEqualTo("even");
        assertThat(even.test(2)).isTrue();
        assertThat(even.test(3)).isFalse();
        assertThat(even.test(null)).isTrue();
        var present = Rule.<String>checkNullable(value -> value != null, "required");
        assertThat(present.test(null)).isFalse();
        assertThat(even.withCode("must_be_even").code()).isEqualTo("must_be_even");
        assertThat(even.withCode("must_be_even").test(3)).isFalse();
        assertThat(notBlank().withCode("required").test(null)).isFalse();
        for (var bad : new String[] {"Bad", "has space", "", "x".repeat(65), "<script>"}) {
            assertThatThrownBy(() -> Rule.check(value -> true, bad)).isInstanceOf(IllegalArgumentException.class);
        }
        assertThatThrownBy(() -> Rule.check(null, "x")).isInstanceOf(NullPointerException.class);
    }
}
