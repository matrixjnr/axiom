package com.jsgalactic.axiom.jta;

import com.jsgalactic.axiom.context.Context;
import com.jsgalactic.axiom.context.Middleware;
import com.jsgalactic.axiom.error.AxiomException;
import com.jsgalactic.axiom.error.ConflictException;
import com.jsgalactic.axiom.error.InternalServerErrorException;
import com.jsgalactic.axiom.http.Response;
import com.jsgalactic.axiom.jta.spi.JtaTransactionLookup;
import java.io.Serial;
import java.time.Duration;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Predicate;

/**
 * Opt-in JTA transaction boundaries around Axiom handlers.
 *
 * <pre>{@code
 * var jta = Jta.builder(applicationOwnedTransactionManager).timeout(Duration.ofSeconds(5)).build();
 * app.use(jta.transaction());
 * }</pre>
 *
 * <p>The application supplies its own {@code jakarta.transaction.TransactionManager} as an {@link
 * Object}; it is checked when the builder is created and adapted inside this module. No JTA type
 * appears in an Axiom signature, and no transaction manager is bundled or published here. Nothing
 * is bound until {@link #transaction()} is registered as middleware.
 *
 * <p>Each boundary suspends any transaction already associated with the thread, begins a new one
 * for the request, and commits when the chain returns a non-problem response or rolls back when it
 * throws, answers a problem status or sets rollback-only. Completion always restores the previously
 * associated transaction, including when nested boundaries run on the same request. Failures become
 * fixed-code problem responses with no provider message or class name; their cause stays available
 * to the framework's logs. Instances are immutable and thread-safe after construction.
 */
public final class Jta implements JtaTransactionLookup {
  /** Header an error handler can use to see how a request's transaction ended. */
  static final String OUTCOME_HEADER = "X-Axiom-Transaction";

  private final Manager manager;
  private final Duration timeout;
  private final int timeoutSeconds;
  private final List<Rule> rules;
  private final Map<TransactionFailure, Integer> problemStatuses;
  private final Middleware middleware = new Boundary();

  private Jta(
      Manager manager,
      Duration timeout,
      int timeoutSeconds,
      List<Rule> rules,
      Map<TransactionFailure, Integer> problemStatuses) {
    this.manager = manager;
    this.timeout = timeout;
    this.timeoutSeconds = timeoutSeconds;
    this.rules = rules;
    this.problemStatuses = problemStatuses;
    JtaLookupService.register(this);
  }

  /**
   * Starts a configuration around the application's transaction manager. A value that is not a JTA
   * {@code TransactionManager} is rejected here, with an exception naming the expected type but not
   * the supplied object.
   *
   * @param transactionManager application-owned transaction manager, supplied as an {@link Object}
   * @return a builder
   * @throws IllegalArgumentException if the value is not a JTA transaction manager
   */
  public static Builder builder(Object transactionManager) {
    return new Builder(transactionManager);
  }

  /**
   * Returns the middleware that binds one transaction around each request that runs through it. The
   * same thread-safe instance may be registered globally or on one route, and more than once to
   * create nested, independent scopes.
   *
   * @return transaction-boundary middleware
   */
  public Middleware transaction() {
    return middleware;
  }

  /**
   * Finds the request's transaction context, available to a handler while a boundary runs and to an
   * error handler just after it ends. The lookup uses the request's context object, not a thread
   * local, so a later request on the same thread cannot see this one's transaction.
   *
   * @param context the request's Axiom context
   * @return the transaction context, if a boundary ran for this request
   * @throws NullPointerException if the context is null
   */
  public static Optional<TransactionContext> context(Context context) {
    return TransactionContext.from(context);
  }

  @Override
  public boolean active(Object context) {
    return context instanceof Context request && ScopeRegistry.active(request);
  }

  @Override
  public String status(Object context) {
    return context instanceof Context request
        ? statusOf(request).name()
        : TransactionStatus.INACTIVE.name();
  }

  @Override
  public boolean rollbackOnly(Object context) {
    return context instanceof Context request && ScopeRegistry.rollbackOnly(request);
  }

  TransactionStatus statusOf(Context context) {
    return ScopeRegistry.status(context);
  }

  private AxiomException problem(ManagerFailure failure) {
    TransactionFailure kind = failure.kind();
    int status = problemStatuses.getOrDefault(kind, defaultStatus(kind));
    AxiomException result =
        switch (status) {
          case 409 -> new ConflictException("transaction_conflict");
          case 503 -> new Unavailable();
          default -> new InternalServerErrorException("transaction_failed");
        };
    result.initCause(failure.getCause() == null ? failure : failure.getCause());
    return result;
  }

  private static int defaultStatus(TransactionFailure failure) {
    return switch (failure) {
      case COMMIT -> 409;
      case BEGIN, ROLLBACK, TIMEOUT -> 503;
      case SUSPEND, RESUME, MARK, INACTIVE -> 500;
    };
  }

  /** Configures an immutable {@link Jta}; not thread-safe, build once at startup. */
  public static final class Builder {
    private final Manager manager;
    private final List<Rule> rules = new ArrayList<>();
    private final Map<TransactionFailure, Integer> problemStatuses =
        new EnumMap<>(TransactionFailure.class);
    private Duration timeout = Duration.ZERO;
    private int timeoutSeconds;

    private Builder(Object transactionManager) {
      manager = new Manager(transactionManager);
    }

    /**
     * Sets the transaction timeout. JTA expresses it in whole seconds, so a sub-second value is
     * rounded up and a value under one second still asks for one second. The default leaves the
     * provider's own timeout alone.
     *
     * @param timeout positive duration of at most one day
     * @return this builder
     * @throws IllegalArgumentException for an invalid duration
     */
    public Builder timeout(Duration timeout) {
      Objects.requireNonNull(timeout, "timeout");
      if (timeout.isZero() || timeout.isNegative() || timeout.compareTo(Duration.ofDays(1)) > 0) {
        throw new IllegalArgumentException(
            "Transaction timeout must be positive and at most one day");
      }
      this.timeout = timeout;
      timeoutSeconds = (int) Math.max(1, Math.ceilDiv(timeout.toNanos(), 1_000_000_000L));
      return this;
    }

    /**
     * Adds a rule that rolls back when the chain throws a throwable of this type. Rules run in the
     * order they were added and a later matching rule wins over an earlier one. The built-in rule
     * rolls back on every throwable and stays in force when no added rule matches.
     *
     * @param type throwable type to match, including subclasses
     * @return this builder
     */
    public Builder rollbackOn(Class<? extends Throwable> type) {
      rules.add(new Rule(requireType(type)::isInstance, true));
      return this;
    }

    /**
     * Adds a rule that commits when the chain throws a throwable of this type. The exception still
     * propagates; this rule is for failures an application error handler maps to a successful
     * answer, and has no effect on a problem response returned by the chain itself.
     *
     * @param type throwable type to match, including subclasses
     * @return this builder
     */
    public Builder noRollbackOn(Class<? extends Throwable> type) {
      rules.add(new Rule(requireType(type)::isInstance, false));
      return this;
    }

    /**
     * Adds a predicate-based rollback rule; a later matching rule wins, as with {@link
     * #rollbackOn(Class)}.
     *
     * @param predicate thread-safe test on the throwable leaving the chain
     * @return this builder
     */
    public Builder rollbackWhen(Predicate<Throwable> predicate) {
      rules.add(new Rule(Objects.requireNonNull(predicate, "predicate"), true));
      return this;
    }

    /**
     * Adds a predicate-based commit rule; a later matching rule wins, as with {@link
     * #noRollbackOn(Class)}.
     *
     * @param predicate thread-safe test on the throwable leaving the chain
     * @return this builder
     */
    public Builder commitWhen(Predicate<Throwable> predicate) {
      rules.add(new Rule(Objects.requireNonNull(predicate, "predicate"), false));
      return this;
    }

    /**
     * Overrides the problem status for one kind of transaction-boundary failure. Only 409, 503 and
     * 500 are accepted, and every mapped response uses a fixed code from that status.
     *
     * @param failure the boundary operation to map
     * @param status 409, 503 or 500
     * @return this builder
     * @throws IllegalArgumentException for another status
     */
    public Builder problemStatus(TransactionFailure failure, int status) {
      Objects.requireNonNull(failure, "failure");
      if (status != 409 && status != 500 && status != 503) {
        throw new IllegalArgumentException("Transaction problem status must be 409, 500 or 503");
      }
      problemStatuses.put(failure, status);
      return this;
    }

    /**
     * Builds the immutable adapter. It starts no transaction and registers no route.
     *
     * @return a thread-safe transaction boundary
     */
    public Jta build() {
      return new Jta(
          manager, timeout, timeoutSeconds, List.copyOf(rules), Map.copyOf(problemStatuses));
    }

    private static Class<? extends Throwable> requireType(Class<? extends Throwable> type) {
      return Objects.requireNonNull(type, "type");
    }
  }

  private record Rule(Predicate<Throwable> matcher, boolean rollback) {}

  /** A 503 with a transaction code and no client-chosen Retry-After value. */
  private static final class Unavailable extends AxiomException {
    @Serial private static final long serialVersionUID = 1L;

    Unavailable() {
      super(503, "transaction_unavailable");
    }
  }

  private final class Boundary implements Middleware {
    @Override
    public Response handle(Context context, Next next) throws Exception {
      Scope scope;
      try {
        scope = ScopeRegistry.enter(context, manager, timeout, timeoutSeconds);
      } catch (ManagerFailure failedBegin) {
        throw problem(failedBegin);
      }
      Throwable failure = null;
      boolean rollback = false;
      try {
        Response response = next.run();
        rollback = response.status() >= 400 || ScopeRegistry.rollbackOnly(context);
        return response;
      } catch (Throwable thrown) {
        failure = thrown;
        rollback = rollbackFor(thrown) || ScopeRegistry.rollbackOnly(context);
        throw thrown;
      } finally {
        ManagerFailure completion = null;
        try {
          ScopeRegistry.complete(context, scope, !rollback);
        } catch (ManagerFailure failedCompletion) {
          completion = failedCompletion;
          if (failure != null) {
            failure.addSuppressed(failedCompletion);
          }
        }
        if (failure == null) {
          ScopeRegistry.remove(context);
          if (completion != null) {
            throw problem(completion);
          }
        }
      }
    }

    @Override
    public Response afterError(Context context, Response response) {
      TransactionContext transaction = TransactionContext.from(context).orElse(null);
      ScopeRegistry.remove(context);
      if (transaction == null) {
        return response;
      }
      return response.withHeader(
          OUTCOME_HEADER, transaction.outcome().name().toLowerCase(Locale.ROOT));
    }

    private boolean rollbackFor(Throwable failure) {
      boolean rollback = true;
      for (Rule rule : rules) {
        try {
          if (rule.matcher().test(failure)) {
            rollback = rule.rollback();
          }
        } catch (RuntimeException badRule) {
          failure.addSuppressed(badRule);
          return true;
        }
      }
      return rollback;
    }
  }
}
