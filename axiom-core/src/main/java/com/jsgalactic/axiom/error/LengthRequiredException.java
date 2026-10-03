package com.jsgalactic.axiom.error;

import java.io.Serial;

/**
 * An endpoint's refusal of a request that does not declare its length (411). The HTTP listener
 * never throws it: a request without Content-Length or Transfer-Encoding has an empty body
 * (RFC 9112 section 6.3), and chunked bodies are accepted. A handler that wants only bodies with
 * a declared length, for example an upload endpoint that refuses chunked content, checks the
 * header and throws it:
 *
 * <pre>{@code
 * app.post("/upload", ctx -> {
 *     if (ctx.header("Content-Length").isEmpty()) { throw new LengthRequiredException(); }
 *     return ctx.status(201).text("stored");
 * });
 * }</pre>
 */
public class LengthRequiredException extends AxiomException {
    @Serial private static final long serialVersionUID = 1L;

    /** Creates the exception with code {@code length_required}. */
    public LengthRequiredException() {
        super(411);
    }

    /**
     * Creates the exception with a specific safe code.
     *
     * @param code machine-readable code
     */
    public LengthRequiredException(String code) {
        super(411, code);
    }
}
