package org.budgetanalyzer.transaction.repository;

import java.util.Collection;
import java.util.UUID;

/** Batch insertion operations for static saved-view memberships. */
public interface SavedViewTransactionBatchRepository {

  /**
   * Inserts memberships that are not already persisted.
   *
   * <p>Callers mutating an existing view must hold its pessimistic lifecycle lock. Creation may
   * call this method for a newly generated view identifier that has no competing writer.
   *
   * @return the number of membership rows inserted
   */
  int insertMissing(UUID viewId, Collection<Long> transactionIds);
}
