package org.budgetanalyzer.transaction.repository;

import java.util.List;
import java.util.Set;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import org.budgetanalyzer.core.repository.SoftDeleteOperations;
import org.budgetanalyzer.transaction.domain.Transaction;

public interface TransactionRepository
    extends JpaRepository<Transaction, Long>, SoftDeleteOperations<Transaction, Long> {

  /** Active transaction candidate returned by owner-scoped duplicate candidate lookup. */
  interface TransactionDuplicateCandidate {

    /**
     * Returns the encoded description-free duplicate candidate key.
     *
     * @return the encoded candidate key
     */
    String getCandidateKey();

    /**
     * Returns the persisted transaction ID.
     *
     * @return the transaction ID
     */
    Long getTransactionId();

    /**
     * Returns the persisted transaction description.
     *
     * @return the transaction description
     */
    String getDescription();
  }

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

  /**
   * Finds active duplicate candidates for a specific owner using description-free keys.
   *
   * <p>Used by fuzzy duplicate detection to retrieve candidate descriptions after strict financial
   * identity fields have matched.
   *
   * @param candidateKeys set of encoded description-free duplicate candidate keys
   * @param ownerId the ID of the transaction owner
   * @return active matching duplicate candidates for this owner
   */
  @Query(
      value =
          """
      WITH duplicate_candidates AS (
        SELECT
            id,
            description,
            CONCAT(
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
                )
            ) AS duplicate_candidate_key
        FROM transaction
        WHERE deleted = false
          AND owner_id = :ownerId
      )
      SELECT
          duplicate_candidate_key AS "candidateKey",
          id AS "transactionId",
          description AS "description"
      FROM duplicate_candidates
      WHERE duplicate_candidate_key IN (:candidateKeys)
      """,
      nativeQuery = true)
  List<TransactionDuplicateCandidate> findDuplicateCandidates(
      @Param("candidateKeys") Set<String> candidateKeys, @Param("ownerId") String ownerId);
}
