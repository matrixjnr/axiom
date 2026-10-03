package com.jsgalactic.axiom.server.internal.execution;

/**
 * The metric tags of one endpoint: its HTTP method and route template, never a request path.
 * Requests that match no route share {@code none} and {@code unmatched}; endpoints beyond the
 * dispatcher's bound share {@code other}.
 *
 * @param method HTTP method of the registered route
 * @param route registered route template
 */
public record EndpointTag(String method, String route) { }
