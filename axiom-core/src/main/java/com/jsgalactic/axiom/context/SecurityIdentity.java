package com.jsgalactic.axiom.context;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
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
 * <p>Attributes carry further verified facts about the caller that an authenticator chose to
 * expose (for example a tenant or e-mail claim of a token) as text. They are not grants and no
 * policy reads them; handlers do. At most 64 attributes; a name is 1 to 256 characters and a
 * value 0 to 2,048 characters, neither with control characters.
 *
 * <p>{@link #toString()} lists the principal and the number of roles, permissions and attributes
 * only, so a logged identity does not spell out its grants or personal data.
 *
 * @param principal the caller's stable name, for example the {@code sub} claim of a token; one to
 *        256 characters without control characters
 * @param roles granted roles; copied, each one to 256 characters without control characters
 * @param permissions granted permissions; copied, each one to 256 characters without control
 *        characters
 * @param attributes further verified facts about the caller, as text; copied
 */
public record SecurityIdentity(String principal, Set<String> roles, Set<String> permissions,
        Map<String, String> attributes) {
    private static final Pattern NAME = Pattern.compile("[^\\p{Cntrl}]{1,256}");
    private static final Pattern VALUE = Pattern.compile("[^\\p{Cntrl}]{0,2048}");
    private static final int MAX_ATTRIBUTES = 64;

    /**
     * Validates and copies the identity.
     *
     * @param principal the caller's stable name
     * @param roles granted roles
     * @param permissions granted permissions
     * @param attributes further verified facts, as text
     * @throws NullPointerException if any argument or element is null
     * @throws IllegalArgumentException for an empty, overlong or control-character name, an
     *         overlong or control-character attribute value, or more than 64 attributes
     */
    public SecurityIdentity {
        requireName(Objects.requireNonNull(principal, "principal"), "principal");
        roles = Set.copyOf(Objects.requireNonNull(roles, "roles"));
        permissions = Set.copyOf(Objects.requireNonNull(permissions, "permissions"));
        roles.forEach(role -> requireName(role, "role"));
        permissions.forEach(permission -> requireName(permission, "permission"));
        attributes = Map.copyOf(Objects.requireNonNull(attributes, "attributes"));
        if (attributes.size() > MAX_ATTRIBUTES) {
            throw new IllegalArgumentException("An identity has at most " + MAX_ATTRIBUTES + " attributes");
        }
        attributes.forEach((name, value) -> {
            requireName(name, "attribute name");
            if (!VALUE.matcher(value).matches()) {
                throw new IllegalArgumentException("An attribute value has at most 2048 characters and no control characters");
            }
        });
    }

    /**
     * Creates an identity with grants and no attributes.
     *
     * @param principal the caller's stable name
     * @param roles granted roles
     * @param permissions granted permissions
     * @throws NullPointerException if any argument or element is null
     * @throws IllegalArgumentException for an empty, overlong or control-character name
     */
    public SecurityIdentity(String principal, Set<String> roles, Set<String> permissions) {
        this(principal, roles, permissions, Map.of());
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
     * Reads an attribute.
     *
     * @param name exact attribute name
     * @return the value, or empty if the authenticator exposed none with that name
     */
    public Optional<String> attribute(String name) {
        return Optional.ofNullable(attributes.get(Objects.requireNonNull(name, "name")));
    }

    /**
     * Returns a copy with further attributes; a name already present is replaced.
     *
     * @param additional attributes to add
     * @return the new identity
     * @throws IllegalArgumentException as for the constructor
     */
    public SecurityIdentity withAttributes(Map<String, String> additional) {
        var merged = new HashMap<>(attributes);
        merged.putAll(Objects.requireNonNull(additional, "additional"));
        return new SecurityIdentity(principal, roles, permissions, merged);
    }

    /**
     * Describes the identity by principal and grant counts.
     *
     * @return diagnostic description
     */
    @Override
    public String toString() {
        return "SecurityIdentity[principal=" + principal + ", roles=" + roles.size()
                + ", permissions=" + permissions.size() + ", attributes=" + attributes.size() + "]";
    }

    private static void requireName(String name, String kind) {
        if (!NAME.matcher(name).matches()) {
            throw new IllegalArgumentException("A " + kind + " has 1 to 256 characters and no control characters");
        }
    }
}
