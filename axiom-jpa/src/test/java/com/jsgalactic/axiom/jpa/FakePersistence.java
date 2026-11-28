package com.jsgalactic.axiom.jpa;

import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.EntityTransaction;
import jakarta.persistence.FlushModeType;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Proxy-backed JPA fakes that record every call, so tests need no provider or database. */
final class FakePersistence {
  private final List<String> events = Collections.synchronizedList(new ArrayList<>());
  private final List<EntityManager> created = Collections.synchronizedList(new ArrayList<>());
  private final EntityManagerFactory factory;
  private int count;

  private FakePersistence() {
    factory =
        (EntityManagerFactory)
            Proxy.newProxyInstance(
                FakePersistence.class.getClassLoader(),
                new Class<?>[] {EntityManagerFactory.class},
                this::factoryCall);
  }

  static FakePersistence create() {
    return new FakePersistence();
  }

  EntityManagerFactory factory() {
    return factory;
  }

  EntityManager last() {
    synchronized (created) {
      return created.get(created.size() - 1);
    }
  }

  List<String> events() {
    synchronized (events) {
      return List.copyOf(events);
    }
  }

  private Object factoryCall(Object proxy, Method method, Object[] arguments) {
    switch (method.getName()) {
      case "createEntityManager":
        events.add("factory.create");
        var manager = new FakeEntityManager(++count, events);
        created.add(manager.entityManager());
        return manager.entityManager();
      case "isOpen":
        return true;
      case "toString":
        return "factory";
      case "hashCode":
        return System.identityHashCode(proxy);
      case "equals":
        return proxy == arguments[0];
      default:
        return defaultValue(method.getReturnType());
    }
  }

  /** One EntityManager and its resource-local transaction, with identity-based event names. */
  private static final class FakeEntityManager {
    private final String name;
    private final List<String> events;
    private final EntityManager entityManager;
    private final EntityTransaction transaction;
    private boolean active;
    private boolean closed;

    FakeEntityManager(int id, List<String> events) {
      name = "em" + id;
      this.events = events;
      entityManager =
          (EntityManager)
              Proxy.newProxyInstance(
                  FakePersistence.class.getClassLoader(),
                  new Class<?>[] {EntityManager.class},
                  this::managerCall);
      transaction =
          (EntityTransaction)
              Proxy.newProxyInstance(
                  FakePersistence.class.getClassLoader(),
                  new Class<?>[] {EntityTransaction.class},
                  this::transactionCall);
    }

    EntityManager entityManager() {
      return entityManager;
    }

    private Object managerCall(Object proxy, Method method, Object[] arguments) {
      switch (method.getName()) {
        case "getTransaction":
          return transaction;
        case "joinTransaction":
          events.add(name + ".join");
          return null;
        case "close":
          events.add(name + ".close");
          closed = true;
          active = false;
          return null;
        case "isOpen":
          return !closed;
        case "setFlushMode":
          events.add(name + ".flush=" + ((FlushModeType) arguments[0]).name());
          return null;
        case "toString":
          return name;
        case "hashCode":
          return System.identityHashCode(proxy);
        case "equals":
          return proxy == arguments[0];
        default:
          return defaultValue(method.getReturnType());
      }
    }

    private Object transactionCall(Object proxy, Method method, Object[] arguments) {
      switch (method.getName()) {
        case "begin":
          requireInactive();
          active = true;
          events.add(name + ".begin");
          return null;
        case "commit":
          requireActive();
          active = false;
          events.add(name + ".commit");
          return null;
        case "rollback":
          requireActive();
          active = false;
          events.add(name + ".rollback");
          return null;
        case "isActive":
          return active;
        case "setRollbackOnly":
          events.add(name + ".rollbackOnly");
          return null;
        case "getRollbackOnly":
          return false;
        case "toString":
          return name + ".transaction";
        case "hashCode":
          return System.identityHashCode(proxy);
        case "equals":
          return proxy == arguments[0];
        default:
          return defaultValue(method.getReturnType());
      }
    }

    private void requireActive() {
      if (!active) {
        throw new IllegalStateException("Transaction is not active");
      }
    }

    private void requireInactive() {
      if (active) {
        throw new IllegalStateException("Transaction is already active");
      }
    }
  }

  private static Object defaultValue(Class<?> type) {
    if (!type.isPrimitive()) {
      return null;
    }
    if (type == boolean.class) {
      return false;
    }
    if (type == char.class) {
      return '\0';
    }
    if (type == byte.class) {
      return (byte) 0;
    }
    if (type == short.class) {
      return (short) 0;
    }
    if (type == int.class) {
      return 0;
    }
    if (type == long.class) {
      return 0L;
    }
    if (type == float.class) {
      return 0f;
    }
    return 0d;
  }
}
