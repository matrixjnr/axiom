package com.jsgalactic.axiom.jpa;

import com.jsgalactic.axiom.context.Context;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityTransaction;

/** One request's EntityManager and the transaction state the middleware owns for it. */
final class Binding {
  final Context context;
  final EntityManager entityManager;
  final Binding previous;
  EntityTransaction transaction;
  boolean joined;
  boolean finished;

  Binding(Context context, EntityManager entityManager, Binding previous) {
    this.context = context;
    this.entityManager = entityManager;
    this.previous = previous;
  }
}
