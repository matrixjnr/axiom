package io.axiom.validation;

import io.axiom.error.ValidationException;
import io.axiom.error.Violation;
import java.util.List;
import java.util.Objects;

/**
 * Entry point for checking values in handlers.
 *
 * <pre>{@code
 * var command = Validation.require(CREATE_NOTE, parse(ctx));
 * }</pre>
 *
 * <p>A failed check throws core's {@link ValidationException}, which the server maps to a 422
 * {@code application/problem+json} response listing only each violation's field path and code.
 */
public final class Validation {
    /** Most violations reported for one value; the same limit {@link ValidationException} enforces. */
    public static final int MAX_VIOLATIONS = 100;

    private Validation() {}

    /**
     * Checks a value and returns it when valid.
     *
     * @param validator validator to run
     * @param value value to check; may be null if the validator accepts null
     * @param <T> value type
     * @return {@code value}
     * @throws ValidationException with at most {@link #MAX_VIOLATIONS} violations, in the order the
     *         validator reported them, when the value is invalid
     * @throws IllegalStateException if the validator returns null or a null violation
     */
    public static <T> T require(Validator<? super T> validator, T value) {
        Objects.requireNonNull(validator, "validator");
        var violations = checked(validator.validate(value));
        if (!violations.isEmpty()) {
            throw new ValidationException(
                    violations.size() > MAX_VIOLATIONS ? violations.subList(0, MAX_VIOLATIONS) : violations);
        }
        return value;
    }

    static List<Violation> checked(List<Violation> violations) {
        if (violations == null) {
            throw new IllegalStateException("Validator returned null instead of a violation list");
        }
        for (var violation : violations) {
            if (violation == null) {
                throw new IllegalStateException("Validator returned a null violation");
            }
        }
        return violations;
    }
}
