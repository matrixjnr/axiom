package com.jsgalactic.axiom.jpa;

import com.jsgalactic.axiom.context.Context;
import com.jsgalactic.axiom.context.Middleware;
import jakarta.persistence.EntityManagerFactory;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;

/**
 * Per-request JPA EntityManager and transaction boundaries for Axiom applications.
 *
 * <pre>{@code
 * var jpa = Jpa.builder().entityManagerFactory(applicationOwnedFactory).build();
 * var managers = jpa.entityManagers();
 * app.use(jpa.transaction());
 * app.get("/items", ctx -> managers.get(ctx));
 * }</pre>
 *
 * <p>The application owns the {@code EntityManagerFactory} and closes it. The value is accepted as
 * {@link Object} so no JPA type appears in an Axiom signature; {@link Builder#build()} rejects a
 * value that is not a factory with {@link IllegalArgumentException}. Nothing is opened, registered
 * or changed until {@link #transaction()} or {@link #readOnlyTransaction()} is registered as
 * middleware. The middleware opens one EntityManager for each request, binds it to that request's
 * {@link Context}, runs the rest of the chain, and commits or rolls back before closing it. A
 * request that already carries an active JTA transaction, reported by the callback supplied to
 * {@link Builder#jta(JtaTransactionLookup)}, joins that transaction instead of beginning a local
 * one.
 *
 * <p>Instances are immutable and thread-safe. The factory must be thread-safe, as required by JPA.
 */
public final class Jpa {
  private final EntityManagerFactory factory;
  private final EntityManagers managers;
  private final JtaTransactionLookup jta;
  private final boolean readOnly;
  private final Set<Class<?>> rollbackRules;
  private final Set<Class<?>> commitRules;

  private Jpa(Builder builder) {
    factory = (EntityManagerFactory) builder.factory;
    managers = EntityManagers.create();
    jta = builder.jta;
    readOnly = builder.readOnly;
    rollbackRules = Set.copyOf(builder.rollbackRules);
    commitRules = Set.copyOf(builder.commitRules);
  }

  /**
   * Starts a configuration that will use the supplied factory.
   *
   * @return a new builder
   */
  public static Builder builder() {
    return new Builder();
  }

  /**
   * Returns this instance's request-scoped EntityManager accessor. Different {@code Jpa} instances
   * have different accessors, and an accessor only sees requests served by its own middleware.
   *
   * @return a thread-safe accessor to pass to handlers
   */
  public EntityManagers entityManagers() {
    return managers;
  }

  /**
   * Returns the EntityManager bound to this request by this instance's middleware.
   *
   * @param context the request being served
   * @param <T> type inferred by the caller, normally {@code jakarta.persistence.EntityManager}
   * @return the request's EntityManager
   * @throws IllegalStateException if the transaction middleware is not active for this request
   */
  public <T> T entityManager(Context context) {
    return managers.get(context);
  }

  /**
   * Returns middleware that gives each request its own EntityManager and resource-local
   * transaction. A response below 400 commits; an exception, a response of 400 or above, or a
   * problem response rolls back. The EntityManager is closed on every path. A configured read-only
   * setting makes this middleware behave like {@link #readOnlyTransaction()}.
   *
   * @return new middleware; registration is what activates it
   */
  public Middleware transaction() {
    return new JpaTransaction(factory, managers, jta, readOnly, rollbackRules, commitRules);
  }

  /**
   * Returns middleware that gives each request its own EntityManager in read-only mode. Flushing is
   * deferred and the resource-local transaction is rolled back when the chain returns, so no write
   * survives the request; an exception rolls it back as well. A joined JTA transaction is left to
   * its owner.
   *
   * @return new middleware; registration is what activates it
   */
  public Middleware readOnlyTransaction() {
    return new JpaTransaction(factory, managers, jta, true, rollbackRules, commitRules);
  }

  /** Configures a {@link Jpa}; instances are not thread-safe and are used during configuration. */
  public static final class Builder {
    private Object factory;
    private JtaTransactionLookup jta;
    private boolean readOnly;
    private final Set<Class<?>> rollbackRules = new LinkedHashSet<>();
    private final Set<Class<?>> commitRules = new LinkedHashSet<>();

    private Builder() {}

    /**
     * Sets the application-owned factory. A value that is not an {@code
     * jakarta.persistence.EntityManagerFactory} is rejected by {@link #build()}. The factory is
     * never closed by this module.
     *
     * @param factory application-owned factory
     * @return this builder
     */
    public Builder entityManagerFactory(Object factory) {
      this.factory = Objects.requireNonNull(factory, "factory");
      return this;
    }

    /**
     * Sets a callback that reports an active JTA transaction for the current request. When it
     * returns true the EntityManager joins that transaction and no local transaction is begun,
     * committed or rolled back by this middleware. The callback is supplied by the application or
     * by its JTA adapter; this module has no JTA dependency.
     *
     * @param jta request-scoped JTA transaction lookup
     * @return this builder
     */
    public Builder jta(JtaTransactionLookup jta) {
      this.jta = Objects.requireNonNull(jta, "jta");
      return this;
    }

    /**
     * Makes {@link #transaction()} use read-only semantics: deferred flushing and a rollback when
     * the chain returns. {@link #readOnlyTransaction()} has the same effect without changing this
     * setting.
     *
     * @return this builder
     */
    public Builder readOnly() {
      readOnly = true;
      return this;
    }

    /**
     * Adds exception types that must roll back. A type matches its own class and subclasses; the
     * nearest matching type wins over a {@link #commitOn(Class[]) commitOn} type of the same class.
     * By default every exception rolls back.
     *
     * @param types exception classes
     * @return this builder
     */
    @SafeVarargs
    public final Builder rollbackOn(Class<? extends Throwable>... types) {
      for (var type : types) {
        rollbackRules.add(Objects.requireNonNull(type, "type"));
      }
      return this;
    }

    /**
     * Adds exception types that must commit. A type matches its own class and subclasses; a more
     * specific {@link #rollbackOn(Class[]) rollbackOn} type wins. Exceptions that match no rule
     * roll back.
     *
     * @param types exception classes
     * @return this builder
     */
    @SafeVarargs
    public final Builder commitOn(Class<? extends Throwable>... types) {
      for (var type : types) {
        commitRules.add(Objects.requireNonNull(type, "type"));
      }
      return this;
    }

    /**
     * Creates an immutable adapter and its request-scoped accessor. No EntityManager is opened and
     * no middleware is registered here.
     *
     * @return a reusable adapter
     * @throws IllegalStateException if no factory was supplied
     * @throws IllegalArgumentException if the factory is not an {@code EntityManagerFactory}
     */
    public Jpa build() {
      if (factory == null) {
        throw new IllegalStateException("An EntityManagerFactory is required");
      }
      if (!(factory instanceof EntityManagerFactory)) {
        throw new IllegalArgumentException(
            "factory must be a jakarta.persistence.EntityManagerFactory");
      }
      return new Jpa(this);
    }
  }
}
