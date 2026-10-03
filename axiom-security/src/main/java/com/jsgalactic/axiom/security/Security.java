package com.jsgalactic.axiom.security;

import com.jsgalactic.axiom.context.Context;
import com.jsgalactic.axiom.context.Middleware;
import com.jsgalactic.axiom.context.SecurityIdentity;
import com.jsgalactic.axiom.error.ForbiddenException;
import com.jsgalactic.axiom.error.UnauthorizedException;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Authentication and authorization policies as {@link Middleware}, bound to one
 * {@link Authenticator}.
 *
 * <pre>{@code
 * var security = Security.of(authenticator);
 * app.use(security.authenticate());                      // identity when credentials are sent
 * app.get("/me", ctx -> ctx.identity().orElseThrow().principal(), security.authenticated());
 * app.group("/admin", admin -> {
 *     admin.use(security.hasRole("admin"));             // 401 without credentials, 403 without the role
 *     admin.delete("/notes/:id", deleteNote, security.hasPermission("notes:delete"));
 * });
 * }</pre>
 *
 * <p><b>Outcomes.</b> Every policy resolves the identity first: it uses the one already attached
 * to the context, or runs the authenticator and attaches what it returns. Without an identity the
 * policy throws {@link UnauthorizedException} (401, code {@code unauthorized}) with the
 * authenticator's challenge; invalid credentials fail with whatever 401 the authenticator throws.
 * An identity that lacks the required role or permission gets {@link ForbiddenException} (403, code
 * {@code forbidden}); the response does not say which grant was missing. Both are ordinary
 * {@code application/problem+json} responses and can be mapped with error handlers.
 *
 * <p><b>Placement.</b> Policies are middleware, so they protect exactly the scope they are
 * registered on (global, group or route) and run before the handler. Stacked policies must all
 * pass. Use {@link #authenticate()} globally to make the identity available to handlers of public
 * routes as well; it never rejects a request without credentials.
 *
 * <p><b>Lifecycle and thread safety.</b> Immutable and thread-safe. The middleware it returns hold
 * the authenticator and are owned by the application they are registered with. An identity is
 * attached at most once per request ({@link Context#identity(SecurityIdentity)}); when several
 * {@code Security} instances guard one route, the first identity attached wins and later policies
 * check it instead of authenticating again.
 */
public final class Security {
    private final Authenticator authenticator;
    private final String challenge;

    private Security(Authenticator authenticator) {
        this.authenticator = authenticator;
        challenge = Objects.requireNonNull(authenticator.challenge(), "challenge");
        new UnauthorizedException(challenge); // Validates the challenge once, at configuration time.
    }

    /**
     * Creates policies that authenticate with the authenticator.
     *
     * @param authenticator thread-safe authenticator
     * @return policies bound to it
     * @throws IllegalArgumentException if the authenticator's challenge is not a valid header value
     */
    public static Security of(Authenticator authenticator) {
        return new Security(Objects.requireNonNull(authenticator, "authenticator"));
    }

    /**
     * Returns middleware that attaches the identity when the request carries valid credentials
     * and continues anonymously when it carries none. Invalid credentials still fail with 401: a
     * client that sends a broken token learns so instead of being served as anonymous.
     *
     * @return optional authentication middleware
     */
    public Middleware authenticate() {
        return (ctx, next) -> {
            resolve(ctx);
            return next.run();
        };
    }

    /**
     * Returns middleware that requires an identity (401 otherwise).
     *
     * @return policy middleware
     */
    public Middleware authenticated() {
        return (ctx, next) -> {
            require(ctx);
            return next.run();
        };
    }

    /**
     * Returns middleware that requires an identity with the role (401 without an identity, 403
     * without the role).
     *
     * @param role exact role name
     * @return policy middleware
     */
    public Middleware hasRole(String role) {
        var required = role(role);
        return (ctx, next) -> {
            if (!require(ctx).hasRole(required)) { throw new ForbiddenException(); }
            return next.run();
        };
    }

    /**
     * Returns middleware that requires an identity with at least one of the roles (401 without an
     * identity, 403 with none of them).
     *
     * @param roles exact role names; at least one
     * @return policy middleware
     */
    public Middleware hasAnyRole(String... roles) {
        var required = List.of(roles);
        if (required.isEmpty()) { throw new IllegalArgumentException("At least one role is required"); }
        required.forEach(Security::role);
        return (ctx, next) -> {
            var identity = require(ctx);
            if (required.stream().noneMatch(identity::hasRole)) { throw new ForbiddenException(); }
            return next.run();
        };
    }

    /**
     * Returns middleware that requires an identity with the permission (401 without an identity,
     * 403 without the permission).
     *
     * @param permission exact permission name
     * @return policy middleware
     */
    public Middleware hasPermission(String permission) {
        Objects.requireNonNull(permission, "permission");
        new SecurityIdentity("policy", Set.of(), Set.of(permission)); // Same naming rules as grants.
        var required = permission;
        return (ctx, next) -> {
            if (!require(ctx).hasPermission(required)) { throw new ForbiddenException(); }
            return next.run();
        };
    }

    private static String role(String role) {
        Objects.requireNonNull(role, "role");
        new SecurityIdentity("policy", Set.of(role), Set.of()); // Same naming rules as grants.
        return role;
    }

    private Optional<SecurityIdentity> resolve(Context ctx) {
        var current = ctx.identity();
        if (current.isPresent()) { return current; }
        var identity = authenticator.authenticate(ctx.request());
        if (identity == null) { throw new IllegalStateException("Authenticator returned null instead of an Optional"); }
        identity.ifPresent(ctx::identity);
        return identity;
    }

    private SecurityIdentity require(Context ctx) {
        return resolve(ctx).orElseThrow(() -> new UnauthorizedException(challenge));
    }
}
