package com.jsgalactic.axiom.openapi;

import com.jsgalactic.axiom.application.Application;
import com.jsgalactic.axiom.routing.Route;
import com.jsgalactic.axiom.routing.RouteDoc;
import java.net.URI;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** Builds the OpenAPI 3.1 or Swagger 2.0 document model and writes it as JSON. */
final class Renderer {
    private static final List<String> METHODS = List.of("GET", "PUT", "POST", "DELETE", "OPTIONS", "HEAD", "PATCH", "TRACE");

    private final OpenApi config;
    private final Application app;
    private final boolean v2;
    private final Schemas schemas;
    private final Map<String, String> operationIds = new HashMap<>();

    private Renderer(OpenApi config, Application app, boolean v2) {
        this.config = config;
        this.app = app;
        this.v2 = v2;
        this.schemas = new Schemas(config.maxDepth, config.names, config.rules);
    }

    static String render(OpenApi config, Application app, boolean v2) {
        return Json.write(new Renderer(config, app, v2).document());
    }

    private Map<String, Object> document() {
        var paths = new TreeMap<String, Map<String, Map<String, Object>>>();
        for (var route : app.routes()) {
            var own = app.doc(route).orElse(RouteDoc.empty());
            if (own.isHidden()) {
                continue;
            }
            var label = route.method() + " " + route.path();
            if (!METHODS.contains(route.method())) {
                throw Schemas.fail(label, "OpenAPI cannot express the method " + route.method()
                        + "; hide the route with RouteDoc.empty().hidden()");
            }
            var operations = paths.computeIfAbsent(templatePath(route.path()), key -> new TreeMap<>(
                    java.util.Comparator.comparingInt(METHODS::indexOf)));
            operations.put(route.method(), operation(route, withDefaults(route.path(), own), label));
        }
        var out = new LinkedHashMap<String, Object>();
        out.put(v2 ? "swagger" : "openapi", v2 ? "2.0" : "3.1.0");
        var info = new LinkedHashMap<String, Object>();
        info.put("title", config.title);
        if (config.description != null) {
            info.put("description", config.description);
        }
        info.put("version", config.version);
        out.put("info", info);
        servers(out);
        var pathItems = new LinkedHashMap<String, Object>();
        paths.forEach((path, operations) -> pathItems.put(path, new LinkedHashMap<String, Object>(lower(operations))));
        out.put("paths", pathItems);
        var definitions = new LinkedHashMap<String, Object>(schemas.components());
        var securities = securitySchemes();
        if (v2) {
            if (!definitions.isEmpty()) {
                out.put("definitions", definitions);
            }
            if (!securities.isEmpty()) {
                out.put("securityDefinitions", securities);
            }
            return copy(rewrite(out));
        }
        var components = new LinkedHashMap<String, Object>();
        if (!definitions.isEmpty()) {
            components.put("schemas", definitions);
        }
        if (!securities.isEmpty()) {
            components.put("securitySchemes", securities);
        }
        if (!components.isEmpty()) {
            out.put("components", components);
        }
        return out;
    }

    private static Map<String, Object> lower(Map<String, Map<String, Object>> operations) {
        var out = new LinkedHashMap<String, Object>();
        operations.forEach((method, operation) -> out.put(method.toLowerCase(java.util.Locale.ROOT), operation));
        return out;
    }

    private void servers(Map<String, Object> out) {
        if (config.servers.isEmpty()) {
            return;
        }
        if (!v2) {
            var list = new ArrayList<Object>();
            for (var server : config.servers) {
                var entry = new LinkedHashMap<String, Object>();
                entry.put("url", server[0]);
                if (server[1] != null) {
                    entry.put("description", server[1]);
                }
                list.add(entry);
            }
            out.put("servers", list);
            return;
        }
        URI uri;
        try {
            uri = URI.create(config.servers.get(0)[0]);
        } catch (IllegalArgumentException e) {
            throw Schemas.fail("server " + config.servers.get(0)[0], "not a valid URL");
        }
        if (uri.getAuthority() != null) {
            out.put("host", uri.getAuthority());
            if (uri.getScheme() != null) {
                out.put("schemes", List.of(uri.getScheme()));
            }
        }
        if (uri.getPath() != null && !uri.getPath().isEmpty() && !uri.getPath().equals("/")) {
            out.put("basePath", uri.getPath());
        }
    }

    private Map<String, Object> securitySchemes() {
        var out = new LinkedHashMap<String, Object>();
        new TreeMap<>(config.schemes).forEach((name, scheme) -> {
            var map = new LinkedHashMap<String, Object>();
            switch (scheme.kind()) {
                case BEARER -> {
                    if (v2) {
                        map.put("type", "apiKey");
                        map.put("name", "Authorization");
                        map.put("in", "header");
                    } else {
                        map.put("type", "http");
                        map.put("scheme", "bearer");
                        if (scheme.value() != null) {
                            map.put("bearerFormat", scheme.value());
                        }
                    }
                }
                case BASIC -> {
                    if (v2) {
                        map.put("type", "basic");
                    } else {
                        map.put("type", "http");
                        map.put("scheme", "basic");
                    }
                }
                case API_KEY_HEADER, API_KEY_QUERY -> {
                    map.put("type", "apiKey");
                    map.put("name", scheme.value());
                    map.put("in", scheme.kind() == SecurityScheme.Kind.API_KEY_HEADER ? "header" : "query");
                }
                default -> throw new IllegalStateException(scheme.kind().name());
            }
            out.put(name, map);
        });
        return out;
    }

    // ---- defaults

    private RouteDoc withDefaults(String path, RouteDoc own) {
        var doc = own;
        for (var entry : config.defaults) {
            var prefix = entry.getKey();
            if (!(prefix.isEmpty() || path.equals(prefix) || path.startsWith(prefix + "/"))) {
                continue;
            }
            var defaults = entry.getValue();
            doc = doc.tags(defaults.tags().toArray(String[]::new));
            if (doc.security().isEmpty() && !defaults.security().isEmpty()) {
                doc = doc.security(defaults.security().toArray(String[]::new));
            }
            for (var reply : defaults.responses()) {
                if (doc.responses().stream().noneMatch(r -> r.status() == reply.status())) {
                    doc = reply.type() == null ? doc.response(reply.status(), reply.description())
                            : doc.response(reply.status(), reply.description(), reply.type(),
                                    reply.mediaTypes().toArray(String[]::new));
                }
            }
            for (var param : defaults.parameters()) {
                var present = doc.parameters().stream().anyMatch(p -> p.in() == param.in() && p.name().equals(param.name()));
                if (!present) {
                    doc = switch (param.in()) {
                        case PATH -> doc.pathParam(param.name(), param.type(), param.description());
                        case QUERY -> doc.queryParam(param.name(), param.type(), param.description(), param.required());
                        case HEADER -> doc.headerParam(param.name(), param.type(), param.description(), param.required());
                    };
                }
            }
        }
        return doc;
    }

    // ---- operations

    private static String templatePath(String path) {
        var out = new StringBuilder();
        for (var segment : path.substring(1).split("/", -1)) {
            out.append('/');
            out.append(segment.startsWith(":") || segment.startsWith("*") ? "{" + segment.substring(1) + "}" : segment);
        }
        return out.toString();
    }

    private Map<String, Object> operation(Route route, RouteDoc doc, String label) {
        var op = new LinkedHashMap<String, Object>();
        if (!doc.tags().isEmpty()) {
            op.put("tags", new ArrayList<Object>(doc.tags()));
        }
        doc.summary().ifPresent(value -> op.put("summary", value));
        doc.description().ifPresent(value -> op.put("description", value));
        doc.operationId().ifPresent(id -> {
            var previous = operationIds.put(id, label);
            if (previous != null) {
                throw Schemas.fail(label, "the operation id '" + id + "' is already used by " + previous);
            }
            op.put("operationId", id);
        });
        if (doc.isDeprecated()) {
            op.put("deprecated", true);
        }
        var parameters = parameters(route, doc, label);
        var consumes = new LinkedHashSet<String>();
        var produces = new LinkedHashSet<String>();
        if (doc.requestBody().isPresent()) {
            var body = doc.requestBody().get();
            var schema = schema(() -> schemas.schema(body.type(), "request body"), label);
            if (v2) {
                consumes.addAll(body.mediaTypes());
                parameters.add(Schemas.object("name", "body", "in", "body", "required", true, "schema", schema));
            } else {
                var content = new LinkedHashMap<String, Object>();
                body.mediaTypes().forEach(type -> content.put(type, Schemas.object("schema", copy(schema))));
                op.put("requestBody", Schemas.object("required", true, "content", content));
            }
        }
        if (!parameters.isEmpty()) {
            op.put("parameters", parameters);
        }
        var responses = new LinkedHashMap<String, Object>();
        doc.responses().stream().sorted(java.util.Comparator.comparingInt(RouteDoc.Reply::status)).forEach(reply -> {
            var entry = new LinkedHashMap<String, Object>();
            entry.put("description", reply.description());
            if (reply.type() != null) {
                var schema = schema(() -> schemas.schema(reply.type(), "response " + reply.status()), label);
                if (v2) {
                    produces.addAll(reply.mediaTypes());
                    entry.put("schema", schema);
                } else {
                    var content = new LinkedHashMap<String, Object>();
                    reply.mediaTypes().forEach(type -> content.put(type, Schemas.object("schema", copy(schema))));
                    entry.put("content", content);
                }
            }
            responses.put(String.valueOf(reply.status()), entry);
        });
        if (responses.isEmpty()) {
            responses.put("default", Schemas.object("description", "No response is documented."));
        }
        if (v2 && !consumes.isEmpty()) {
            op.put("consumes", new ArrayList<Object>(consumes));
        }
        if (v2 && !produces.isEmpty()) {
            op.put("produces", new ArrayList<Object>(produces));
        }
        op.put("responses", responses);
        if (!doc.security().isEmpty()) {
            var requirements = new ArrayList<Object>();
            for (var name : doc.security()) {
                if (!config.schemes.containsKey(name)) {
                    throw Schemas.fail(label, "it requires the security scheme '" + name
                            + "', which is not defined; add it with Builder.securityScheme");
                }
                requirements.add(Schemas.object(name, new ArrayList<Object>()));
            }
            op.put("security", requirements);
        }
        return op;
    }

    private List<Object> parameters(Route route, RouteDoc doc, String label) {
        var captures = new ArrayList<String>();
        for (var segment : route.path().substring(1).split("/", -1)) {
            if (segment.startsWith(":") || segment.startsWith("*")) {
                captures.add(segment.substring(1));
            }
        }
        for (var param : doc.parameters()) {
            if (param.in() == RouteDoc.In.PATH && !captures.contains(param.name())) {
                throw Schemas.fail(label, "it documents the path parameter '" + param.name()
                        + "', which the route template does not capture");
            }
        }
        var out = new ArrayList<Object>();
        for (var name : captures) {
            var documented = doc.parameters().stream()
                    .filter(p -> p.in() == RouteDoc.In.PATH && p.name().equals(name)).findFirst()
                    .orElse(new RouteDoc.Param(RouteDoc.In.PATH, name, String.class, null, true));
            out.add(parameter(documented, label));
        }
        for (var param : doc.parameters()) {
            if (param.in() != RouteDoc.In.PATH) {
                out.add(parameter(param, label));
            }
        }
        return out;
    }

    private Map<String, Object> parameter(RouteDoc.Param param, String label) {
        var schema = schema(() -> schemas.simple(param.type(), "parameter '" + param.name() + "'"), label);
        var out = new LinkedHashMap<String, Object>();
        out.put("name", param.name());
        out.put("in", param.in().name().toLowerCase(java.util.Locale.ROOT));
        if (param.description() != null) {
            out.put("description", param.description());
        }
        if (param.required()) {
            out.put("required", true);
        }
        if (v2) {
            out.putAll(schema);
            if ("array".equals(schema.get("type")) && param.in() == RouteDoc.In.QUERY) {
                out.put("collectionFormat", "multi");
            }
        } else {
            out.put("schema", schema);
        }
        return out;
    }

    // ---- helpers

    /** Runs a schema generation and puts the route in front of any failure. */
    private static Map<String, Object> schema(java.util.function.Supplier<Map<String, Object>> generation, String label) {
        try {
            return generation.get();
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(label + ": " + e.getMessage(), e);
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> copy(Object schema) {
        return (Map<String, Object>) deepCopy(schema);
    }

    private static Object deepCopy(Object value) {
        if (value instanceof Map<?, ?> map) {
            var out = new LinkedHashMap<String, Object>();
            map.forEach((key, element) -> out.put((String) key, deepCopy(element)));
            return out;
        }
        if (value instanceof List<?> list) {
            var out = new ArrayList<Object>();
            list.forEach(element -> out.add(deepCopy(element)));
            return out;
        }
        return value;
    }

    /** Points every schema reference at the Swagger 2.0 location. */
    private static Object rewrite(Object value) {
        if (value instanceof Map<?, ?> map) {
            var out = new LinkedHashMap<String, Object>();
            map.forEach((key, element) -> {
                if ("$ref".equals(key) && element instanceof String ref) {
                    out.put("$ref", ref.replace("#/components/schemas/", "#/definitions/"));
                } else {
                    out.put((String) key, rewrite(element));
                }
            });
            return out;
        }
        if (value instanceof List<?> list) {
            var out = new ArrayList<Object>();
            list.forEach(element -> out.add(rewrite(element)));
            return out;
        }
        return value;
    }
}
