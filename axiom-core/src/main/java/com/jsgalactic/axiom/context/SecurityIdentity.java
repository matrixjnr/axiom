package com.jsgalactic.axiom.context;

import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * The authenticated caller of one request: a principal name with the roles and permissions it
 * was granted. Immutable and thread-safe; the sets are unmodifiable copies, so an identity may be
 * handed to application tasks.
 *
 * <p>An authenticator creates the identity from credentials it verified, and authentication
 * middleware attaches it to the request with {@link Context#identity(SecurityIdentity)}. Handlers
 * read it with {@link Context#identity()}. Role and permission names are compared exactly
 * (case-sensitive). What a role or permission means is the application's decision.
 *
 * <p>{@link #toString()} lists the principal and the number of roles and permissions only, so a
 * logged identity does not spell out its grants.
 *
 * @param principal the caller's stable name, for example the {@code sub} claim of a token; one to
 *        256 characters without control characters
 * @param roles granted roles; copied, each one to 256 characters without control characters
 * @param permissions granted permissions; copied, each one to 256 characters without control
 *        characters
 */
public record SecurityIdentity(String principal, Set<String> roles, Set<String> permissions) {
    private static final Pattern NAME = Pattern.compile("[^\\p{Cntrl}]{1,256}");

    /**
     * Validates and copies the identity.
     *
     * @param principal the caller's stable name
     * @param roles granted roles
     * @param permissions granted permissions
     * @throws NullPointerException if any argument or element is null
     * @throws IllegalArgumentException for an empty, overlong or control-character name
     */
    public SecurityIdentity {
        requireName(Objects.requireNonNull(principal, "principal"), "principal");
        roles = Set.copyOf(Objects.requireNonNull(roles, "roles"));
        permissions = Set.copyOf(Objects.requireNonNull(permissions, "permissions"));
        roles.forEach(role -> requireName(role, "role"));
        permissions.forEach(permission -> requireName(permission, "permission"));
    }

    /**
     * Creates an identity without roles or permissions.
     *
     * @param principal the caller's stable name
     * @return identity with no grants
     */
    public static SecurityIdentity of(String principal) {
        return new SecurityIdentity(principal, Set.of(), Set.of());
    }

    /**
     * Reports whether the identity was granted a role.
     *
     * @param role exact role name
     * @return true if granted
     */
    public boolean hasRole(String role) {
        return roles.contains(Objects.requireNonNull(role, "role"));
    }

    /**
     * Reports whether the identity was granted a permission.
     *
     * @param permission exact permission name
     * @return true if granted
     */
    public boolean hasPermission(String permission) {
        return permissions.contains(Objects.requireNonNull(permission, "permission"));
    }

    /**
     * Describes the identity by principal and grant counts.
     *
     * @return diagnostic description
     */
    @Override
    public String toString() {
        return "SecurityIdentity[principal=" + principal + ", roles=" + roles.size()
                + ", permissions=" + permissions.size() + "]";
    }

    private static void requireName(String name, String kind) {
        if (!NAME.matcher(name).matches()) {
            throw new IllegalArgumentException("A " + kind + " has 1 to 256 characters and no control characters");
        }
    }
}
