package io.axiom.server;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.axiom.Axiom;
import io.axiom.http.InvalidRequestPathException;
import io.axiom.http.Request;
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
        Collections.shuffle(routes, new Random(31));
        try (var app = Axiom.create()) {
            for (var route : routes) {
                app.route(route.method(), route.path(), ctx -> new Captured(ctx.route().path(), ctx.pathParameters()));
            }
            app.start();
            var paths = new ArrayList<String>();
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
                for (var method : List.of("GET", "HEAD", "POST", "DELETE")) {
                    // No HEAD routes are registered, so HEAD selects the best GET match.
                    var served = method.equals("HEAD") ? "GET" : method;
                    var expected = complete.stream().filter(match -> match.template().method().equals(served))
                            .findFirst().orElse(null);
                    var actual = app.handle(new Request(method, path));
                    int status = complete.isEmpty() ? 404 : expected == null ? 405 : 200;
                    assertThat(actual.status()).as("%s %s", method, path).isEqualTo(status);
                    if (status == 200 && !method.equals("HEAD")) {
                        assertThat(actual.body()).as("%s %s", method, path)
                                .isEqualTo(new Captured(expected.template().path(), expected.parameters()));
                    } else if (status == 200) {
                        assertThat(app.resolve(new Request(method, path))).as("%s %s", method, path)
                                .contains(new io.axiom.routing.Route("GET", expected.template().path()));
                    } else if (status == 405) {
                        assertThat(actual.headers()).as("%s %s", method, path)
                                .containsEntry("Allow", String.join(", ", allowed));
                    }
                }
            }
        }
    }

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
