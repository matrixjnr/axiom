package io.axiom.server.internal;

import io.axiom.context.Handler;
import io.axiom.http.Request;
import io.axiom.routing.Route;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeSet;

/** Immutable segment trie. All mutable construction state is discarded after compilation. */
final class CompiledRouter {
    private final Node root;
    private final Map<String, Node> exactPaths;

    private CompiledRouter(Node root, Map<String, Node> exactPaths) {
        this.root = root;
        this.exactPaths = Map.copyOf(exactPaths);
    }

    static CompiledRouter compile(Map<Route, Handler> registrations) {
        var nodes = new ArrayList<Builder>();
        var root = new Builder(nodes);
        var exact = new HashMap<String, Builder>();
        registrations.forEach((route, handler) -> {
            var node = root;
            var segments = route.path().substring(1).split("/", -1);
            var captures = new ArrayList<Capture>();
            for (int i = 0; i < segments.length; i++) {
                var segment = segments[i];
                if (segment.startsWith(":")) {
                    captures.add(new Capture(segment.substring(1), i, false));
                    if (node.parameter == null) { node.parameter = new Builder(nodes); }
                    node = node.parameter;
                } else if (segment.startsWith("*")) {
                    captures.add(new Capture(segment.substring(1), i, true));
                    if (node.wildcard == null) { node.wildcard = new Builder(nodes); }
                    node = node.wildcard;
                } else {
                    node = node.literals.computeIfAbsent(segment, ignored -> new Builder(nodes));
                }
            }
            var endpoint = new Endpoint(route, handler, List.copyOf(captures));
            var previous = node.endpoints.putIfAbsent(route.method(), endpoint);
            if (previous != null) {
                throw new IllegalArgumentException("Ambiguous routes for " + route.method()
                        + ": " + previous.route().path() + " and " + route.path());
            }
            if (captures.isEmpty()) { exact.put(route.path(), node); }
        });

        // Children are created after their parents. Freeze bottom-up without recursive calls.
        for (int i = nodes.size() - 1; i >= 0; i--) {
            var builder = nodes.get(i);
            var literals = new HashMap<String, Node>();
            builder.literals.forEach((segment, child) -> literals.put(segment, child.frozen));
            var methods = new TreeSet<>(builder.endpoints.keySet());
            if (methods.contains("GET")) { methods.add("HEAD"); }
            builder.frozen = new Node(Map.copyOf(literals),
                    builder.parameter == null ? null : builder.parameter.frozen,
                    builder.wildcard == null ? null : builder.wildcard.frozen,
                    Map.copyOf(builder.endpoints), List.copyOf(methods), String.join(", ", methods));
        }
        var exactPaths = new HashMap<String, Node>();
        exact.forEach((path, node) -> exactPaths.put(path, node.frozen));
        return new CompiledRouter(root.frozen, exactPaths);
    }

    /**
     * Finds the most specific complete path match registered for the request method.
     * Complete matches are visited in precedence order; one without the method is skipped
     * so a less specific template can serve it. When no complete match has the method, the
     * result reports a method mismatch whose Allow value is the union over all of them.
     */
    Match match(Request request) {
        var method = request.method();
        var exact = exactPaths.get(request.path());
        if (exact != null) {
            var endpoint = select(exact, method);
            if (endpoint != null) {
                // Static matches allocate neither capture boundaries nor parameter maps.
                return new Match(endpoint, null, null);
            }
        }
        var segments = new Segments(request.path());
        var pending = new ArrayDeque<Step>();
        pending.push(new Step(root, 0));
        Node mismatch = null;
        TreeSet<String> allowed = null;
        while (!pending.isEmpty()) {
            var step = pending.pop();
            var node = step.node();
            int index = step.index();
            if (index == segments.size()) {
                if (node.endpoints().isEmpty()) { continue; }
                var endpoint = select(node, method);
                if (endpoint != null) { return new Match(endpoint, null, segments); }
                if (mismatch == null) {
                    mismatch = node;
                } else {
                    if (allowed == null) { allowed = new TreeSet<>(mismatch.methods()); }
                    allowed.addAll(node.methods());
                }
                continue;
            }
            // Push in reverse precedence. Backtrack only when a branch cannot match the whole path.
            if (node.wildcard() != null) {
                pending.push(new Step(node.wildcard(), segments.size()));
            }
            if (node.parameter() != null && segments.starts[index] != segments.ends[index]) {
                pending.push(new Step(node.parameter(), index + 1));
            }
            if (!node.literals().isEmpty()) {
                var literal = node.literals().get(segments.value(index));
                if (literal != null) { pending.push(new Step(literal, index + 1)); }
            }
        }
        if (mismatch == null) { return null; }
        return new Match(null, allowed == null ? mismatch.allow() : String.join(", ", allowed), null);
    }

    /** HEAD uses an explicit HEAD endpoint on a node, or else that node's GET endpoint. */
    private static Endpoint select(Node node, String method) {
        var endpoint = node.endpoints().get(method);
        if (endpoint == null && method.equals("HEAD")) { endpoint = node.endpoints().get("GET"); }
        return endpoint;
    }

    /**
     * Immutable match result. Captures are extracted into an unmodifiable map when the match is
     * created, so a match holds no lazily initialized state and is safe to publish to any thread.
     */
    static final class Match {
        private final Endpoint endpoint;
        private final String allow;
        private final Map<String, String> parameters;

        private Match(Endpoint endpoint, String allow, Segments segments) {
            this.endpoint = endpoint;
            this.allow = allow;
            this.parameters = endpoint == null || endpoint.captures().isEmpty()
                    ? Map.of() : captures(endpoint, segments);
        }

        private static Map<String, String> captures(Endpoint endpoint, Segments segments) {
            var values = new LinkedHashMap<String, String>();
            for (var capture : endpoint.captures()) {
                values.put(capture.name(), capture.wildcard()
                        ? segments.path.substring(segments.starts[capture.index()])
                        : segments.value(capture.index()));
            }
            return Collections.unmodifiableMap(values);
        }

        boolean methodAllowed() { return endpoint != null; }
        String allow() { return allow; }
        Handler handler() { return endpoint.handler(); }
        Route route() { return endpoint.route(); }

        String parameter(String name) {
            var value = parameters.get(Objects.requireNonNull(name, "name"));
            if (value == null) { throw new IllegalArgumentException("Unknown path parameter: " + name); }
            return value;
        }

        Map<String, String> parameters() { return parameters; }
    }

    private record Capture(String name, int index, boolean wildcard) {}
    private record Endpoint(Route route, Handler handler, List<Capture> captures) {}
    private record Node(Map<String, Node> literals, Node parameter, Node wildcard,
                        Map<String, Endpoint> endpoints, List<String> methods, String allow) {}
    private record Step(Node node, int index) {}

    private static final class Builder {
        private final Map<String, Builder> literals = new HashMap<>();
        private final Map<String, Endpoint> endpoints = new HashMap<>();
        private Builder parameter;
        private Builder wildcard;
        private Node frozen;

        private Builder(List<Builder> nodes) { nodes.add(this); }
    }

    private static final class Segments {
        private final String path;
        private final int[] starts;
        private final int[] ends;

        private Segments(String path) {
            this.path = path;
            int count = 1;
            for (int i = 1; i < path.length(); i++) {
                if (path.charAt(i) == '/') { count++; }
            }
            starts = new int[count];
            ends = new int[count];
            int index = 0;
            starts[0] = 1;
            for (int i = 1; i < path.length(); i++) {
                if (path.charAt(i) == '/') {
                    ends[index] = i;
                    starts[++index] = i + 1;
                }
            }
            ends[index] = path.length();
        }

        private int size() { return starts.length; }
        private String value(int index) { return path.substring(starts[index], ends[index]); }
    }
}
