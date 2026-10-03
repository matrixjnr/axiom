package com.jsgalactic.axiom.security.jwt;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;

/**
 * The claims of a token whose signature, expiry, issuer, audience and subject have been verified,
 * read through typed accessors so that no JSON library type is exposed. Passed to a
 * {@link TokenCheck} and to {@link JwtAuthenticator.Builder#attributes}.
 *
 * <p>Accessors never return a value of another type than they promise: a claim of the wrong
 * type throws {@link IllegalArgumentException}, which the authenticator answers like any invalid
 * token (401 {@code invalid_token}). An absent claim is empty. The claim values are untrusted in
 * the sense that the issuer chose them; apply the same care as to any input. {@link #toString()}
 * lists the number of claims, not their values.
 *
 * <p>Immutable and thread-safe; valid after the call that received it returns.
 */
public final class JwtClaims {
    private final Map<String, Object> claims;

    JwtClaims(Map<String, Object> claims) {
        this.claims = Map.copyOf(claims);
    }

    /**
     * Lists the names of the claims present.
     *
     * @return unmodifiable set of claim names
     */
    public Set<String> names() {
        return claims.keySet();
    }

    /**
     * Reports whether a claim is present, with any value (including JSON {@code null}).
     *
     * @param name claim name
     * @return true if present
     */
    public boolean has(String name) {
        return claims.containsKey(Objects.requireNonNull(name, "name"));
    }

    /**
     * Reads a string claim.
     *
     * @param name claim name
     * @return the value, or empty if absent or JSON {@code null}
     * @throws IllegalArgumentException if the claim is not a string
     */
    public Optional<String> string(String name) {
        var value = claims.get(Objects.requireNonNull(name, "name"));
        if (value == null || value == Json.NULL) { return Optional.empty(); }
        if (value instanceof String text) { return Optional.of(text); }
        throw new IllegalArgumentException("The claim " + name + " is not a string");
    }

    /**
     * Reads a claim that is a string or an array of strings, as a list.
     *
     * @param name claim name
     * @return the values; empty if absent or JSON {@code null}
     * @throws IllegalArgumentException if the claim is neither a string nor an array of strings
     */
    public List<String> strings(String name) {
        var value = claims.get(Objects.requireNonNull(name, "name"));
        if (value == null || value == Json.NULL) { return List.of(); }
        if (value instanceof String text) { return List.of(text); }
        if (value instanceof List<?> list) {
            var result = new ArrayList<String>(list.size());
            for (var element : list) {
                if (!(element instanceof String text)) { throw new IllegalArgumentException("The claim " + name + " holds a non-string value"); }
                result.add(text);
            }
            return List.copyOf(result);
        }
        throw new IllegalArgumentException("The claim " + name + " is neither a string nor an array of strings");
    }

    /**
     * Reads a claim that is a non-negative integer of at most 15 digits.
     *
     * @param name claim name
     * @return the value, or empty if absent or JSON {@code null}
     * @throws IllegalArgumentException if the claim is not such an integer
     */
    public OptionalLong number(String name) {
        var value = claims.get(Objects.requireNonNull(name, "name"));
        if (value == null || value == Json.NULL) { return OptionalLong.empty(); }
        long number = value instanceof Json.JsonNumber json ? json.nonNegativeInteger() : -1;
        if (number < 0) { throw new IllegalArgumentException("The claim " + name + " is not a non-negative integer"); }
        return OptionalLong.of(number);
    }

    /**
     * Reads a boolean claim.
     *
     * @param name claim name
     * @return the value, or empty if absent or JSON {@code null}
     * @throws IllegalArgumentException if the claim is not a boolean
     */
    public Optional<Boolean> bool(String name) {
        var value = claims.get(Objects.requireNonNull(name, "name"));
        if (value == null || value == Json.NULL) { return Optional.empty(); }
        if (value instanceof Boolean flag) { return Optional.of(flag); }
        throw new IllegalArgumentException("The claim " + name + " is not a boolean");
    }

    /**
     * Reads a scalar claim (string, number or boolean) as text.
     *
     * @param name claim name
     * @return the text; empty if absent or JSON {@code null}
     * @throws IllegalArgumentException if the claim is an array or an object
     */
    public Optional<String> text(String name) {
        var value = claims.get(Objects.requireNonNull(name, "name"));
        if (value == null || value == Json.NULL) { return Optional.empty(); }
        if (value instanceof String || value instanceof Boolean) { return Optional.of(value.toString()); }
        if (value instanceof Json.JsonNumber number) { return Optional.of(number.text()); }
        throw new IllegalArgumentException("The claim " + name + " is not a scalar");
    }

    /**
     * Returns the {@code jti} claim, the token's unique identifier, for replay and revocation checks.
     *
     * @return the identifier, or empty if the token has none
     * @throws IllegalArgumentException if present but not a string
     */
    public Optional<String> id() {
        return string("jti");
    }

    /**
     * Returns the {@code sub} claim, which is always present in a verified token.
     *
     * @return the subject
     */
    public String subject() {
        return string("sub").orElseThrow();
    }

    /**
     * Returns the {@code iss} claim, which is always present in a verified token.
     *
     * @return the issuer
     */
    public String issuer() {
        return string("iss").orElseThrow();
    }

    /**
     * Returns the {@code exp} claim, which is always present in a verified token. A replay record
     * for {@link #id()} can be dropped after this instant plus the clock skew.
     *
     * @return the expiry
     */
    public Instant expiresAt() {
        return Instant.ofEpochSecond(number("exp").orElseThrow());
    }

    @Override
    public String toString() {
        return "JwtClaims[" + claims.size() + " claims]";
    }
}
