package com.jsgalactic.axiom.security;

import java.util.Optional;
import java.util.function.UnaryOperator;

/**
 * Where {@link Sessions} keep session data, keyed by the secret session identifier.
 *
 * <p><b>Contract.</b> Implementations are thread-safe, enforce the idle and absolute timeouts and a
 * bound on the number of sessions themselves, and never return or accept an expired session.
 * Identifiers are 43-character base64url secrets chosen by {@link Sessions}; a store treats them as
 * credentials: it must not log them, and should key its storage by a digest of the identifier so a
 * leaked dump or log of keys cannot be replayed. Methods may block on I/O; they run on the request's
 * virtual thread.
 *
 * <p><b>Atomicity.</b> {@link #update} and {@link #rename} are atomic with respect to every other
 * operation on the same identifier, so concurrent requests of one session do not lose each other's
 * attribute changes. A distributed store implements {@code update} with a compare-and-set loop,
 * which is why the function must be pure and may run more than once.
 *
 * <p><b>Timestamps.</b> The store stamps {@code createdAt} when a session is created (ignoring the
 * state's) and {@code lastAccessedAt} on every successful {@link #find}, {@link #update} and
 * {@link #rename}. Renaming keeps {@code createdAt}: rotating the identifier does not extend the
 * absolute lifetime.
 */
public interface SessionStore {
    /**
     * Stores a new session.
     *
     * @param id the identifier
     * @param state the initial state
     * @return false if the identifier is already in use (the caller picks another)
     */
    boolean create(String id, SessionState state);

    /**
     * Returns a live session and counts the call as an access (the idle timeout restarts).
     *
     * @param id the identifier
     * @return the state with the new access time, or empty when unknown or expired
     */
    Optional<SessionState> find(String id);

    /**
     * Atomically replaces a live session's state with the function's result.
     *
     * @param id the identifier
     * @param change a pure function from the current state to the new one; may be called repeatedly
     * @return false when the session is unknown or expired, in which case nothing changed
     */
    boolean update(String id, UnaryOperator<SessionState> change);

    /**
     * Atomically gives a live session a new identifier; the old one stops working at once.
     *
     * @param id the current identifier
     * @param newId the new identifier
     * @return false when the session is unknown or expired, or the new identifier is in use
     */
    boolean rename(String id, String newId);

    /**
     * Deletes a session; unknown identifiers are ignored.
     *
     * @param id the identifier
     */
    void remove(String id);
}
