package com.jsgalactic.axiom.server;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.jsgalactic.axiom.Axiom;
import com.jsgalactic.axiom.execution.AdmissionPolicy;
import com.jsgalactic.axiom.execution.ExecutionBudget;
import com.jsgalactic.axiom.routing.Route;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ExecutionBudgetTest {
    @Test void admissionPolicyMethodsAreViewsOfTheBudget() {
        try (var app = Axiom.create()) {
            var route = app.get("/reports/:id", ctx -> "report");
            var other = app.get("/health", ctx -> "ok");
            var aggregate = new AdmissionPolicy(4, 8, Duration.ofSeconds(1));
            var override = AdmissionPolicy.reject(1);
            assertThat(app.executionBudget()).isEqualTo(ExecutionBudget.defaults());
            app.admissionPolicy(aggregate).admissionPolicy(route, override);
            assertThat(app.executionBudget().aggregate()).isEqualTo(aggregate);
            assertThat(app.admissionPolicy()).isEqualTo(aggregate);
            assertThat(app.executionBudget(route)).isEqualTo(override);
            assertThat(app.admissionPolicy(route)).isEqualTo(override);
            assertThat(app.executionBudget(other)).isEqualTo(aggregate);
        }
    }

    @Test void replacesTheWholeBudgetAndValidatesRoutes() {
        try (var app = Axiom.create()) {
            var route = app.get("/slow", ctx -> "slow");
            var aggregate = AdmissionPolicy.reject(6);
            var override = AdmissionPolicy.reject(2);
            app.executionBudget(new ExecutionBudget(aggregate, Map.of(route, override)));
            assertThat(app.executionBudget().aggregate()).isEqualTo(aggregate);
            assertThat(app.executionBudget().routePolicies()).containsExactly(Map.entry(route, override));
            assertThatThrownBy(() -> app.executionBudget(
                    new ExecutionBudget(aggregate, Map.of(new Route("GET", "/missing"), override))))
                    .isInstanceOf(IllegalArgumentException.class);
            app.executionBudget(route, AdmissionPolicy.reject(3));
            assertThat(app.executionBudget(route)).isEqualTo(AdmissionPolicy.reject(3));
            app.start();
            assertThat(app.executionBudget(route)).isEqualTo(AdmissionPolicy.reject(3));
            assertThatThrownBy(() -> app.executionBudget(ExecutionBudget.defaults()))
                    .isInstanceOf(IllegalStateException.class);
            assertThatThrownBy(() -> app.executionBudget(route, aggregate)).isInstanceOf(IllegalStateException.class);
        }
    }
}
