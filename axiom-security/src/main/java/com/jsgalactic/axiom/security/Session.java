package com.jsgalactic.axiom.security;

import com.jsgalactic.axiom.context.SecurityIdentity;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;
import java.util.function.UnaryOperator;

/**
 * One request's view of its session, from {@link Sessions#session(com.jsgalactic.axiom.context.Context)}.
 *
 * <pre>{@code
 * var session = sessions.session(ctx);
 * session.attribute("cart", "42");                       // creates the session on first write
 * session.authenticate(new SecurityIdentity("ada", Set.of("user"), Set.of()));   // login: new identifier
 * session.invalidate();                                  // logout
 * }</pre>
 *
 * <p><b>Lazy and transactional.</b> Reading never creates a session, so anonymous traffic costs the
 * store nothing. Writes are recorded and applied to the store atomically, once, when the request
 * completes normally: if the handler throws, none of them is applied (and no cookie is issued).
 * Concurrent requests of one session therefore do not overwrite each other's attributes; two
 * requests that set the <em>same</em> attribute or identity are ordered by completion.
 *
 * <p><b>Identifier rotation.</b> {@link #authenticate} and {@link #rotate} give the session a new
 * secret identifier when the request completes; the old identifier stops working immediately. Call
 * {@code authenticate} at every sign-in and whenever the identity's privileges change, so an
 * identifier an attacker planted or observed before the change is useless afterwards (session
 * fixation). A client-supplied identifier that the store does not know is never adopted. The
 * identifier is deliberately not exposed: it appears only in the cookie.
 *
 * <p>Attributes whose names start with {@code axiom.} are reserved for the library (the CSRF token);
 * {@code authenticate} deletes them so tokens do not survive a change of identity.
 *
 * <p>Methods are synchronized, but a session belongs to one request and is released when it ends.
 * {@code toString()} shows no identifier or data.
 */
public final class Session {
    static final String RESERVED = "axiom.";

    private sealed interface Change {
        SessionState apply(SessionState state);
    }

    private record Put(String name, String value) implements Change {
        @Override public SessionState apply(SessionState state) {
            var map = new TreeMap<>(state.attributes());
            map.put(name, value);
            return state.withAttributes(map);
        }
    }

    private record Remove(String name) implements Change {
        @Override public SessionState apply(SessionState state) {
            var map = new TreeMap<>(state.attributes());
            map.remove(name);
            return state.withAttributes(map);
        }
    }

    private record SetIdentity(SecurityIdentity identity) implements Change {
        @Override public SessionState apply(SessionState state) {
            var map = new TreeMap<>(state.attributes());
            map.keySet().removeIf(name -> name.startsWith(RESERVED));
            return state.withAttributes(map).withIdentity(Optional.of(identity));
        }
    }

    private final String id;
    private final Instant createdAt;
    private SecurityIdentity identity;
    private final TreeMap<String, String> attributes;
    private final List<Change> changes = new ArrayList<>();
    private boolean rotate;
    private boolean authenticated;
    private boolean invalidated;

    Session(String id, SessionState state) {
        this.id = id;
        createdAt = state == null ? null : state.createdAt();
        identity = state == null ? null : state.identity().orElse(null);
        attributes = state == null ? new TreeMap<>() : new TreeMap<>(state.attributes());
    }

    /**
     * Tells whether this session is not stored yet (no valid cookie came with the request).
     *
     * @return true for a session that exists only if this request writes to it
     */
    public synchronized boolean isNew() { return id == null; }

    /**
     * Returns when the stored session was created.
     *
     * @return the creation time, empty for a new session
     */
    public synchronized Optional<Instant> createdAt() { return Optional.ofNullable(createdAt); }

    /**
     * Returns the signed-in identity, including a change made earlier in this request.
     *
     * @return the identity, empty when anonymous or invalidated
     */
    public synchronized Optional<SecurityIdentity> identity() { return invalidated ? Optional.empty() : Optional.ofNullable(identity); }

    /**
     * Returns an attribute, including changes made earlier in this request.
     *
     * @param name attribute name
     * @return the value, if set
     */
    public synchronized Optional<String> attribute(String name) {
        return invalidated ? Optional.empty() : Optional.ofNullable(attributes.get(Objects.requireNonNull(name, "name")));
    }

    /**
     * Returns a snapshot of the attributes without the reserved ones.
     *
     * @return an unmodifiable copy
     */
    public synchronized Map<String, String> attributes() {
        if (invalidated) { return Map.of(); }
        var copy = new TreeMap<>(attributes);
        copy.keySet().removeIf(name -> name.startsWith(RESERVED));
        return Collections.unmodifiableMap(copy);
    }

    /**
     * Sets an attribute; applied when the request completes.
     *
     * @param name {@code [A-Za-z0-9_.:-]{1,128}}, not starting with {@code axiom.}
     * @param value up to 4,096 characters
     * @return this session
     * @throws IllegalArgumentException for an invalid or reserved name, a long value, or more than
     *     {@value SessionState#MAX_ATTRIBUTES} attributes
     * @throws IllegalStateException if the session was invalidated
     */
    public Session attribute(String name, String value) {
        if (Objects.requireNonNull(name, "name").startsWith(RESERVED)) { throw new IllegalArgumentException("Reserved attribute name"); }
        return put(name, value);
    }

    Session put(String name, String value) {
        Objects.requireNonNull(value, "value");
        if (!SessionState.validName(name)) { throw new IllegalArgumentException("Invalid session attribute name"); }
        if (value.length() > SessionState.MAX_VALUE_LENGTH) { throw new IllegalArgumentException("Session attribute value too long"); }
        synchronized (this) {
            requireLive();
            if (!attributes.containsKey(name) && attributes.size() >= SessionState.MAX_ATTRIBUTES) {
                throw new IllegalArgumentException("Too many session attributes");
            }
            attributes.put(name, value);
            changes.add(new Put(name, value));
        }
        return this;
    }

    /**
     * Removes an attribute; applied when the request completes.
     *
     * @param name attribute name
     * @return this session
     */
    public Session removeAttribute(String name) {
        if (Objects.requireNonNull(name, "name").startsWith(RESERVED)) { throw new IllegalArgumentException("Reserved attribute name"); }
        synchronized (this) {
            requireLive();
            attributes.remove(name);
            changes.add(new Remove(name));
        }
        return this;
    }

    /**
     * Signs the identity in and rotates the identifier when the request completes. Attributes are
     * kept except the reserved ones; the previous identity, if any, is replaced.
     *
     * @param identity the verified identity
     * @return this session
     * @throws IllegalStateException if the session was invalidated
     */
    public synchronized Session authenticate(SecurityIdentity identity) {
        Objects.requireNonNull(identity, "identity");
        requireLive();
        this.identity = identity;
        attributes.keySet().removeIf(name -> name.startsWith(RESERVED));
        changes.add(new SetIdentity(identity));
        authenticated = true;
        rotate = true;
        return this;
    }

    /**
     * Rotates the identifier when the request completes, keeping the data.
     *
     * @return this session
     * @throws IllegalStateException if the session was invalidated
     */
    public synchronized Session rotate() {
        requireLive();
        rotate = true;
        return this;
    }

    /**
     * Deletes the session when the request completes and expires the cookie (logout).
     *
     * @return this session
     */
    public synchronized Session invalidate() {
        invalidated = true;
        changes.clear();
        return this;
    }

    synchronized String id() { return id; }

    synchronized boolean invalidated() { return invalidated; }

    synchronized boolean rotates() { return rotate; }

    synchronized boolean signsIn() { return authenticated; }

    synchronized boolean changed() { return rotate || !changes.isEmpty(); }

    /** Applies the recorded writes to a state, for the store's atomic update. */
    synchronized UnaryOperator<SessionState> writes() {
        var recorded = List.copyOf(changes);
        return state -> {
            var result = state;
            for (var change : recorded) { result = change.apply(result); }
            return result;
        };
    }

    private void requireLive() {
        if (invalidated) { throw new IllegalStateException("The session was invalidated"); }
    }

    @Override
    public String toString() {
        return "Session[" + (id == null ? "new" : "stored") + "]";
    }
}
