package com.jsgalactic.axiom.jpa;

import com.jsgalactic.axiom.context.Context;
import jakarta.persistence.EntityManager;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Request-scoped accessor for the EntityManager bound by {@link Jpa#transaction()}.
 *
 * <p>An instance is obtained from a {@link Jpa} instance with {@link Jpa#entityManagers()} and may
 * be shared by every handler of an application. It holds no thread-local state: the key is the
 * request's own {@link Context}, which Axiom creates once per request and passes to middleware,
 * handlers and error handlers alike. The entry is removed when the middleware finishes, so an
 * EntityManager cannot leak into a later request on the same thread or be seen after the response.
 *
 * <p>The return type is inferred from the assignment so that no JPA type appears in an Axiom
 * signature:
 *
 * <pre>{@code
 * EntityManager entityManager = managers.get(context);
 * }</pre>
 *
 * <p>Instances are thread-safe; a caller must use the returned EntityManager only on the request
 * thread and only until its handler returns, as required by JPA.
 */
public final class EntityManagers {
  private final Map<Context, Binding> active = new IdentityHashMap<>();

  private EntityManagers() {}

  static EntityManagers create() {
    return new EntityManagers();
  }

  /**
   * Returns the EntityManager bound to this request.
   *
   * @param context the request being served
   * @param <T> the type inferred by the caller, normally {@code jakarta.persistence.EntityManager}
   * @return the request's EntityManager, cast to the inferred type
   * @throws IllegalStateException if no JPA transaction middleware is active for this request
   * @throws ClassCastException if the caller asks for a type the EntityManager does not have
   */
  public <T> T get(Context context) {
    Objects.requireNonNull(context, "context");
    Binding binding;
    synchronized (active) {
      binding = active.get(context);
    }
    if (binding == null) {
      throw new IllegalStateException("No EntityManager is bound to this request");
    }
    @SuppressWarnings("unchecked")
    T value = (T) binding.entityManager;
    return value;
  }

  /**
   * Reports whether an EntityManager is bound to this request.
   *
   * @param context the request being served
   * @return true while JPA transaction middleware is active for this request
   */
  public boolean has(Context context) {
    Objects.requireNonNull(context, "context");
    synchronized (active) {
      return active.containsKey(context);
    }
  }

  Binding bind(Context context, EntityManager entityManager) {
    synchronized (active) {
      var binding = new Binding(context, entityManager, active.get(context));
      active.put(context, binding);
      return binding;
    }
  }

  Binding current(Context context) {
    synchronized (active) {
      return active.get(context);
    }
  }

  void finish(Binding binding) {
    synchronized (active) {
      binding.finished = true;
      if (active.get(binding.context) == binding) {
        if (binding.previous == null) {
          active.remove(binding.context);
        } else {
          active.put(binding.context, binding.previous);
        }
      }
    }
  }
}
