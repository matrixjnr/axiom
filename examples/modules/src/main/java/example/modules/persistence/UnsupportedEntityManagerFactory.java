package example.modules.persistence;

import jakarta.persistence.EntityManagerFactory;

/**
 * Provides no-op methods so the example can implement only the EntityManagerFactory operations it
 * uses.
 */
abstract class UnsupportedEntityManagerFactory implements EntityManagerFactory {
  @Override
  public jakarta.persistence.EntityManager createEntityManager() {
    throw new UnsupportedOperationException();
  }

  @Override
  public jakarta.persistence.EntityManager createEntityManager(java.util.Map<?, ?> p0) {
    throw new UnsupportedOperationException();
  }

  @Override
  public jakarta.persistence.EntityManager createEntityManager(
      jakarta.persistence.SynchronizationType p0) {
    throw new UnsupportedOperationException();
  }

  @Override
  public jakarta.persistence.EntityManager createEntityManager(
      jakarta.persistence.SynchronizationType p0, java.util.Map<?, ?> p1) {
    throw new UnsupportedOperationException();
  }

  @Override
  public jakarta.persistence.criteria.CriteriaBuilder getCriteriaBuilder() {
    throw new UnsupportedOperationException();
  }

  @Override
  public jakarta.persistence.metamodel.Metamodel getMetamodel() {
    throw new UnsupportedOperationException();
  }

  @Override
  public boolean isOpen() {
    throw new UnsupportedOperationException();
  }

  @Override
  public void close() {
    throw new UnsupportedOperationException();
  }

  @Override
  public java.lang.String getName() {
    throw new UnsupportedOperationException();
  }

  @Override
  public java.util.Map<java.lang.String, java.lang.Object> getProperties() {
    throw new UnsupportedOperationException();
  }

  @Override
  public jakarta.persistence.Cache getCache() {
    throw new UnsupportedOperationException();
  }

  @Override
  public jakarta.persistence.PersistenceUnitUtil getPersistenceUnitUtil() {
    throw new UnsupportedOperationException();
  }

  @Override
  public jakarta.persistence.PersistenceUnitTransactionType getTransactionType() {
    throw new UnsupportedOperationException();
  }

  @Override
  public jakarta.persistence.SchemaManager getSchemaManager() {
    throw new UnsupportedOperationException();
  }

  @Override
  public void addNamedQuery(java.lang.String p0, jakarta.persistence.Query p1) {
    throw new UnsupportedOperationException();
  }

  @Override
  public <T> T unwrap(java.lang.Class<T> p0) {
    throw new UnsupportedOperationException();
  }

  @Override
  public <T> void addNamedEntityGraph(java.lang.String p0, jakarta.persistence.EntityGraph<T> p1) {
    throw new UnsupportedOperationException();
  }

  @Override
  public <R>
      java.util.Map<java.lang.String, jakarta.persistence.TypedQueryReference<R>> getNamedQueries(
          java.lang.Class<R> p0) {
    throw new UnsupportedOperationException();
  }

  @Override
  public <E>
      java.util.Map<java.lang.String, jakarta.persistence.EntityGraph<? extends E>>
          getNamedEntityGraphs(java.lang.Class<E> p0) {
    throw new UnsupportedOperationException();
  }

  @Override
  public void runInTransaction(java.util.function.Consumer<jakarta.persistence.EntityManager> p0) {
    throw new UnsupportedOperationException();
  }

  @Override
  public <R> R callInTransaction(
      java.util.function.Function<jakarta.persistence.EntityManager, R> p0) {
    throw new UnsupportedOperationException();
  }
}
