package com.jsgalactic.axiom.validation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import com.jsgalactic.axiom.error.ValidationException;
import com.jsgalactic.axiom.error.Violation;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

class ValidationTest {
    @Test
    void returnsTheValueWhenThereAreNoViolations() {
        Validator<String> accepting = value -> List.of();
        assertThat(Validation.require(accepting, "ok")).isEqualTo("ok");
    }

    @Test
    void throwsTheCoreValidationExceptionWithFieldsAndCodes() {
        Validator<String> rejecting = value -> List.of(new Violation("name", "not_blank"));
        var failure = catchThrowableOfType(ValidationException.class, () -> Validation.require(rejecting, " "));
        assertThat(failure.status()).isEqualTo(422);
        assertThat(failure.code()).isEqualTo("validation_failed");
        assertThat(failure.violations()).containsExactly(new Violation("name", "not_blank"));
        assertThat(failure.getMessage()).isEqualTo("422 validation_failed");
    }

    @Test
    void capsViolationsAtTheLimitTheCoreExceptionAccepts() {
        Validator<Object> noisy = value -> IntStream.range(0, 250)
                .mapToObj(i -> new Violation("items[" + i + "]", "size")).toList();
        var failure = catchThrowableOfType(ValidationException.class, () -> Validation.require(noisy, "x"));
        assertThat(failure.violations()).hasSize(Validation.MAX_VIOLATIONS).hasSize(100);
        assertThat(failure.violations().get(99).field()).isEqualTo("items[99]");
    }

    @Test
    void rejectsValidatorsThatBreakTheContract() {
        Validator<Object> broken = value -> null;
        assertThatThrownBy(() -> Validation.require(broken, "x")).isInstanceOf(IllegalStateException.class);
        var withNull = new ArrayList<Violation>();
        withNull.add(null);
        assertThatThrownBy(() -> Validation.require(value -> withNull, "x")).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> Validation.require(null, "x")).isInstanceOf(NullPointerException.class);
    }

    @Test
    void combinesValidatorsInOrderWithoutDuplicatesAndWithinTheCap() {
        Validator<String> first = value -> List.of(new Violation("a", "x"), new Violation("b", "y"));
        Validator<Object> second = value -> List.of(new Violation("b", "y"), new Violation("c", "z"));
        assertThat(first.and(second).validate("v"))
                .containsExactly(new Violation("a", "x"), new Violation("b", "y"), new Violation("c", "z"));
        Validator<Object> many = value -> IntStream.range(0, 80).mapToObj(i -> new Violation("f" + i, "x")).toList();
        Validator<Object> more = value -> IntStream.range(0, 80).mapToObj(i -> new Violation("g" + i, "x")).toList();
        var combined = many.and(more).validate("v");
        assertThat(combined).hasSize(100);
        assertThatThrownBy(() -> combined.add(new Violation("h", "x"))).isInstanceOf(UnsupportedOperationException.class);
        var calls = new int[1];
        Validator<Object> counting = value -> { calls[0]++; return List.of(); };
        assertThat(many.and(more).and(counting).validate("v")).hasSize(100);
        assertThat(calls[0]).as("validators after the cap is reached are skipped").isZero();
    }
}
