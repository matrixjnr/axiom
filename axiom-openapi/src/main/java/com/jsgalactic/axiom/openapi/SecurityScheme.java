package com.jsgalactic.axiom.openapi;

import java.util.Objects;
import java.util.regex.Pattern;

/**
 * A security scheme that routes can require by name (see
 * {@link com.jsgalactic.axiom.routing.RouteDoc#security(String...)}). It only documents how
 * clients authenticate; the application enforces access with its own middleware.
 *
 * <p>In a Swagger 2.0 document a bearer scheme is an API key in the {@code Authorization} header,
 * because that format has no bearer type.
 */
public final class SecurityScheme {
    private static final Pattern HEADER = Pattern.compile("[!#$%&'*+.^_`|~0-9A-Za-z-]+");
    private static final Pattern QUERY = Pattern.compile("[A-Za-z0-9_.~-]+");

    enum Kind { BEARER, BASIC, API_KEY_HEADER, API_KEY_QUERY }

    private final Kind kind;
    private final String value;

    private SecurityScheme(Kind kind, String value) {
        this.kind = kind;
        this.value = value;
    }

    /**
     * An HTTP bearer token.
     *
     * @return the scheme
     */
    public static SecurityScheme bearer() { return new SecurityScheme(Kind.BEARER, null); }

    /**
     * An HTTP bearer token with a documented format, for example {@code JWT}.
     *
     * @param format format hint
     * @return the scheme
     */
    public static SecurityScheme bearer(String format) {
        return new SecurityScheme(Kind.BEARER, Objects.requireNonNull(format, "format"));
    }

    /**
     * HTTP basic authentication.
     *
     * @return the scheme
     */
    public static SecurityScheme basic() { return new SecurityScheme(Kind.BASIC, null); }

    /**
     * An API key sent in a request header.
     *
     * @param header header name
     * @return the scheme
     * @throws IllegalArgumentException if the name is not a header name
     */
    public static SecurityScheme apiKeyHeader(String header) {
        if (!HEADER.matcher(Objects.requireNonNull(header, "header")).matches()) {
            throw new IllegalArgumentException("Invalid header name");
        }
        return new SecurityScheme(Kind.API_KEY_HEADER, header);
    }

    /**
     * An API key sent as a query parameter.
     *
     * @param parameter parameter name
     * @return the scheme
     * @throws IllegalArgumentException if the name is not a plain parameter name
     */
    public static SecurityScheme apiKeyQuery(String parameter) {
        if (!QUERY.matcher(Objects.requireNonNull(parameter, "parameter")).matches()) {
            throw new IllegalArgumentException("Invalid parameter name");
        }
        return new SecurityScheme(Kind.API_KEY_QUERY, parameter);
    }

    Kind kind() { return kind; }

    String value() { return value; }
}
