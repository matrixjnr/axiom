package com.jsgalactic.axiom.error;

import java.util.Objects;
import java.util.regex.Pattern;

/**
 * One field-level problem reported to a client. Both values are sent in error responses, so
 * they are restricted to safe syntax: the field is a property path of at most 256 characters
 * using ASCII letters, digits, {@code _}, {@code -}, {@code .} and {@code [n]} indexes, and the
 * code follows the {@link AxiomException} code syntax. Never build either from client input.
 * <p>
 * An empty field means the violation concerns the whole validated value (for example a rule
 * that compares two properties, or a {@code null} body) rather than one property. Problem
 * responses leave the {@code field} member out for such a violation.
 *
 * @param field property path, for example {@code items[0].name}, or empty for the whole value
 * @param code safe machine-readable code
 */
public record Violation(String field, String code) {
    private static final Pattern FIELD =
            Pattern.compile("[A-Za-z_][A-Za-z0-9_-]*(\\[[0-9]{1,9}])*(\\.[A-Za-z_][A-Za-z0-9_-]*(\\[[0-9]{1,9}])*)*");

    /**
     * Validates both values.
     *
     * @param field property path
     * @param code safe machine-readable code
     * @throws IllegalArgumentException if either value has unsafe syntax
     * @throws NullPointerException if the field is null
     */
    public Violation {
        Objects.requireNonNull(field, "field");
        if (field.length() > 256 || !field.isEmpty() && !FIELD.matcher(field).matches()) {
            throw new IllegalArgumentException(
                    "Violation fields must be empty or property paths such as items[0].name");
        }
        AxiomException.requireCode(code);
    }
}
