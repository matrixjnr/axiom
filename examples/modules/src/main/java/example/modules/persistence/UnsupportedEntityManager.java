package example.modules.persistence;

import jakarta.persistence.EntityManager;

/**
 * Provides no-op methods so the example can implement only the EntityManager operations it uses.
 */
abstract class UnsupportedEntityManager implements EntityManager {
  @Override
  public void persist(java.lang.Object p0) {
    throw new UnsupportedOperationException();
  }

  @Override
  public <T> T merge(T p0) {
    throw new UnsupportedOperationException();
  }

  @Override
  public void remove(java.lang.Object p0) {
    throw new UnsupportedOperationException();
  }

  @Override
  public <T> T find(java.lang.Class<T> p0, java.lang.Object p1) {
    throw new UnsupportedOperationException();
  }

  @Override
  public <T> T find(
      java.lang.Class<T> p0,
      java.lang.Object p1,
      java.util.Map<java.lang.String, java.lang.Object> p2) {
    throw new UnsupportedOperationException();
  }

  @Override
  public <T> T find(
      java.lang.Class<T> p0, java.lang.Object p1, jakarta.persistence.LockModeType p2) {
    throw new UnsupportedOperationException();
  }

  @Override
  public <T> T find(
      java.lang.Class<T> p0,
      java.lang.Object p1,
      jakarta.persistence.LockModeType p2,
      java.util.Map<java.lang.String, java.lang.Object> p3) {
    throw new UnsupportedOperationException();
  }

  @Override
  public <T> T find(
      java.lang.Class<T> p0, java.lang.Object p1, jakarta.persistence.FindOption... p2) {
    throw new UnsupportedOperationException();
  }

  @Override
  public <T> T find(
      jakarta.persistence.EntityGraph<T> p0,
      java.lang.Object p1,
      jakarta.persistence.FindOption... p2) {
    throw new UnsupportedOperationException();
  }

  @Override
  public <T> T getReference(java.lang.Class<T> p0, java.lang.Object p1) {
    throw new UnsupportedOperationException();
  }

  @Override
  public <T> T getReference(T p0) {
    throw new UnsupportedOperationException();
  }

  @Override
  public void flush() {
    throw new UnsupportedOperationException();
  }

  @Override
  public void setFlushMode(jakarta.persistence.FlushModeType p0) {
    throw new UnsupportedOperationException();
  }

  @Override
  public jakarta.persistence.FlushModeType getFlushMode() {
    throw new UnsupportedOperationException();
  }

  @Override
  public void lock(java.lang.Object p0, jakarta.persistence.LockModeType p1) {
    throw new UnsupportedOperationException();
  }

  @Override
  public void lock(
      java.lang.Object p0,
      jakarta.persistence.LockModeType p1,
      java.util.Map<java.lang.String, java.lang.Object> p2) {
    throw new UnsupportedOperationException();
  }

  @Override
  public void lock(
      java.lang.Object p0,
      jakarta.persistence.LockModeType p1,
      jakarta.persistence.LockOption... p2) {
    throw new UnsupportedOperationException();
  }

  @Override
  public void refresh(java.lang.Object p0) {
    throw new UnsupportedOperationException();
  }

  @Override
  public void refresh(java.lang.Object p0, java.util.Map<java.lang.String, java.lang.Object> p1) {
    throw new UnsupportedOperationException();
  }

  @Override
  public void refresh(java.lang.Object p0, jakarta.persistence.LockModeType p1) {
    throw new UnsupportedOperationException();
  }

  @Override
  public void refresh(
      java.lang.Object p0,
      jakarta.persistence.LockModeType p1,
      java.util.Map<java.lang.String, java.lang.Object> p2) {
    throw new UnsupportedOperationException();
  }

  @Override
  public void refresh(java.lang.Object p0, jakarta.persistence.RefreshOption... p1) {
    throw new UnsupportedOperationException();
  }

  @Override
  public void clear() {
    throw new UnsupportedOperationException();
  }

  @Override
  public void detach(java.lang.Object p0) {
    throw new UnsupportedOperationException();
  }

  @Override
  public boolean contains(java.lang.Object p0) {
    throw new UnsupportedOperationException();
  }

  @Override
  public jakarta.persistence.LockModeType getLockMode(java.lang.Object p0) {
    throw new UnsupportedOperationException();
  }

  @Override
  public void setCacheRetrieveMode(jakarta.persistence.CacheRetrieveMode p0) {
    throw new UnsupportedOperationException();
  }

  @Override
  public void setCacheStoreMode(jakarta.persistence.CacheStoreMode p0) {
    throw new UnsupportedOperationException();
  }

  @Override
  public jakarta.persistence.CacheRetrieveMode getCacheRetrieveMode() {
    throw new UnsupportedOperationException();
  }

  @Override
  public jakarta.persistence.CacheStoreMode getCacheStoreMode() {
    throw new UnsupportedOperationException();
  }

  @Override
  public void setProperty(java.lang.String p0, java.lang.Object p1) {
    throw new UnsupportedOperationException();
  }

  @Override
  public java.util.Map<java.lang.String, java.lang.Object> getProperties() {
    throw new UnsupportedOperationException();
  }

  @Override
  public jakarta.persistence.Query createQuery(java.lang.String p0) {
    throw new UnsupportedOperationException();
  }

  @Override
  public <T> jakarta.persistence.TypedQuery<T> createQuery(
      jakarta.persistence.criteria.CriteriaQuery<T> p0) {
    throw new UnsupportedOperationException();
  }

  @Override
  public <T> jakarta.persistence.TypedQuery<T> createQuery(
      jakarta.persistence.criteria.CriteriaSelect<T> p0) {
    throw new UnsupportedOperationException();
  }

  @Override
  public jakarta.persistence.Query createQuery(jakarta.persistence.criteria.CriteriaUpdate<?> p0) {
    throw new UnsupportedOperationException();
  }

  @Override
  public jakarta.persistence.Query createQuery(jakarta.persistence.criteria.CriteriaDelete<?> p0) {
    throw new UnsupportedOperationException();
  }

  @Override
  public <T> jakarta.persistence.TypedQuery<T> createQuery(
      java.lang.String p0, java.lang.Class<T> p1) {
    throw new UnsupportedOperationException();
  }

  @Override
  public jakarta.persistence.Query createNamedQuery(java.lang.String p0) {
    throw new UnsupportedOperationException();
  }

  @Override
  public <T> jakarta.persistence.TypedQuery<T> createNamedQuery(
      java.lang.String p0, java.lang.Class<T> p1) {
    throw new UnsupportedOperationException();
  }

  @Override
  public <T> jakarta.persistence.TypedQuery<T> createQuery(
      jakarta.persistence.TypedQueryReference<T> p0) {
    throw new UnsupportedOperationException();
  }

  @Override
  public jakarta.persistence.Query createNativeQuery(java.lang.String p0) {
    throw new UnsupportedOperationException();
  }

  @Override
  public <T> jakarta.persistence.Query createNativeQuery(
      java.lang.String p0, java.lang.Class<T> p1) {
    throw new UnsupportedOperationException();
  }

  @Override
  public jakarta.persistence.Query createNativeQuery(java.lang.String p0, java.lang.String p1) {
    throw new UnsupportedOperationException();
  }

  @Override
  public jakarta.persistence.StoredProcedureQuery createNamedStoredProcedureQuery(
      java.lang.String p0) {
    throw new UnsupportedOperationException();
  }

  @Override
  public jakarta.persistence.StoredProcedureQuery createStoredProcedureQuery(java.lang.String p0) {
    throw new UnsupportedOperationException();
  }

  @Override
  public jakarta.persistence.StoredProcedureQuery createStoredProcedureQuery(
      java.lang.String p0, java.lang.Class<?>... p1) {
    throw new UnsupportedOperationException();
  }

  @Override
  public jakarta.persistence.StoredProcedureQuery createStoredProcedureQuery(
      java.lang.String p0, java.lang.String... p1) {
    throw new UnsupportedOperationException();
  }

  @Override
  public void joinTransaction() {
    throw new UnsupportedOperationException();
  }

  @Override
  public boolean isJoinedToTransaction() {
    throw new UnsupportedOperationException();
  }

  @Override
  public <T> T unwrap(java.lang.Class<T> p0) {
    throw new UnsupportedOperationException();
  }

  @Override
  public java.lang.Object getDelegate() {
    throw new UnsupportedOperationException();
  }

  @Override
  public void close() {
    throw new UnsupportedOperationException();
  }

  @Override
  public boolean isOpen() {
    throw new UnsupportedOperationException();
  }

  @Override
  public jakarta.persistence.EntityTransaction getTransaction() {
    throw new UnsupportedOperationException();
  }

  @Override
  public jakarta.persistence.EntityManagerFactory getEntityManagerFactory() {
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
  public <T> jakarta.persistence.EntityGraph<T> createEntityGraph(java.lang.Class<T> p0) {
    throw new UnsupportedOperationException();
  }

  @Override
  public jakarta.persistence.EntityGraph<?> createEntityGraph(java.lang.String p0) {
    throw new UnsupportedOperationException();
  }

  @Override
  public jakarta.persistence.EntityGraph<?> getEntityGraph(java.lang.String p0) {
    throw new UnsupportedOperationException();
  }

  @Override
  public <T> java.util.List<jakarta.persistence.EntityGraph<? super T>> getEntityGraphs(
      java.lang.Class<T> p0) {
    throw new UnsupportedOperationException();
  }

  @Override
  public <C> void runWithConnection(jakarta.persistence.ConnectionConsumer<C> p0) {
    throw new UnsupportedOperationException();
  }

  @Override
  public <C, T> T callWithConnection(jakarta.persistence.ConnectionFunction<C, T> p0) {
    throw new UnsupportedOperationException();
  }
}
