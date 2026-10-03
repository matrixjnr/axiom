package com.jsgalactic.axiom.validation;

import com.jsgalactic.axiom.context.BodyValidator;
import com.jsgalactic.axiom.error.Violation;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;

/**
 * Checks a value and reports field violations.
 *
 * <p>Implementations must be thread-safe and must not retain the value. The returned list is
 * empty when the value is valid; otherwise it holds at most {@link Validation#MAX_VIOLATIONS}
 * violations whose fields are property paths built from developer-defined names and element
 * indexes and whose codes are fixed machine-readable strings. Violations are sent to clients, so
 * they must never contain the checked value or any other client input (no map keys, no
 * messages). An implementation that fails internally should throw an
 * {@link com.jsgalactic.axiom.error.AxiomException}, typically {@link com.jsgalactic.axiom.error.InternalServerErrorException},
 * whose response carries no internal detail; other exceptions reach the server's generic 500
 * handling.
 *
 * <p>A validator is also core's {@link BodyValidator}, so it can check request bodies directly:
 * {@code ctx.validatedBody(Order.class, ORDER)}.
 *
 * @param <T> type of value checked
 */
@FunctionalInterface
public interface Validator<T> extends BodyValidator<T> {
    /**
     * Checks a value.
     *
     * @param value value to check; may be null, but never null when called by
     *        {@code ctx.validatedBody}
     * @return immutable or caller-owned violations, empty when the value is valid; never null and
     *         without null elements (callers fail with {@link IllegalStateException} otherwise)
     */
    @Override
    List<Violation> validate(T value);

    /**
     * Returns a validator that runs this validator and then {@code other}, keeping the first
     * occurrence of each violation and stopping once {@link Validation#MAX_VIOLATIONS} are
     * collected (later validators are then not run).
     *
     * @param other validator to run second
     * @return combined validator returning an immutable list
     */
    default Validator<T> and(Validator<? super T> other) {
        Objects.requireNonNull(other, "other");
        return value -> {
            var collected = new LinkedHashSet<Violation>();
            for (var validator : List.<Validator<? super T>>of(this, other)) {
                if (collected.size() >= Validation.MAX_VIOLATIONS) {
                    break;
                }
                for (var violation : Validation.checked(validator.validate(value))) {
                    if (collected.size() >= Validation.MAX_VIOLATIONS) {
                        break;
                    }
                    collected.add(violation);
                }
            }
            return List.copyOf(collected);
        };
    }
}
