package io.axiom.server.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.axiom.context.Handler;
import io.axiom.http.Request;
import io.axiom.routing.Route;
import java.lang.reflect.Modifier;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class CompiledRouterTest {
    @Test
    void matchesHoldOnlyFinalStateAndExtractCapturesEagerly() {
        for (var field : CompiledRouter.Match.class.getDeclaredFields()) {
            assertThat(Modifier.isFinal(field.getModifiers())).as(field.getName()).isTrue();
        }
        var routes = new LinkedHashMap<Route, Handler>();
        routes.put(new Route("GET", "/users/:id/*rest"), ctx -> null);
        routes.put(new Route("GET", "/health"), ctx -> null);
        var router = CompiledRouter.compile(routes);

        var match = router.match(Request.get("/users/7/a/b"));
        assertThat(match.parameters()).containsExactly(Map.entry("id", "7"), Map.entry("rest", "a/b"));
        assertThat(match.parameters()).isSameAs(match.parameters());
        assertThat(match.parameter("rest")).isEqualTo("a/b");
        assertThatIllegalArgumentException().isThrownBy(() -> match.parameter("missing"));
        assertThatThrownBy(() -> match.parameters().put("id", "8")).isInstanceOf(UnsupportedOperationException.class);
        assertThat(router.match(Request.get("/health")).parameters()).isEmpty();
    }
}
