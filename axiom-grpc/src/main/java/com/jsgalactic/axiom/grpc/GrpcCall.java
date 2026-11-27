package com.jsgalactic.axiom.grpc;

import com.jsgalactic.axiom.execution.ExecutionContext;
import io.grpc.Context;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * The deadline and cancellation of the gRPC call on the current thread. A generated service handler
 * reads it with {@link #current()}; callers that are not serving a gRPC call get an {@link
 * IllegalStateException}. Instances belong to one call and are safe to share with the threads that
 * call it.
 */
public final class GrpcCall {
  static final Context.Key<GrpcCall> KEY = Context.key("axiom.grpc.call");
  private static final Duration MAX_BUDGET = Duration.ofDays(1);

  private final Context context;
  private final ExecutionContext execution;
  private final AtomicBoolean cancelled = new AtomicBoolean();
  private final List<Runnable> listeners = new CopyOnWriteArrayList<>();

  private GrpcCall(Context context) {
    this.context = context;
    var deadline = context.getDeadline();
    long remaining =
        deadline == null ? MAX_BUDGET.toNanos() : deadline.timeRemaining(TimeUnit.NANOSECONDS);
    execution =
        ExecutionContext.create(
            Duration.ofNanos(Math.max(1, Math.min(MAX_BUDGET.toNanos(), remaining))));
  }

  /**
   * Returns the call bound to the current thread.
   *
   * @return the current call
   * @throws IllegalStateException if no gRPC call is attached to this thread
   */
  public static GrpcCall current() {
    var call = KEY.get();
    if (call == null) {
      throw new IllegalStateException("No gRPC call is attached to this thread");
    }
    return call;
  }

  /** Creates a view of a call and makes it the value of {@link #KEY} for the call's context. */
  static GrpcCall attach(Context context) {
    var call = new GrpcCall(context);
    context.addListener(observed -> call.cancel(), Runnable::run);
    return call;
  }

  /**
   * Returns the call's identity and original deadline budget as an Axiom execution context. It is
   * created when the call starts from the client's deadline (or one day when the client sent none),
   * so it never extends past that deadline and reports expiry independently of later clock reads.
   *
   * @return immutable execution metadata for this call
   */
  public ExecutionContext execution() {
    return execution;
  }

  /**
   * Returns the time left before the call's deadline, clamped at zero. A call with no deadline has
   * a one-day budget, matching {@link #execution()}.
   *
   * @return remaining time
   */
  public Duration remainingTime() {
    var deadline = context.getDeadline();
    if (deadline == null) {
      return execution.remainingTime();
    }
    return Duration.ofNanos(Math.max(0, deadline.timeRemaining(TimeUnit.NANOSECONDS)));
  }

  /**
   * Reports whether the client cancelled the call or its deadline elapsed.
   *
   * @return true once the call can no longer send a result
   */
  public boolean cancelled() {
    return cancelled.get();
  }

  /**
   * Runs an action once when {@link #cancelled()} becomes true. If the call is already cancelled
   * the action runs on the calling thread before this method returns. Actions run on whichever
   * thread observes the cancellation, so they must be quick and thread-safe.
   *
   * @param action what to run on cancellation
   * @throws NullPointerException if the action is null
   */
  public void onCancel(Runnable action) {
    Objects.requireNonNull(action, "action");
    var once = new AtomicBoolean();
    Runnable listener =
        () -> {
          if (once.compareAndSet(false, true)) {
            action.run();
          }
        };
    listeners.add(listener);
    if (cancelled.get()) {
      listener.run();
    }
  }

  private void cancel() {
    if (cancelled.compareAndSet(false, true)) {
      for (var listener : listeners) {
        listener.run();
      }
    }
  }
}
