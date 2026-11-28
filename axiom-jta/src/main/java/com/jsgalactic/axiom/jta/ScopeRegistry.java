package com.jsgalactic.axiom.jta;

import com.jsgalactic.axiom.context.Context;
import java.lang.ref.Reference;
import java.lang.ref.ReferenceQueue;
import java.lang.ref.WeakReference;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Map;

/**
 * Per-request scope state, keyed by the identity of the {@link Context} the middleware received.
 * The weak keys and the explicit removal on completion keep one request's transaction from ever
 * being visible to another, without a thread local or a change to Axiom's core.
 */
final class ScopeRegistry {
  private static final Map<Identity, State> STATES = new HashMap<>();
  private static final ReferenceQueue<Context> STALE = new ReferenceQueue<>();

  private ScopeRegistry() {}

  static synchronized Scope enter(Context context, Manager manager, Duration timeout, int seconds) {
    purge();
    State state = STATES.computeIfAbsent(new Identity(context, STALE), key -> new State());
    Scope scope = new Scope(manager, timeout, seconds);
    try {
      scope.suspended = manager.suspend();
      if (seconds > 0) {
        manager.timeout(seconds);
      }
      manager.begin();
      scope.began = true;
      scope.active = true;
      scope.status = manager.status();
    } catch (ManagerFailure failure) {
      undoBegin(scope, failure);
      throw failure;
    }
    state.scopes.push(scope);
    state.status = scope.status;
    state.outcome = TransactionOutcome.PENDING;
    return scope;
  }

  private static void undoBegin(Scope scope, ManagerFailure failure) {
    if (scope.began) {
      scope.began = false;
      try {
        scope.manager.rollback();
      } catch (ManagerFailure rollbackFailure) {
        failure.addSuppressed(rollbackFailure);
      }
    }
    if (scope.suspended != null) {
      try {
        scope.manager.resume(scope.suspended);
        scope.suspended = null;
      } catch (ManagerFailure resumeFailure) {
        failure.addSuppressed(resumeFailure);
      }
    }
  }

  static synchronized void complete(Context context, Scope scope, boolean commit) {
    ManagerFailure failure = finish(scope, commit);
    State state = find(context);
    if (state != null && !state.scopes.isEmpty() && state.scopes.peek() == scope) {
      state.scopes.pop();
      Scope outer = state.scopes.peek();
      state.status = outer == null ? scope.status : outer.status;
      state.outcome = outer == null ? scope.outcome : TransactionOutcome.PENDING;
    }
    if (failure != null) {
      throw failure;
    }
  }

  private static ManagerFailure finish(Scope scope, boolean commit) {
    ManagerFailure failure = null;
    if (commit) {
      try {
        scope.manager.commit();
        scope.outcome = TransactionOutcome.COMMITTED;
      } catch (ManagerFailure commitFailure) {
        failure = commitFailure;
        scope.outcome = TransactionOutcome.FAILED;
        try {
          scope.manager.rollback();
        } catch (ManagerFailure rollbackFailure) {
          failure.addSuppressed(rollbackFailure);
        }
      }
    } else {
      try {
        scope.manager.rollback();
        scope.outcome = TransactionOutcome.ROLLED_BACK;
      } catch (ManagerFailure rollbackFailure) {
        failure = rollbackFailure;
        scope.outcome = TransactionOutcome.FAILED;
      }
    }
    scope.status =
        scope.outcome == TransactionOutcome.COMMITTED
            ? TransactionStatus.COMMITTED
            : scope.outcome == TransactionOutcome.ROLLED_BACK
                ? TransactionStatus.ROLLED_BACK
                : TransactionStatus.UNKNOWN;
    scope.active = false;
    if (scope.suspended != null) {
      try {
        scope.manager.resume(scope.suspended);
        scope.suspended = null;
      } catch (ManagerFailure resumeFailure) {
        if (failure == null) {
          failure = resumeFailure;
        } else {
          failure.addSuppressed(resumeFailure);
        }
      }
    }
    return failure;
  }

  static synchronized void marked(Scope scope) {
    scope.rollbackOnly = true;
    scope.status = TransactionStatus.MARKED_ROLLBACK;
  }

  static synchronized Scope current(State state) {
    return state.scopes.peek();
  }

  static synchronized boolean active(Context context) {
    State state = find(context);
    Scope scope = state == null ? null : state.scopes.peek();
    return scope != null && scope.active;
  }

  static synchronized TransactionStatus status(Context context) {
    State state = find(context);
    return state == null ? TransactionStatus.INACTIVE : state.status;
  }

  static synchronized boolean rollbackOnly(Context context) {
    State state = find(context);
    Scope scope = state == null ? null : state.scopes.peek();
    return scope != null && scope.rollsBack();
  }

  static synchronized TransactionContext facade(Context context) {
    State state = find(context);
    return state == null ? null : state.facade;
  }

  static synchronized void remove(Context context) {
    STATES.remove(new Identity(context));
    purge();
  }

  private static State find(Context context) {
    purge();
    return STATES.get(new Identity(context));
  }

  private static void purge() {
    Reference<? extends Context> stale;
    while ((stale = STALE.poll()) != null) {
      STATES.remove(stale);
    }
  }

  /** A weak key with identity equality, so two equal-but-distinct contexts cannot share state. */
  private static final class Identity extends WeakReference<Context> {
    private final int hash;

    Identity(Context context) {
      super(context);
      hash = System.identityHashCode(context);
    }

    Identity(Context context, ReferenceQueue<Context> queue) {
      super(context, queue);
      hash = System.identityHashCode(context);
    }

    @Override
    public int hashCode() {
      return hash;
    }

    @Override
    public boolean equals(Object other) {
      if (this == other) {
        return true;
      }
      if (!(other instanceof Identity identity)) {
        return false;
      }
      Context referent = get();
      return referent != null && referent == identity.get();
    }
  }

  /** The scopes of one request, innermost first, and the public facade over them. */
  static final class State {
    final TransactionContext facade = new TransactionContext(this);
    final Deque<Scope> scopes = new ArrayDeque<>();

    TransactionStatus status = TransactionStatus.INACTIVE;
    TransactionOutcome outcome = TransactionOutcome.PENDING;
  }
}
