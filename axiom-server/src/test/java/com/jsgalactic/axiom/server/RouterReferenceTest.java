package com.jsgalactic.axiom.server;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.jsgalactic.axiom.Axiom;
import com.jsgalactic.axiom.http.InvalidRequestPathException;
import com.jsgalactic.axiom.http.Request;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;

/** Compares the trie against an intentionally simple scan of complete route templates. */
class RouterReferenceTest {
    @Test
    void matchesReferenceAcrossOverlappingTemplatesAndRequestShapes() throws Exception {
        var routes = new ArrayList<Template>();
        var prefixes = List.of("");
        for (int depth = 0; depth < 3; depth++) {
            var next = new ArrayList<String>();
            for (var prefix : prefixes) {
                for (var segment : List.of("a", "b", ":p" + depth)) {
                    var path = prefix + "/" + segment;
                    next.add(path);
                    routes.add(new Template(routes.size() % 2 == 0 ? "GET" : "POST", path));
                    routes.add(new Template(routes.size() % 3 == 0 ? "GET" : "POST", path + "/*tail"));
                }
            }
            prefixes = next;
        }
        // Extension and OPTIONS routes on overlapping templates; none conflicts with the GET/POST shapes.
        for (var path : List.of("/a/:p1", "/:p0/b", "/b/*tail", "/:p0/:p1/a")) { routes.add(new Template("PROPFIND", path)); }
        for (var path : List.of("/a/b", "/:p0/*tail", "/b/:p1/:p2")) { routes.add(new Template("OPTIONS", path)); }
        Collections.shuffle(routes, new Random(31));
        try (var app = Axiom.create()) {
            for (var route : routes) {
                app.route(route.method(), route.path(), ctx -> new Captured(ctx.route().path(), ctx.pathParameters()));
            }
            app.start();
            var registered = new TreeSet<String>();
            routes.forEach(route -> registered.add(route.method()));
            var recognized = new TreeSet<>(List.of("GET", "HEAD", "POST", "PUT", "DELETE", "CONNECT", "OPTIONS",
                    "TRACE", "PATCH"));
            recognized.addAll(registered);
            // OPTIONS * lists every registered method, HEAD because GET is registered, and OPTIONS.
            var everywhere = new TreeSet<>(registered);
            everywhere.add("HEAD");
            everywhere.add("OPTIONS");
            var asterisk = app.handle(new Request("OPTIONS", "*"));
            assertThat(asterisk.status()).isEqualTo(204);
            assertThat(asterisk.headers()).containsEntry("Allow", String.join(", ", everywhere));
            var paths = new ArrayList<String>();
            int combinations = 0;
            prefixes = List.of("");
            for (int depth = 0; depth < 4; depth++) {
                var next = new ArrayList<String>();
                for (var prefix : prefixes) {
                    for (var segment : List.of("a", "b", "c", "")) { next.add(prefix + "/" + segment); }
                }
                paths.addAll(next);
                prefixes = next;
            }
            for (var path : paths) {
                if (path.contains("//")) {
                    // Interior empty segments are rejected before routing.
                    assertThatThrownBy(() -> Request.get(path)).isInstanceOf(InvalidRequestPathException.class);
                    continue;
                }
                // Rank complete matches lexicographically: literals 2, parameters 1, wildcards 0.
                var complete = routes.stream().map(route -> scan(route, path)).filter(match -> match != null)
                        .sorted(Comparator.comparing(Reference::rank).reversed()).toList();
                var allowed = new TreeSet<String>();
                complete.forEach(match -> allowed.add(match.template().method()));
                if (allowed.contains("GET")) { allowed.add("HEAD"); }
                // Every routed path answers OPTIONS, with a route or automatically, so 405 lists it.
                allowed.add("OPTIONS");
                for (var method : METHODS) {
                    combinations++;
                    // No HEAD routes are registered, so HEAD selects the best GET match.
                    var served = method.equals("HEAD") ? "GET" : method;
                    var expected = complete.stream().filter(match -> match.template().method().equals(served))
                            .findFirst().orElse(null);
                    var actual = app.handle(new Request(method, path));
                    int status = method.equals("CONNECT") ? 501
                            : complete.isEmpty() ? (recognized.contains(method) ? 404 : 501)
                            : expected != null ? 200 : method.equals("OPTIONS") ? 204 : 405;
                    assertThat(actual.status()).as("%s %s", method, path).isEqualTo(status);
                    if (status == 204) {
                        var withOptions = new TreeSet<>(allowed);
                        withOptions.add("OPTIONS");
                        assertThat(actual.headers()).as("%s %s", method, path)
                                .containsExactly(Map.entry("Allow", String.join(", ", withOptions)));
                        assertThat(app.resolve(new Request(method, path))).as("%s %s", method, path).isEmpty();
                    } else if (status == 404 || status == 501) {
                        assertThat(actual.headers()).as("%s %s", method, path).doesNotContainKey("Allow");
                    } else if (status == 200 && !method.equals("HEAD")) {
                        assertThat(actual.body()).as("%s %s", method, path)
                                .isEqualTo(new Captured(expected.template().path(), expected.parameters()));
                    } else if (status == 200) {
                        assertThat(app.resolve(new Request(method, path))).as("%s %s", method, path)
                                .contains(new com.jsgalactic.axiom.routing.Route("GET", expected.template().path()));
                    } else if (status == 405) {
                        assertThat(actual.headers()).as("%s %s", method, path)
                                .containsEntry("Allow", String.join(", ", allowed));
                    }
                }
            }
            assertThat(combinations).isEqualTo(1600);
        }
    }

    /**
     * Standard methods, one that is never routed (TRACE), one that is never supported (CONNECT), a
     * registered extension method, an unrecognized one, and a lowercase spelling of GET.
     */
    private static final List<String> METHODS =
            List.of("GET", "HEAD", "POST", "DELETE", "OPTIONS", "TRACE", "CONNECT", "PROPFIND", "FOO", "get");

    private static Reference scan(Template template, String path) {
        var pattern = template.path().substring(1).split("/", -1);
        var values = path.substring(1).split("/", -1);
        var parameters = new LinkedHashMap<String, String>();
        var rank = new StringBuilder();
        for (int i = 0; i < pattern.length; i++) {
            if (i >= values.length) { return null; }
            var segment = pattern[i];
            if (segment.startsWith("*")) {
                parameters.put(segment.substring(1), String.join("/", Arrays.copyOfRange(values, i, values.length)));
                return new Reference(template, rank.append('0').toString(), parameters);
            }
            if (segment.startsWith(":")) {
                if (values[i].isEmpty()) { return null; }
                parameters.put(segment.substring(1), values[i]);
                rank.append('1');
            } else {
                if (!segment.equals(values[i])) { return null; }
                rank.append('2');
            }
        }
        return pattern.length == values.length ? new Reference(template, rank.toString(), parameters) : null;
    }

    private record Template(String method, String path) {}
    private record Reference(Template template, String rank, Map<String, String> parameters) {}
    private record Captured(String template, Map<String, String> parameters) {}
}
