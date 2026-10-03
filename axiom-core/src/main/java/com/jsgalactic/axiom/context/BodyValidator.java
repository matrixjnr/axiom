package com.jsgalactic.axiom.context;

import com.jsgalactic.axiom.error.Violation;
import java.util.List;

/**
 * Checks a decoded request body for {@link Context#validatedBody(Class, BodyValidator)}.
 *
 * <p>The optional {@code axiom-validation} module's {@code Validator} extends this interface, so
 * its rule sets and the Jakarta Validation adapter can be passed directly; a lambda works too.
 *
 * <p><b>Thread safety and ownership.</b> One instance is typically shared by every request, so
 * implementations must be thread-safe and must not retain the value. Violations are sent to
 * clients: fields and codes must come from developer-defined names, never from the value or any
 * other client input.
 *
 * @param <T> type of value checked
 */
@FunctionalInterface
public interface BodyValidator<T> {
    /**
     * Checks a value.
     *
     * @param value value to check; never null when called by {@code validatedBody}, but other
     *        callers (such as {@code Validation.require}) may pass null
     * @return violations, empty when the value is valid; never null and without null elements
     *         (callers fail with {@link IllegalStateException} otherwise)
     */
    List<Violation> validate(T value);
}
