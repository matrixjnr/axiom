package com.jsgalactic.axiom.server;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.jsgalactic.axiom.Axiom;
import com.jsgalactic.axiom.execution.StreamPolicy;
import com.jsgalactic.axiom.routing.Route;
import org.junit.jupiter.api.Test;

class StreamPolicyTest {
    @Test void resolvesStableTemplatesAndFreezesPoliciesWithRoutes() {
        try (var app = Axiom.create()) {
            assertThat(app.streamPolicy()).isEqualTo(StreamPolicy.reject(36));
            var route = app.get("/events/:id", ctx -> "events");
            var literal = app.get("/events/live", ctx -> "live");
            var defaultPolicy = StreamPolicy.reject(5);
            var routePolicy = StreamPolicy.reject(1);
            app.streamPolicy(defaultPolicy).streamPolicy(route, routePolicy);
            assertThat(app.streamPolicy(route)).isEqualTo(routePolicy);
            assertThat(app.streamPolicy(literal)).isEqualTo(defaultPolicy);
            assertThatThrownBy(() -> app.streamPolicy(new Route("GET", "/missing"), routePolicy))
                    .isInstanceOf(IllegalArgumentException.class);
            app.start();
            assertThat(app.streamPolicy(route)).isEqualTo(routePolicy);
            assertThat(app.streamPolicy(literal)).isEqualTo(defaultPolicy);
            assertThatThrownBy(() -> app.streamPolicy(defaultPolicy)).isInstanceOf(IllegalStateException.class);
            assertThatThrownBy(() -> app.streamPolicy(route, defaultPolicy)).isInstanceOf(IllegalStateException.class);
        }
    }
}
