package org.budgetanalyzer.transaction.repository;

import java.util.Collection;
import java.util.UUID;

/** Batch insertion operations for static saved-view memberships. */
public interface SavedViewTransactionBatchRepository {

  /** Inserts memberships, ignoring associations that already exist. */
  void insertAll(UUID viewId, Collection<Long> transactionIds);
}
