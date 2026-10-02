package io.axiom.error;

import java.io.Serial;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Base type for failures that the framework maps to a client-visible error response.
 * <p>
 * An Axiom exception carries only a final status (400-599), a short machine-readable code and,
 * for some subtypes, a list of field {@link Violation violations}. These are the only values
 * that reach the response body, so they must never contain client input, internal messages or
 * class names. Codes are restricted to lowercase ASCII letters, digits, {@code _}, {@code .} and
 * {@code -} (at most 64 characters, starting with a letter) and are validated at construction.
 * The exception message is derived from the status and code alone. A cause attached with
 * {@link #initCause(Throwable)} is available to logging but is never sent to clients.
 */
public abstract class AxiomException extends RuntimeException {
    @Serial private static final long serialVersionUID = 1L;
    private static final Pattern CODE = Pattern.compile("[a-z][a-z0-9_.-]{0,63}");

    /** Final status. */
    private final int status;
    /** Safe machine-readable code. */
    private final String code;

    /**
     * Creates the exception.
     *
     * @param status client or server error status, 400 through 599
     * @param code safe machine-readable code
     * @throws IllegalArgumentException for an invalid status or code
     */
    protected AxiomException(int status, String code) {
        super(status + " " + requireCode(code));
        if (status < 400 || status > 599) {
            throw new IllegalArgumentException("Error status must be between 400 and 599: " + status);
        }
        this.status = status;
        this.code = code;
    }

    /**
     * Validates a machine-readable error code.
     *
     * @param code candidate code
     * @return the code
     * @throws IllegalArgumentException if the code does not match the safe code syntax
     */
    static String requireCode(String code) {
        Objects.requireNonNull(code, "code");
        if (!CODE.matcher(code).matches()) {
            throw new IllegalArgumentException("Error codes use [a-z][a-z0-9_.-]{0,63}");
        }
        return code;
    }

    /**
     * Returns the response status.
     *
     * @return status between 400 and 599
     */
    public final int status() { return status; }

    /**
     * Returns the safe machine-readable code sent to clients.
     *
     * @return error code
     */
    public final String code() { return code; }

    /**
     * Returns field-level details sent to clients; empty unless a subtype provides them.
     *
     * @return immutable violations
     */
    public List<Violation> violations() { return List.of(); }
}
