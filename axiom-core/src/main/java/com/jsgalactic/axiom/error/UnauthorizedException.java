package com.jsgalactic.axiom.error;

import java.io.Serial;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * A request without valid credentials (401). The response carries the caller-supplied
 * {@code WWW-Authenticate} challenge, which must be a constant chosen by the application
 * (for example {@code Bearer realm="api"}), never a value taken from the request.
 */
public class UnauthorizedException extends AxiomException {
    @Serial private static final long serialVersionUID = 1L;
    private static final Pattern CHALLENGE = Pattern.compile("[!#$%&'*+.^_`|~0-9A-Za-z-]+( [ -~]*)?");

    /**
     * Creates the exception with code {@code unauthorized}.
     *
     * @param challenge WWW-Authenticate value: an auth scheme, optionally followed by a space and
     *        parameters, in visible ASCII
     * @throws IllegalArgumentException for an invalid or unsafe challenge
     */
    public UnauthorizedException(String challenge) {
        this(challenge, "unauthorized");
    }

    /**
     * Creates the exception with a specific safe code.
     *
     * @param challenge WWW-Authenticate value
     * @param code machine-readable code
     * @throws IllegalArgumentException for an invalid or unsafe challenge
     */
    public UnauthorizedException(String challenge, String code) {
        super(401, code, Map.of("WWW-Authenticate", requireChallenge(challenge)));
    }

    private static String requireChallenge(String challenge) {
        requireHeaderValue(challenge);
        if (!CHALLENGE.matcher(challenge).matches()) {
            throw new IllegalArgumentException("A challenge starts with an auth scheme token");
        }
        return challenge;
    }
}
