package com.jsgalactic.axiom.routing;

import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Optional, immutable documentation of one route: what it is for, which parameters, request body
 * and responses it has, and which security requirements apply. It is plain data attached with
 * {@code Application.describe(route, doc)}; nothing is scanned, no annotations are read and the
 * runtime never consults it when routing or handling requests. Tools such as the
 * {@code axiom-openapi} module read it with {@code Application.doc(route)}.
 *
 * <pre>{@code
 * app.describe(app.post("/notes", create),
 *         RouteDoc.summary("Create a note").tags("notes").operationId("createNote")
 *                 .requestBody(NewNote.class)
 *                 .response(201, "Created", Note.class)
 *                 .response(422, "Validation failed"));
 * }</pre>
 *
 * <p>Types are {@link Type} values, so generic types such as a {@code List<Note>} can be given
 * through a {@code ParameterizedType}; a plain {@link Class} is the common case. Every wither
 * returns a new instance, so a partly built description can be shared and extended. Instances are
 * thread-safe. Media types default to {@code application/json}. Names, tags and operation ids are
 * validated when the description is built.
 */
public final class RouteDoc {
    private static final Pattern MEDIA_TYPE =
            Pattern.compile("[!#$%&'*+.^_`|~0-9A-Za-z-]+/[!#$%&'*+.^_`|~0-9A-Za-z-]+");
    private static final Pattern OPERATION_ID = Pattern.compile("[A-Za-z0-9_.-]{1,128}");
    private static final Pattern NAME = Pattern.compile("[A-Za-z0-9_.~-]{1,128}");
    private static final String[] JSON = {"application/json"};

    /** Where a parameter is read from. */
    public enum In {
        /** A captured path segment, always required. */
        PATH,
        /** A query string parameter. */
        QUERY,
        /** A request header. */
        HEADER
    }

    /**
     * A documented parameter.
     *
     * @param in where it is read from
     * @param name path capture name, query name or header name
     * @param type simple type: a primitive, wrapper, String, enum, UUID, number, date/time type, or
     *        a list or set of those
     * @param description human-readable description, or null
     * @param required whether the request must carry it; always true for path parameters
     */
    public record Param(In in, String name, Type type, String description, boolean required) {
        /** Validates the parameter. */
        public Param {
            Objects.requireNonNull(in, "in");
            Objects.requireNonNull(type, "type");
            if (name == null || !NAME.matcher(name).matches()) {
                throw new IllegalArgumentException("Invalid parameter name");
            }
            required = required || in == In.PATH;
        }
    }

    /**
     * A documented request body.
     *
     * @param type body type
     * @param mediaTypes accepted media types, at least one
     */
    public record Body(Type type, List<String> mediaTypes) {
        /** Validates and copies the body description. */
        public Body {
            Objects.requireNonNull(type, "type");
            mediaTypes = checkedMediaTypes(mediaTypes);
        }
    }

    /**
     * A documented response.
     *
     * @param status HTTP status, 100 to 599
     * @param description human-readable description
     * @param type body type, or null for a response without a documented body
     * @param mediaTypes media types of the body; empty when {@code type} is null
     */
    public record Reply(int status, String description, Type type, List<String> mediaTypes) {
        /** Validates and copies the response description. */
        public Reply {
            if (status < 100 || status > 599) {
                throw new IllegalArgumentException("Invalid status: " + status);
            }
            Objects.requireNonNull(description, "description");
            mediaTypes = type == null ? List.of() : checkedMediaTypes(mediaTypes);
        }
    }

    private static final RouteDoc EMPTY = new RouteDoc(null, null, List.of(), null, false, false, null, List.of(),
            List.of(), List.of());

    private final String summary;
    private final String description;
    private final List<String> tags;
    private final String operationId;
    private final boolean deprecated;
    private final boolean hidden;
    private final Body requestBody;
    private final List<Reply> responses;
    private final List<Param> parameters;
    private final List<String> security;

    private RouteDoc(String summary, String description, List<String> tags, String operationId, boolean deprecated,
                     boolean hidden, Body requestBody, List<Reply> responses, List<Param> parameters,
                     List<String> security) {
        this.summary = summary;
        this.description = description;
        this.tags = tags;
        this.operationId = operationId;
        this.deprecated = deprecated;
        this.hidden = hidden;
        this.requestBody = requestBody;
        this.responses = responses;
        this.parameters = parameters;
        this.security = security;
    }

    /**
     * Creates the type {@code raw<arguments...>}, for example {@code type(Page.class, Note.class)}
     * for a {@code Page<Note>}, since a {@code Class} literal cannot carry type arguments.
     *
     * @param raw generic class
     * @param arguments its type arguments, one per type parameter
     * @return a parameterized type usable wherever a {@code RouteDoc} takes a type
     * @throws IllegalArgumentException if the count differs from the class's type parameters
     */
    public static Type type(Class<?> raw, Type... arguments) {
        Objects.requireNonNull(raw, "raw");
        if (arguments.length == 0 || arguments.length != raw.getTypeParameters().length) {
            throw new IllegalArgumentException(raw.getSimpleName() + " has " + raw.getTypeParameters().length
                    + " type parameter(s), not " + arguments.length);
        }
        var copy = arguments.clone();
        for (var argument : copy) {
            Objects.requireNonNull(argument, "argument");
        }
        return new java.lang.reflect.ParameterizedType() {
            @Override public Type[] getActualTypeArguments() { return copy.clone(); }
            @Override public Type getRawType() { return raw; }
            @Override public Type getOwnerType() { return raw.getDeclaringClass(); }
            @Override public String getTypeName() {
                var names = new ArrayList<String>();
                for (var argument : copy) {
                    names.add(argument.getTypeName());
                }
                return raw.getName() + "<" + String.join(", ", names) + ">";
            }
            @Override public String toString() { return getTypeName(); }
        };
    }

    /**
     * Creates the type {@code List<element>}.
     *
     * @param element element type
     * @return a parameterized list type
     */
    public static Type listOf(Type element) { return type(List.class, element); }

    /**
     * Returns the empty description: no metadata at all.
     *
     * @return the empty description
     */
    public static RouteDoc empty() { return EMPTY; }

    /**
     * Starts a description with a one-line summary.
     *
     * @param summary short summary of what the route does
     * @return a description
     */
    public static RouteDoc summary(String summary) { return EMPTY.withSummary(summary); }

    /**
     * Returns a copy with another summary.
     *
     * @param summary short summary
     * @return the new description
     */
    public RouteDoc withSummary(String summary) {
        Objects.requireNonNull(summary, "summary");
        return new RouteDoc(summary, description, tags, operationId, deprecated, hidden, requestBody, responses,
                parameters, security);
    }

    /**
     * Returns a copy with a longer description.
     *
     * @param description description text
     * @return the new description
     */
    public RouteDoc description(String description) {
        Objects.requireNonNull(description, "description");
        return new RouteDoc(summary, description, tags, operationId, deprecated, hidden, requestBody, responses,
                parameters, security);
    }

    /**
     * Returns a copy with more tags, appended in order; duplicates are ignored.
     *
     * @param names tag names, none blank
     * @return the new description
     */
    public RouteDoc tags(String... names) {
        var all = new ArrayList<>(tags);
        for (var name : names) {
            if (Objects.requireNonNull(name, "tag").isBlank()) {
                throw new IllegalArgumentException("A tag must not be blank");
            }
            if (!all.contains(name)) {
                all.add(name);
            }
        }
        return new RouteDoc(summary, description, List.copyOf(all), operationId, deprecated, hidden, requestBody,
                responses, parameters, security);
    }

    /**
     * Returns a copy with an operation id, which should be unique across the application.
     *
     * @param operationId 1 to 128 characters of letters, digits, {@code _}, {@code .} and {@code -}
     * @return the new description
     */
    public RouteDoc operationId(String operationId) {
        if (!OPERATION_ID.matcher(Objects.requireNonNull(operationId, "operationId")).matches()) {
            throw new IllegalArgumentException("Invalid operation id");
        }
        return new RouteDoc(summary, description, tags, operationId, deprecated, hidden, requestBody, responses,
                parameters, security);
    }

    /**
     * Returns a copy marked deprecated.
     *
     * @return the new description
     */
    public RouteDoc deprecated() {
        return new RouteDoc(summary, description, tags, operationId, true, hidden, requestBody, responses,
                parameters, security);
    }

    /**
     * Returns a copy that tools leave out of generated documentation, for example an internal
     * route.
     *
     * @return the new description
     */
    public RouteDoc hidden() {
        return new RouteDoc(summary, description, tags, operationId, deprecated, true, requestBody, responses,
                parameters, security);
    }

    /**
     * Returns a copy documenting a JSON request body.
     *
     * @param type body type
     * @return the new description
     */
    public RouteDoc requestBody(Type type) { return requestBody(type, JSON); }

    /**
     * Returns a copy documenting the request body, replacing an earlier one.
     *
     * @param type body type
     * @param mediaTypes accepted media types, at least one
     * @return the new description
     */
    public RouteDoc requestBody(Type type, String... mediaTypes) {
        return new RouteDoc(summary, description, tags, operationId, deprecated, hidden,
                new Body(type, List.of(mediaTypes)), responses, parameters, security);
    }

    /**
     * Returns a copy documenting a response without a body.
     *
     * @param status HTTP status
     * @param description what the response means
     * @return the new description
     * @throws IllegalArgumentException if the status is already documented
     */
    public RouteDoc response(int status, String description) {
        return withReply(new Reply(status, description, null, List.of()));
    }

    /**
     * Returns a copy documenting a JSON response body.
     *
     * @param status HTTP status
     * @param description what the response means
     * @param type body type
     * @return the new description
     * @throws IllegalArgumentException if the status is already documented
     */
    public RouteDoc response(int status, String description, Type type) {
        return response(status, description, type, JSON);
    }

    /**
     * Returns a copy documenting a response body.
     *
     * @param status HTTP status
     * @param description what the response means
     * @param type body type
     * @param mediaTypes media types the response can have, at least one
     * @return the new description
     * @throws IllegalArgumentException if the status is already documented
     */
    public RouteDoc response(int status, String description, Type type, String... mediaTypes) {
        return withReply(new Reply(status, description, Objects.requireNonNull(type, "type"), List.of(mediaTypes)));
    }

    private RouteDoc withReply(Reply reply) {
        for (var existing : responses) {
            if (existing.status() == reply.status()) {
                throw new IllegalArgumentException("Status " + reply.status() + " is already documented");
            }
        }
        var all = new ArrayList<>(responses);
        all.add(reply);
        return new RouteDoc(summary, description, tags, operationId, deprecated, hidden, requestBody,
                List.copyOf(all), parameters, security);
    }

    /**
     * Returns a copy documenting a path parameter (a {@code :name} or {@code *name} capture).
     *
     * @param name capture name
     * @param type simple type
     * @param description description, or null
     * @return the new description
     */
    public RouteDoc pathParam(String name, Type type, String description) {
        return withParam(new Param(In.PATH, name, type, description, true));
    }

    /**
     * Returns a copy documenting a query parameter.
     *
     * @param name parameter name
     * @param type simple type
     * @param description description, or null
     * @param required whether it must be present
     * @return the new description
     */
    public RouteDoc queryParam(String name, Type type, String description, boolean required) {
        return withParam(new Param(In.QUERY, name, type, description, required));
    }

    /**
     * Returns a copy documenting a request header.
     *
     * @param name header name
     * @param type simple type
     * @param description description, or null
     * @param required whether it must be present
     * @return the new description
     */
    public RouteDoc headerParam(String name, Type type, String description, boolean required) {
        return withParam(new Param(In.HEADER, name, type, description, required));
    }

    private RouteDoc withParam(Param param) {
        for (var existing : parameters) {
            if (existing.in() == param.in() && existing.name().equalsIgnoreCase(param.name())
                    && (param.in() == In.HEADER || existing.name().equals(param.name()))) {
                throw new IllegalArgumentException("Parameter '" + param.name() + "' is already documented");
            }
        }
        var all = new ArrayList<>(parameters);
        all.add(param);
        return new RouteDoc(summary, description, tags, operationId, deprecated, hidden, requestBody, responses,
                List.copyOf(all), security);
    }

    /**
     * Returns a copy with security requirements, appended in order. Each name refers to a scheme
     * the consuming tool knows; any one of the listed requirements satisfies the route.
     *
     * @param schemeNames names of security schemes, none blank
     * @return the new description
     */
    public RouteDoc security(String... schemeNames) {
        var all = new ArrayList<>(security);
        for (var name : schemeNames) {
            if (Objects.requireNonNull(name, "scheme").isBlank()) {
                throw new IllegalArgumentException("A security scheme name must not be blank");
            }
            if (!all.contains(name)) {
                all.add(name);
            }
        }
        return new RouteDoc(summary, description, tags, operationId, deprecated, hidden, requestBody, responses,
                parameters, List.copyOf(all));
    }

    /**
     * Returns the summary.
     *
     * @return the summary, if set
     */
    public Optional<String> summary() { return Optional.ofNullable(summary); }

    /**
     * Returns the description.
     *
     * @return the description, if set
     */
    public Optional<String> description() { return Optional.ofNullable(description); }

    /**
     * Returns the tags.
     *
     * @return tags in declaration order
     */
    public List<String> tags() { return tags; }

    /**
     * Returns the operation id.
     *
     * @return the operation id, if set
     */
    public Optional<String> operationId() { return Optional.ofNullable(operationId); }

    /**
     * Tells whether the route is deprecated.
     *
     * @return true if deprecated
     */
    public boolean isDeprecated() { return deprecated; }

    /**
     * Tells whether tools should leave the route out.
     *
     * @return true if hidden
     */
    public boolean isHidden() { return hidden; }

    /**
     * Returns the request body.
     *
     * @return the request body, if documented
     */
    public Optional<Body> requestBody() { return Optional.ofNullable(requestBody); }

    /**
     * Returns the responses.
     *
     * @return documented responses in declaration order
     */
    public List<Reply> responses() { return responses; }

    /**
     * Returns the parameters.
     *
     * @return documented parameters in declaration order
     */
    public List<Param> parameters() { return parameters; }

    /**
     * Returns the security scheme names.
     *
     * @return names in declaration order
     */
    public List<String> security() { return security; }

    private static List<String> checkedMediaTypes(List<String> mediaTypes) {
        Objects.requireNonNull(mediaTypes, "mediaTypes");
        if (mediaTypes.isEmpty()) {
            throw new IllegalArgumentException("At least one media type is required");
        }
        var copy = new ArrayList<String>(mediaTypes.size());
        for (var mediaType : mediaTypes) {
            if (mediaType == null || !MEDIA_TYPE.matcher(mediaType).matches()) {
                throw new IllegalArgumentException("Invalid media type");
            }
            if (!copy.contains(mediaType)) {
                copy.add(mediaType);
            }
        }
        return List.copyOf(copy);
    }
}
