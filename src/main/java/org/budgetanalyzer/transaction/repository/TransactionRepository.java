package org.budgetanalyzer.transaction.repository;

import java.util.Set;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import org.budgetanalyzer.core.repository.SoftDeleteOperations;
import org.budgetanalyzer.transaction.domain.Transaction;

public interface TransactionRepository
    extends JpaRepository<Transaction, Long>, SoftDeleteOperations<Transaction, Long> {

  /**
   * Finds all duplicate keys that exist in the database for a specific owner.
   *
   * <p>Used for bulk duplicate detection during batch import. Returns the composite keys for any
   * transactions that match the provided set of keys and belong to the specified owner.
   *
   * @param keys set of encoded composite duplicate keys
   * @param ownerId the ID of the transaction owner
   * @return set of keys that already exist in the database for this owner
   */
  @Query(
      value =
          """
      WITH duplicate_keys AS (
        SELECT DISTINCT CONCAT(
            CASE
              WHEN NULLIF(account_id, '') IS NULL THEN 'N'
              ELSE CONCAT('V', OCTET_LENGTH(CONVERT_TO(account_id, 'UTF8')), ':', account_id)
            END,
            '|',
            CONCAT('V', OCTET_LENGTH(CONVERT_TO(bank_name, 'UTF8')), ':', bank_name),
            '|',
            CONCAT('V', OCTET_LENGTH(CONVERT_TO(date::text, 'UTF8')), ':', date::text),
            '|',
            CONCAT('V', OCTET_LENGTH(CONVERT_TO(amount::text, 'UTF8')), ':', amount::text),
            '|',
            CONCAT('V', OCTET_LENGTH(CONVERT_TO(type, 'UTF8')), ':', type),
            '|',
            CONCAT(
                'V',
                OCTET_LENGTH(CONVERT_TO(currency_iso_code, 'UTF8')),
                ':',
                currency_iso_code
            ),
            '|',
            CONCAT(
                'V',
                OCTET_LENGTH(CONVERT_TO(description, 'UTF8')),
                ':',
                description
            )
        ) AS duplicate_key
      FROM transaction
      WHERE deleted = false
        AND owner_id = :ownerId
      )
      SELECT duplicate_key
      FROM duplicate_keys
      WHERE duplicate_key IN (:keys)
      """,
      nativeQuery = true)
  Set<String> findExistingDuplicateKeys(
      @Param("keys") Set<String> keys, @Param("ownerId") String ownerId);
}
