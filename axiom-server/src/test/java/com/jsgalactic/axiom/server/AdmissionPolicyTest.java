package com.jsgalactic.axiom.server;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.jsgalactic.axiom.Axiom;
import com.jsgalactic.axiom.execution.AdmissionPolicy;
import com.jsgalactic.axiom.http.Request;
import com.jsgalactic.axiom.routing.Route;
import java.time.Duration;
import org.junit.jupiter.api.Test;

class AdmissionPolicyTest {
    @Test void resolvesStableTemplatesAndFreezesPoliciesWithRoutes() {
        try (var app = Axiom.create()) {
            assertThat(app.admissionPolicy()).isEqualTo(AdmissionPolicy.reject(36));
            var route = app.get("/users/:id", ctx -> "user");
            var literal = app.get("/users/me", ctx -> "me");
            var defaultPolicy = new AdmissionPolicy(5, 10, Duration.ofSeconds(1));
            var routePolicy = AdmissionPolicy.reject(1);
            app.admissionPolicy(defaultPolicy).admissionPolicy(route, routePolicy);
            assertThatThrownBy(() -> app.admissionPolicy(new Route("GET", "/missing"), routePolicy))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> app.resolve(Request.get("/users/1"))).isInstanceOf(IllegalStateException.class);
            app.start();
            assertThat(app.resolve(Request.get("/users/a"))).contains(route);
            assertThat(app.resolve(Request.get("/users/b"))).contains(route);
            assertThat(app.resolve(Request.get("/users/me"))).contains(literal);
            assertThat(app.resolve(new Request("POST", "/users/me"))).isEmpty();
            assertThat(app.resolve(Request.get("/unknown"))).isEmpty();
            assertThat(app.admissionPolicy(route)).isEqualTo(routePolicy);
            assertThat(app.admissionPolicy(literal)).isEqualTo(defaultPolicy);
            assertThatThrownBy(() -> app.admissionPolicy(defaultPolicy)).isInstanceOf(IllegalStateException.class);
            assertThatThrownBy(() -> app.admissionPolicy(route, defaultPolicy)).isInstanceOf(IllegalStateException.class);
        }
    }
}
