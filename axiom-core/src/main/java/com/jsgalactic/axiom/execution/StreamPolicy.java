package com.jsgalactic.axiom.execution;

/**
 * Immutable bound on the streams that may run at the same time. Each stream holds one slot from the
 * moment its response head is accepted until its body ends, is cancelled or is cut by shutdown. The
 * bound is separate from request admission: a stream leaves its request slot when it becomes a
 * stream, so open streams cannot take capacity away from ordinary requests. An aggregate bound
 * applies to every listener or test client, and a route override narrows it for that route. A new
 * stream is refused with 503 when either bound is full, before any stream bytes are written.
 *
 * @param maxActive maximum number of concurrently active streams
 */
public record StreamPolicy(int maxActive) {
  /**
   * Validates the bound before it can be installed.
   *
   * @param maxActive positive active limit
   * @throws IllegalArgumentException if the limit is not positive
   */
  public StreamPolicy {
    if (maxActive < 1) {
      throw new IllegalArgumentException("Stream capacity must be positive");
    }
  }

  /**
   * Configures immediate refusal when the active limit is occupied.
   *
   * @param maxActive positive active limit
   * @return policy that refuses a new stream with 503 when all slots are taken
   */
  public static StreamPolicy reject(int maxActive) {
    return new StreamPolicy(maxActive);
  }
}
