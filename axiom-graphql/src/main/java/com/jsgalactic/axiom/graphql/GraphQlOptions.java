package com.jsgalactic.axiom.graphql;

/**
 * Bounds and switches of a {@link GraphQl} handler, supplied as plain data.
 *
 * @param maxBodyBytes maximum request body in bytes, 1 to 1048576
 * @param maxDepth maximum query depth enforced by graphql-java, 1 to 100
 * @param maxComplexity maximum query complexity enforced by graphql-java, 1 to 100000
 * @param introspection whether {@code __schema} and {@code __type} may be queried
 * @param getQueries whether queries (never mutations) may be sent as GET with query parameters
 */
public record GraphQlOptions(
    int maxBodyBytes, int maxDepth, int maxComplexity, boolean introspection, boolean getQueries) {
  /** Bounded defaults: 64 KiB body, depth 10, complexity 100, no introspection, POST only. */
  public static final GraphQlOptions DEFAULT = new GraphQlOptions(64 * 1024, 10, 100, false, false);

  /**
   * Validates the bounds so a handler can never be built with unbounded work.
   *
   * @throws IllegalArgumentException if a bound is outside its range
   */
  public GraphQlOptions {
    if (maxBodyBytes < 1 || maxBodyBytes > 1024 * 1024) {
      throw new IllegalArgumentException("maxBodyBytes must be between 1 and 1048576");
    }
    if (maxDepth < 1 || maxDepth > 100) {
      throw new IllegalArgumentException("maxDepth must be between 1 and 100");
    }
    if (maxComplexity < 1 || maxComplexity > 100_000) {
      throw new IllegalArgumentException("maxComplexity must be between 1 and 100000");
    }
  }
}
