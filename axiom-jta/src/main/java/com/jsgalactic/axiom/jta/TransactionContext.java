package com.jsgalactic.axiom.jta;

import com.jsgalactic.axiom.context.Context;
import java.time.Duration;
import java.util.Objects;
import java.util.Optional;

/**
 * The transaction view handed to handlers inside a {@link Jta#transaction()} boundary.
 *
 * <p>There is one instance per request and it is reached through {@link Jta#context}, using the
 * request's existing Axiom context rather than a thread local. It is valid only on the request
 * thread until the boundary returns. When boundaries are nested it reports the innermost active
 * transaction; once all boundaries finish it reports their final outcome and is no longer active.
 * Every method uses plain Java types, so neither this class nor its callers need the JTA API.
 */
public final class TransactionContext {
  private final ScopeRegistry.State state;

  TransactionContext(ScopeRegistry.State state) {
    this.state = state;
  }

  /**
   * Reports whether a transaction is currently associated with this request.
   *
   * @return true only while the innermost boundary has an active transaction
   */
  public boolean active() {
    Scope scope = ScopeRegistry.current(state);
    return scope != null && scope.active;
  }

  /**
   * Returns the status of the innermost transaction, or the status left by the last completed one.
   * A provider status with no equivalent here is {@link TransactionStatus#UNKNOWN}; this method
   * never throws because the provider cannot be queried.
   *
   * @return plain transaction status
   */
  public TransactionStatus status() {
    return state.status;
  }

  /**
   * Reports whether the innermost transaction has been marked for rollback, either by {@link
   * #setRollbackOnly()} or by the provider.
   *
   * @return true when the current transaction must be rolled back
   */
  public boolean rollbackOnly() {
    Scope scope = ScopeRegistry.current(state);
    return scope != null && scope.rollsBack();
  }

  /**
   * Marks the innermost transaction rollback-only. The boundary still completes normally, but rolls
   * back instead of committing. This is a request-local operation: it cannot affect a suspended
   * outer transaction or another request.
   *
   * @throws IllegalStateException if no transaction is active or the provider refuses the mark
   */
  public void setRollbackOnly() {
    Scope scope = ScopeRegistry.current(state);
    if (scope == null || !scope.active) {
      throw new IllegalStateException("No active transaction for this request");
    }
    try {
      scope.manager.markRollbackOnly();
    } catch (ManagerFailure failure) {
      throw new IllegalStateException("Unable to mark the transaction rollback-only", failure);
    }
    ScopeRegistry.marked(scope);
    state.status = TransactionStatus.MARKED_ROLLBACK;
  }

  /**
   * Returns the timeout configured for the innermost boundary, if one was set.
   *
   * @return configured timeout, or empty for the provider's default
   */
  public Optional<Duration> timeout() {
    Scope scope = ScopeRegistry.current(state);
    return scope == null ? Optional.empty() : Optional.of(scope.timeout);
  }

  /**
   * Returns the outcome of the transaction that last completed for this request. It is {@link
   * TransactionOutcome#PENDING} while a boundary is running and stays available after completion so
   * an error handler can decorate its response.
   *
   * @return plain completion outcome
   */
  public TransactionOutcome outcome() {
    return state.outcome;
  }

  /**
   * Finds the transaction context attached to a request's context, if a boundary is running or has
   * just finished for that request.
   *
   * @param context the request's Axiom context
   * @return the request's transaction context, if any
   * @throws NullPointerException if the context is null
   */
  public static Optional<TransactionContext> from(Context context) {
    Objects.requireNonNull(context, "context");
    return Optional.ofNullable(ScopeRegistry.facade(context));
  }
}
