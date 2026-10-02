package io.axiom.error;

import java.io.Serial;
import java.util.List;
import java.util.Optional;

/**
 * A request body that is missing, malformed or does not match the requested type (400).
 * Codecs report only a safe code and, when known, the declared property path; parser messages
 * and input fragments are never carried.
 */
public class DecodeException extends AxiomException {
    @Serial private static final long serialVersionUID = 1L;
    /** Optional property path; null when unknown. */
    private final String field;

    /**
     * Creates the exception without a field.
     *
     * @param code safe machine-readable code, for example {@code malformed_body}
     */
    public DecodeException(String code) {
        this(code, null);
    }

    /**
     * Creates the exception for a property path.
     *
     * @param code safe machine-readable code, for example {@code type_mismatch}
     * @param field declared property path, or null when unknown
     * @throws IllegalArgumentException if the field is not a safe property path
     */
    public DecodeException(String code, String field) {
        super(400, code);
        if (field != null) { new Violation(field, code); }
        this.field = field;
    }

    /**
     * Returns the property path that failed, when known.
     *
     * @return property path
     */
    public Optional<String> field() { return Optional.ofNullable(field); }

    /**
     * Returns one violation for the field, or none.
     *
     * @return immutable violations
     */
    @Override public List<Violation> violations() {
        return field == null ? List.of() : List.of(new Violation(field, code()));
    }
}
