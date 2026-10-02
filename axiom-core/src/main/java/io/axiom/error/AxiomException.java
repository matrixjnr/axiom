package io.axiom.error;

import io.axiom.http.HttpStatus;
import java.io.Serial;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Pattern;

/**
 * Base type for failures that the framework maps to a client-visible error response.
 * <p>
 * An Axiom exception carries only a final status (400-599), a short machine-readable code and,
 * for some subtypes, a list of field {@link Violation violations}. These are the only values
 * that reach the response body, so they must never contain client input, internal messages or
 * class names. Codes are restricted to lowercase ASCII letters, digits, {@code _}, {@code .} and
 * {@code -} (at most 64 characters, starting with a letter) and are validated at construction.
 * The exception message is derived from the status and code alone. The only response headers an
 * exception can add are {@code Allow}, {@code Retry-After} and {@code WWW-Authenticate}, built
 * by subtypes from typed values and validated so they cannot inject other header fields. A cause attached with
 * {@link #initCause(Throwable)} is available to logging but is never sent to clients.
 */
public abstract class AxiomException extends RuntimeException {
    @Serial private static final long serialVersionUID = 1L;
    private static final Pattern CODE = Pattern.compile("[a-z][a-z0-9_.-]{0,63}");
    private static final Set<String> HEADER_NAMES = Set.of("Allow", "Retry-After", "WWW-Authenticate");

    /** Final status. */
    private final int status;
    /** Safe machine-readable code. */
    private final String code;
    /** Validated response headers. */
    @SuppressWarnings("serial") // Always an unmodifiable TreeMap or Map.of(), both serializable.
    private final Map<String, String> headers;

    /**
     * Creates the exception with the status's default code, for example {@code not_found}.
     *
     * @param status client or server error status, 400 through 599
     * @throws IllegalArgumentException for an invalid status
     */
    protected AxiomException(int status) {
        this(status, defaultCode(status));
    }

    /**
     * Creates the exception.
     *
     * @param status client or server error status, 400 through 599
     * @param code safe machine-readable code
     * @throws IllegalArgumentException for an invalid status or code
     */
    protected AxiomException(int status, String code) {
        this(status, code, Map.of());
    }

    /**
     * Creates the exception with response headers.
     *
     * @param status client or server error status, 400 through 599
     * @param code safe machine-readable code
     * @param headers values for {@code Allow}, {@code Retry-After} or {@code WWW-Authenticate}
     *        only; visible ASCII, spaces and tabs, at most 1024 characters each
     * @throws IllegalArgumentException for an invalid status, code, header name or value
     */
    protected AxiomException(int status, String code, Map<String, String> headers) {
        super(status + " " + requireCode(code));
        if (status < 400 || status > 599) {
            throw new IllegalArgumentException("Error status must be between 400 and 599: " + status);
        }
        this.status = status;
        this.code = code;
        var copy = new TreeMap<String, String>(String.CASE_INSENSITIVE_ORDER);
        headers.forEach((name, value) -> {
            if (!HEADER_NAMES.contains(name)) {
                throw new IllegalArgumentException("Error responses may only add Allow, Retry-After or WWW-Authenticate");
            }
            copy.put(name, requireHeaderValue(value));
        });
        this.headers = copy.isEmpty() ? Map.of() : Collections.unmodifiableMap(copy);
    }

    private static String defaultCode(int status) {
        if (status < 400 || status > 599) {
            throw new IllegalArgumentException("Error status must be between 400 and 599: " + status);
        }
        return HttpStatus.defaultCode(status);
    }

    /**
     * Validates a header value: visible ASCII, spaces and tabs only, 1 to 1024 characters.
     *
     * @param value candidate value
     * @return the value
     * @throws IllegalArgumentException for control characters (including CR and LF), non-ASCII
     *         characters, or an empty or overlong value
     */
    static String requireHeaderValue(String value) {
        Objects.requireNonNull(value, "value");
        if (value.isEmpty() || value.length() > 1024
                || value.chars().anyMatch(c -> (c < 0x20 && c != '\t') || c > 0x7e)) {
            throw new IllegalArgumentException("Header values must be 1-1024 visible ASCII characters");
        }
        return value;
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

    /**
     * Returns the headers this exception adds to its error response.
     *
     * @return immutable, case-insensitive headers; empty for most exceptions
     */
    public final Map<String, String> headers() { return headers; }
}
