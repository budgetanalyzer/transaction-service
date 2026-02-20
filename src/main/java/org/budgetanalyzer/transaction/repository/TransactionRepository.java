package org.budgetanalyzer.transaction.repository;

import java.util.Set;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import org.budgetanalyzer.core.repository.SoftDeleteOperations;
import org.budgetanalyzer.transaction.domain.Transaction;

public interface TransactionRepository
    extends JpaRepository<Transaction, Long>, SoftDeleteOperations<Transaction> {

  /**
   * Finds all duplicate keys (date|amount|description) that exist in the database.
   *
   * <p>Used for bulk duplicate detection during batch import. Returns the composite keys for any
   * transactions that match the provided set of keys.
   *
   * @param keys set of composite keys in format "date|amount|description"
   * @return set of keys that already exist in the database
   */
  @Query(
      value =
          """
      SELECT DISTINCT CONCAT(date, '|', amount, '|', description) as duplicate_key
      FROM transaction
      WHERE deleted = false
        AND CONCAT(date, '|', amount, '|', description) IN (:keys)
      """,
      nativeQuery = true)
  Set<String> findExistingDuplicateKeys(@Param("keys") Set<String> keys);
}
