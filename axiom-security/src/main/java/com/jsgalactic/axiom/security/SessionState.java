package com.jsgalactic.axiom.security;

import com.jsgalactic.axiom.context.SecurityIdentity;
import java.time.Instant;
import java.util.Collections;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;
import java.util.regex.Pattern;

/**
 * The data a {@link SessionStore} keeps for one session: who is signed in, application attributes
 * and two timestamps. Immutable; the store, not the caller, owns the timestamps.
 *
 * <p>Attribute names match {@code [A-Za-z0-9_.:-]{1,128}}, values are at most 4,096 characters,
 * and at most {@value #MAX_ATTRIBUTES} attributes are kept, so a store can bound what one session
 * costs. The session identifier is not part of the state.
 *
 * @param identity the signed-in identity, empty for an anonymous session
 * @param attributes application data as text; an unmodifiable copy
 * @param createdAt when the session was created; the absolute timeout counts from here
 * @param lastAccessedAt when the session was last used; the idle timeout counts from here
 */
public record SessionState(Optional<SecurityIdentity> identity, Map<String, String> attributes, Instant createdAt,
                           Instant lastAccessedAt) {
    /** Most attributes one session holds. */
    public static final int MAX_ATTRIBUTES = 64;
    /** Longest attribute value, in characters. */
    public static final int MAX_VALUE_LENGTH = 4096;

    private static final Pattern NAME = Pattern.compile("[A-Za-z0-9_.:-]{1,128}");

    /**
     * Validates and copies the components.
     *
     * @throws IllegalArgumentException if an attribute name or value is invalid or there are too many
     */
    public SessionState {
        Objects.requireNonNull(identity, "identity");
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(lastAccessedAt, "lastAccessedAt");
        if (attributes.size() > MAX_ATTRIBUTES) { throw new IllegalArgumentException("Too many session attributes"); }
        var copy = new TreeMap<String, String>();
        attributes.forEach((name, value) -> {
            if (!NAME.matcher(name).matches()) { throw new IllegalArgumentException("Invalid session attribute name"); }
            if (value.length() > MAX_VALUE_LENGTH) { throw new IllegalArgumentException("Session attribute value too long"); }
            copy.put(name, value);
        });
        attributes = Collections.unmodifiableMap(copy);
    }

    /**
     * Returns an anonymous state without attributes; the store stamps the times.
     *
     * @return the empty state
     */
    public static SessionState empty() {
        return new SessionState(Optional.empty(), Map.of(), Instant.EPOCH, Instant.EPOCH);
    }

    /**
     * Returns a copy with another identity.
     *
     * @param identity the identity, or empty
     * @return the copy
     */
    public SessionState withIdentity(Optional<SecurityIdentity> identity) {
        return new SessionState(identity, attributes, createdAt, lastAccessedAt);
    }

    /**
     * Returns a copy with other attributes.
     *
     * @param attributes the attributes
     * @return the copy
     */
    public SessionState withAttributes(Map<String, String> attributes) {
        return new SessionState(identity, attributes, createdAt, lastAccessedAt);
    }

    /**
     * Returns a copy with other timestamps.
     *
     * @param createdAt the creation time
     * @param lastAccessedAt the last access time
     * @return the copy
     */
    public SessionState withTimes(Instant createdAt, Instant lastAccessedAt) {
        return new SessionState(identity, attributes, createdAt, lastAccessedAt);
    }

    static boolean validName(String name) { return NAME.matcher(name).matches(); }

    @Override
    public String toString() {
        return "SessionState[authenticated=" + identity.isPresent() + ", attributes=" + attributes.size() + "]";
    }
}
