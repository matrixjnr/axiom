package io.axiom.routing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class RouteTest {
    @ParameterizedTest
    @ValueSource(strings = {"/", "/users/:id", "/:team/users/:user", "/files/*path", "/:_id/*Rest2",
            "/time/12:00", "/literal/a*b", "/%3Aid", "/:id/", "/*rest"})
    void acceptsAndPreservesValidTemplates(String path) {
        assertThat(new Route("GET", path).path()).isEqualTo(path);
    }

    @ParameterizedTest
    @ValueSource(strings = {"/:", "/*", "/:1id", "/:bad-name", "/:a:b", "/:a*", "/:é",
            "/:id/:id", "/:id/*id", "/a/*rest/b", "/a/*rest/", "/*one/*two", "/{id}",
            "//:id", "/a/../:id", "/a%2F:id"})
    void rejectsMalformedOrAmbiguousCaptureSyntax(String path) {
        assertThatIllegalArgumentException().isThrownBy(() -> new Route("GET", path));
    }
}
