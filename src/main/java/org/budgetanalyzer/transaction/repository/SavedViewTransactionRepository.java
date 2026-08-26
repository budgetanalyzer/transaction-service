package org.budgetanalyzer.transaction.repository;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import org.budgetanalyzer.transaction.domain.SavedViewTransaction;
import org.budgetanalyzer.transaction.domain.SavedViewTransactionId;

/** Repository for static saved-view membership associations. */
public interface SavedViewTransactionRepository
    extends JpaRepository<SavedViewTransaction, SavedViewTransactionId>,
        SavedViewTransactionBatchRepository {

  /** Count grouped by saved view. */
  interface SavedViewMembershipCount {

    /** Returns the saved-view identifier. */
    UUID getViewId();

    /** Returns the membership count. */
    long getTransactionCount();
  }

  /** Returns deterministic transaction IDs stored for a view. */
  @Query(
      "SELECT m.transactionId FROM SavedViewTransaction m "
          + "WHERE m.viewId = :viewId ORDER BY m.transactionId")
  List<Long> findTransactionIds(@Param("viewId") UUID viewId);

  /** Counts memberships for a view directly from the association table. */
  long countByViewId(UUID viewId);

  /** Counts memberships for each requested view directly from the association table. */
  @Query(
      "SELECT m.viewId AS viewId, COUNT(m.transactionId) AS transactionCount "
          + "FROM SavedViewTransaction m WHERE m.viewId IN :viewIds GROUP BY m.viewId")
  List<SavedViewMembershipCount> countByViewIds(@Param("viewIds") Collection<UUID> viewIds);

  /** Idempotently removes selected memberships from one view. */
  @Modifying
  @Query(
      "DELETE FROM SavedViewTransaction m "
          + "WHERE m.viewId = :viewId AND m.transactionId IN :transactionIds")
  int deleteByViewIdAndTransactionIdIn(
      @Param("viewId") UUID viewId, @Param("transactionIds") Collection<Long> transactionIds);

  /** Removes every membership referencing one or more transactions. */
  @Modifying
  @Query("DELETE FROM SavedViewTransaction m WHERE m.transactionId IN :transactionIds")
  int deleteByTransactionIdIn(@Param("transactionIds") Collection<Long> transactionIds);
}
