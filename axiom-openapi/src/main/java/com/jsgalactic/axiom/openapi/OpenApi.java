package com.jsgalactic.axiom.openapi;

import com.jsgalactic.axiom.application.Application;
import com.jsgalactic.axiom.context.Context;
import com.jsgalactic.axiom.context.Middleware;
import com.jsgalactic.axiom.http.Response;
import com.jsgalactic.axiom.routing.Route;
import com.jsgalactic.axiom.routing.RouteDoc;
import com.jsgalactic.axiom.validation.Constraint;
import com.jsgalactic.axiom.validation.Rules;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Generates an OpenAPI 3.1 document, or the equivalent Swagger 2.0 document, from an
 * application's routes and their {@link RouteDoc} descriptions.
 *
 * <pre>{@code
 * var openApi = OpenApi.builder("Notes API", "1.0.0")
 *         .securityScheme("bearer", SecurityScheme.bearer("JWT"))
 *         .build();
 * // after registering every other route:
 * openApi.serve(app, "/openapi.json");
 * }</pre>
 *
 * <p>Every route appears with its method and path; metadata adds to it. Output is deterministic:
 * paths, methods, response codes and schema names are sorted, record properties keep their
 * declaration order and JavaBean properties are sorted by name, so equal inputs produce equal
 * bytes. Nothing is derived from handler code, and no class or package name appears in the output
 * unless it is the simple name of a described record, bean or enum (rename it with
 * {@link Builder#schemaName}).
 *
 * <p>Generation fails with {@link IllegalArgumentException}, naming the route or property, for a
 * type it cannot describe, a duplicated operation id, an unknown security scheme, a documented
 * path parameter the route does not have, or a method OpenAPI cannot express. It happens when the
 * document is rendered, which {@link #serve} does immediately, so a wrong description stops
 * startup instead of failing a request.
 *
 * <p>Instances are immutable and thread-safe. The document describes the application's API
 * surface; whether to expose it publicly is the application's decision (see the guide).
 */
public final class OpenApi {
    private static final Pattern PREFIX = Pattern.compile("(/[^/]+)*");

    final String title;
    final String version;
    final String description;
    final List<String[]> servers;
    final Map<String, SecurityScheme> schemes;
    final Map<Class<?>, String> names;
    final Map<Class<?>, Map<String, List<Constraint>>> rules;
    final List<Map.Entry<String, RouteDoc>> defaults;
    final int maxDepth;

    private OpenApi(Builder b) {
        this.title = b.title;
        this.version = b.version;
        this.description = b.description;
        this.servers = List.copyOf(b.servers);
        this.schemes = Map.copyOf(b.schemes);
        this.names = Map.copyOf(b.names);
        this.rules = Map.copyOf(b.rules);
        this.defaults = List.copyOf(b.defaults);
        this.maxDepth = b.maxDepth;
    }

    /**
     * Starts a configuration.
     *
     * @param title API title
     * @param version API version (the application's, not the OpenAPI version)
     * @return a builder
     */
    public static Builder builder(String title, String version) {
        return new Builder(title, version);
    }

    /**
     * Renders the OpenAPI 3.1 document of the application's current routes.
     *
     * @param app application whose routes and descriptions are read; may be configuring or running
     * @return the JSON document
     * @throws IllegalArgumentException if a route or type cannot be described
     */
    public String render(Application app) {
        return Renderer.render(this, Objects.requireNonNull(app, "app"), false);
    }

    /**
     * Renders the Swagger 2.0 document of the application's current routes. It carries the same
     * information in the older format, with its constraints: a single server (the first one), one
     * body schema per operation, and bearer schemes expressed as an API key header.
     *
     * @param app application whose routes and descriptions are read
     * @return the JSON document
     * @throws IllegalArgumentException if a route or type cannot be described
     */
    public String renderSwagger(Application app) {
        return Renderer.render(this, Objects.requireNonNull(app, "app"), true);
    }

    /**
     * Renders the OpenAPI 3.1 document now and registers a GET route that serves it. Call it
     * after every other route is registered; the document is a snapshot, cached for the life of
     * the application, with a strong {@code ETag} (a matching {@code If-None-Match} gets 304).
     * The serving route itself is not part of the document. Nothing is served unless this is
     * called.
     *
     * @param app application to document and register on, still configuring
     * @param path route path, for example {@code /openapi.json}
     * @param middleware route middleware, for example an authentication policy
     * @return the registered route
     * @throws IllegalArgumentException if the document cannot be generated
     */
    public Route serve(Application app, String path, Middleware... middleware) {
        return register(app, path, render(app), middleware);
    }

    /**
     * Like {@link #serve} for the Swagger 2.0 document.
     *
     * @param app application to document and register on, still configuring
     * @param path route path, for example {@code /swagger.json}
     * @param middleware route middleware
     * @return the registered route
     * @throws IllegalArgumentException if the document cannot be generated
     */
    public Route serveSwagger(Application app, String path, Middleware... middleware) {
        return register(app, path, renderSwagger(app), middleware);
    }

    private static Route register(Application app, String path, String document, Middleware[] middleware) {
        var bytes = document.getBytes(StandardCharsets.UTF_8);
        var etag = "\"" + sha256(bytes) + "\"";
        var ok = Response.of(200, bytes).withHeader("Content-Type", "application/json")
                .withHeader("Cache-Control", "no-cache").withHeader("ETag", etag);
        var notModified = Response.of(304, null).withHeader("ETag", etag);
        var route = app.get(path, (Context ctx) -> matches(ctx, etag) ? notModified : ok, middleware);
        app.describe(route, RouteDoc.empty().hidden());
        return route;
    }

    private static boolean matches(Context ctx, String etag) {
        return ctx.header("If-None-Match").map(value -> {
            for (var candidate : value.split(",")) {
                var trimmed = candidate.trim();
                if (trimmed.equals("*") || trimmed.equals(etag)) {
                    return true;
                }
            }
            return false;
        }).orElse(false);
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes), 0, 16);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Configures an {@link OpenApi}. Not thread-safe; build once at startup. */
    public static final class Builder {
        private final String title;
        private final String version;
        private String description;
        private final List<String[]> servers = new ArrayList<>();
        private final Map<String, SecurityScheme> schemes = new LinkedHashMap<>();
        private final Map<Class<?>, String> names = new HashMap<>();
        private final Map<Class<?>, Map<String, List<Constraint>>> rules = new HashMap<>();
        private final List<Map.Entry<String, RouteDoc>> defaults = new ArrayList<>();
        private int maxDepth = 32;

        private Builder(String title, String version) {
            if (Objects.requireNonNull(title, "title").isBlank() || Objects.requireNonNull(version, "version").isBlank()) {
                throw new IllegalArgumentException("Title and version must not be blank");
            }
            this.title = title;
            this.version = version;
        }

        /**
         * Sets the API description.
         *
         * @param description text
         * @return this builder
         */
        public Builder description(String description) {
            this.description = Objects.requireNonNull(description, "description");
            return this;
        }

        /**
         * Adds a server the API is reachable at. A Swagger 2.0 document uses only the first.
         *
         * @param url absolute URL or a path such as {@code /api}
         * @param description what the server is, or null
         * @return this builder
         */
        public Builder server(String url, String description) {
            if (Objects.requireNonNull(url, "url").isBlank()) {
                throw new IllegalArgumentException("A server URL must not be blank");
            }
            servers.add(new String[] {url, description});
            return this;
        }

        /**
         * Defines a security scheme that routes can require by {@code name}.
         *
         * @param name scheme name, letters, digits and {@code . _ -}
         * @param scheme the scheme
         * @return this builder
         */
        public Builder securityScheme(String name, SecurityScheme scheme) {
            if (!Objects.requireNonNull(name, "name").matches("[A-Za-z0-9._-]+")) {
                throw new IllegalArgumentException("Invalid security scheme name");
            }
            schemes.put(name, Objects.requireNonNull(scheme, "scheme"));
            return this;
        }

        /**
         * Names the schema of a record, bean or enum, instead of its simple class name. Use it to
         * hide an internal name or to resolve two classes that share a simple name. For a generic
         * class the name is the prefix of the generated one.
         *
         * @param type described class
         * @param name schema name, letters, digits and {@code . _ -}
         * @return this builder
         */
        public Builder schemaName(Class<?> type, String name) {
            if (!Objects.requireNonNull(name, "name").matches("[A-Za-z0-9._-]+")) {
                throw new IllegalArgumentException("Invalid schema name");
            }
            names.put(Objects.requireNonNull(type, "type"), name);
            return this;
        }

        /**
         * Maps the built-in rules of a rule set to schema constraints on {@code type}'s
         * properties: not-null becomes required, length and size become min/max length and items,
         * min/max/range become minimum and maximum, pattern, e-mail and one-of become
         * {@code pattern}, {@code format: email} and {@code enum}. A rule that cannot constrain
         * its property's type, or a property the type does not have, fails generation. Custom
         * rules are not described. Only the rules declared with {@code field} and {@code each}
         * are mapped; register the rule set of a nested type separately.
         *
         * @param type described class
         * @param rules its rule set
         * @param <T> the type
         * @return this builder
         */
        public <T> Builder rules(Class<T> type, Rules<T> rules) {
            this.rules.put(Objects.requireNonNull(type, "type"), Objects.requireNonNull(rules, "rules").constraints());
            return this;
        }

        /**
         * Adds defaults for every route whose path equals {@code pathPrefix} or lies below it,
         * for example the tags, the security requirement or a 401 response of a whole group.
         * Tags are added to the route's own; security applies only to routes that declare none;
         * responses and parameters apply where the route has none with the same status or name.
         * Other settings of {@code defaults} are ignored. Defaults apply in the order added.
         *
         * @param pathPrefix {@code ""} for all routes, or a template prefix such as {@code /api/v1}
         * @param defaults the defaults
         * @return this builder
         */
        public Builder defaults(String pathPrefix, RouteDoc defaults) {
            if (!PREFIX.matcher(Objects.requireNonNull(pathPrefix, "pathPrefix")).matches()) {
                throw new IllegalArgumentException("A path prefix is empty or starts with / and does not end with /");
            }
            this.defaults.add(Map.entry(pathPrefix, Objects.requireNonNull(defaults, "defaults")));
            return this;
        }

        /**
         * Limits how deeply described types may nest before generation fails; cycles never count
         * because a type that refers to itself becomes a reference.
         *
         * @param maxDepth at least 1, 32 by default
         * @return this builder
         */
        public Builder maxDepth(int maxDepth) {
            if (maxDepth < 1) {
                throw new IllegalArgumentException("maxDepth must be at least 1");
            }
            this.maxDepth = maxDepth;
            return this;
        }

        /**
         * Builds the configuration.
         *
         * @return an immutable {@link OpenApi}
         */
        public OpenApi build() { return new OpenApi(this); }
    }
}
