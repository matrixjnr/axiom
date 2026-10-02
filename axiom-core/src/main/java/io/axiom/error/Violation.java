package io.axiom.error;

import java.util.Objects;
import java.util.regex.Pattern;

/**
 * One field-level problem reported to a client. Both values are sent in error responses, so
 * they are restricted to safe syntax: the field is a property path of at most 256 characters
 * using ASCII letters, digits, {@code _}, {@code -}, {@code .} and {@code [n]} indexes, and the
 * code follows the {@link AxiomException} code syntax. Never build either from client input.
 *
 * @param field property path, for example {@code items[0].name}
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
     */
    public Violation {
        Objects.requireNonNull(field, "field");
        if (field.length() > 256 || !FIELD.matcher(field).matches()) {
            throw new IllegalArgumentException("Violation fields must be property paths such as items[0].name");
        }
        AxiomException.requireCode(code);
    }
}
