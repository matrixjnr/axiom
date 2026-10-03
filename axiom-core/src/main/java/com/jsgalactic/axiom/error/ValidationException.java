package com.jsgalactic.axiom.error;

import java.io.Serial;
import java.util.List;

/**
 * A syntactically valid request whose content breaks application rules (422). The framework
 * does not throw this yet; applications may throw it with field violations.
 */
public class ValidationException extends UnprocessableContentException {
    @Serial private static final long serialVersionUID = 1L;
    /** Immutable violations. */
    @SuppressWarnings("serial") // List.copyOf returns a serializable list.
    private final List<Violation> violations;

    /**
     * Creates the exception with code {@code validation_failed}.
     *
     * @param violations at most 100 field violations
     * @throws IllegalArgumentException if more than 100 violations are supplied
     */
    public ValidationException(List<Violation> violations) {
        super("validation_failed");
        this.violations = List.copyOf(violations);
        if (this.violations.size() > 100) {
            throw new IllegalArgumentException("At most 100 violations may be reported");
        }
    }

    @Override public List<Violation> violations() { return violations; }
}
