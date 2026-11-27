package com.jsgalactic.axiom.execution;

import com.jsgalactic.axiom.routing.Route;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Immutable application-wide execution budget. The aggregate policy bounds requests from every
 * listener and test client of one application; a route override bounds that route in addition to
 * the aggregate. The counters behind the budget belong to the application, so no listener has a
 * private copy of its capacity.
 *
 * @param aggregate bounds all executions and supplies the policy of routes without an override
 * @param routePolicies immutable additional bounds keyed by registered route identity
 */
public record ExecutionBudget(
    AdmissionPolicy aggregate, Map<Route, AdmissionPolicy> routePolicies) {
  /**
   * Validates the aggregate and copies the overrides into an immutable map, so later changes to a
   * caller's map cannot change this budget.
   */
  public ExecutionBudget {
    Objects.requireNonNull(aggregate, "aggregate");
    routePolicies = Map.copyOf(Objects.requireNonNull(routePolicies, "routePolicies"));
  }

  /**
   * Creates a budget with no route overrides.
   *
   * @param aggregate bounds all executions of the application
   * @return budget whose every route uses the aggregate policy
   */
  public static ExecutionBudget of(AdmissionPolicy aggregate) {
    return new ExecutionBudget(aggregate, Map.of());
  }

  /**
   * Creates the default budget: no queue, 36 active executions and no route overrides.
   *
   * @return the unchanged default budget
   */
  public static ExecutionBudget defaults() {
    return of(AdmissionPolicy.reject(36));
  }

  /**
   * Returns the policy that bounds a route: its override when present, otherwise the aggregate.
   *
   * @param route registered route identity
   * @return effective admission policy
   */
  public AdmissionPolicy policy(Route route) {
    Objects.requireNonNull(route, "route");
    return routePolicies.getOrDefault(route, aggregate);
  }

  /**
   * Returns a budget with one route bound replaced, leaving the aggregate and other overrides as
   * they are.
   *
   * @param route route identity whose bound is replaced
   * @param policy new bound for that route
   * @return a new budget; this one is unchanged
   */
  public ExecutionBudget withRoute(Route route, AdmissionPolicy policy) {
    Objects.requireNonNull(route, "route");
    Objects.requireNonNull(policy, "policy");
    var updated = new LinkedHashMap<>(routePolicies);
    updated.put(route, policy);
    return new ExecutionBudget(aggregate, updated);
  }

  /**
   * Returns a budget without the bound of one route, leaving the aggregate and other overrides as
   * they are. Removing an override that is not present has no effect.
   *
   * @param route route identity whose override is removed
   * @return a new budget; this one is unchanged
   */
  public ExecutionBudget withoutRoute(Route route) {
    Objects.requireNonNull(route, "route");
    if (!routePolicies.containsKey(route)) {
      return this;
    }
    var updated = new LinkedHashMap<>(routePolicies);
    updated.remove(route);
    return new ExecutionBudget(aggregate, updated);
  }
}
