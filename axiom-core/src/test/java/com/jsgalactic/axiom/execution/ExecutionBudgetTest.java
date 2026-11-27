package com.jsgalactic.axiom.execution;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.jsgalactic.axiom.routing.Route;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ExecutionBudgetTest {
  @Test
  void defaultsKeepTheExistingBoundAndNoOverrides() {
    var budget = ExecutionBudget.defaults();
    assertThat(budget.aggregate()).isEqualTo(AdmissionPolicy.reject(36));
    assertThat(budget.routePolicies()).isEmpty();
    assertThat(budget.policy(new Route("GET", "/any"))).isEqualTo(AdmissionPolicy.reject(36));
  }

  @Test
  void copiesOverridesAndResolvesTheEffectivePolicy() {
    var route = new Route("GET", "/reports/:id");
    var other = new Route("GET", "/health");
    var aggregate = new AdmissionPolicy(8, 16, Duration.ofSeconds(1));
    var override = AdmissionPolicy.reject(1);
    var mutable = new HashMap<Route, AdmissionPolicy>();
    mutable.put(route, override);
    var budget = new ExecutionBudget(aggregate, mutable);
    mutable.clear();
    assertThat(budget.routePolicies()).containsExactly(Map.entry(route, override));
    assertThat(budget.policy(route)).isEqualTo(override);
    assertThat(budget.policy(other)).isEqualTo(aggregate);
    assertThatThrownBy(() -> budget.routePolicies().clear())
        .isInstanceOf(UnsupportedOperationException.class);
  }

  @Test
  void withRouteReplacesOnlyOneOverride() {
    var route = new Route("GET", "/slow");
    var other = new Route("POST", "/slow");
    var first = AdmissionPolicy.reject(1);
    var second = AdmissionPolicy.reject(2);
    var budget = ExecutionBudget.defaults().withRoute(route, first).withRoute(other, first);
    var changed = budget.withRoute(route, second);
    assertThat(changed.policy(route)).isEqualTo(second);
    assertThat(changed.policy(other)).isEqualTo(first);
    assertThat(budget.policy(route)).isEqualTo(first);
    var removed = changed.withoutRoute(route).withoutRoute(new Route("GET", "/absent"));
    assertThat(removed.policy(route)).isEqualTo(AdmissionPolicy.reject(36));
    assertThat(removed.policy(other)).isEqualTo(first);
  }

  @Test
  void rejectsNullPoliciesAndOverrides() {
    var route = new Route("GET", "/null");
    assertThatThrownBy(() -> ExecutionBudget.of(null)).isInstanceOf(NullPointerException.class);
    assertThatThrownBy(() -> new ExecutionBudget(AdmissionPolicy.reject(1), null))
        .isInstanceOf(NullPointerException.class);
    var nullable = new HashMap<Route, AdmissionPolicy>();
    nullable.put(route, null);
    assertThatThrownBy(() -> new ExecutionBudget(AdmissionPolicy.reject(1), nullable))
        .isInstanceOf(NullPointerException.class);
    assertThatThrownBy(() -> ExecutionBudget.defaults().withRoute(route, null))
        .isInstanceOf(NullPointerException.class);
  }
}
