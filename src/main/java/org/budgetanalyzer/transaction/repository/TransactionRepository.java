package org.budgetanalyzer.transaction.repository;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

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
     * Returns the structured description-free duplicate candidate criteria.
     *
     * @return the candidate criteria
     */
    TransactionDuplicateCandidateCriteria getCandidateCriteria();

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

  /** Legacy SQL projection returned before structured repository SQL is introduced. */
  interface EncodedTransactionDuplicateCandidate {

    /**
     * Returns the encoded description-free duplicate candidate lookup value.
     *
     * @return the encoded lookup value
     */
    String getCandidateLookupValue();

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
   * Finds active duplicate candidates for a specific owner using description-free criteria.
   *
   * <p>Used by fuzzy duplicate detection to retrieve candidate descriptions after strict financial
   * identity fields have matched.
   *
   * @param candidateCriteria set of structured description-free duplicate candidate criteria
   * @param ownerId the ID of the transaction owner
   * @return active matching duplicate candidates for this owner
   */
  default List<TransactionDuplicateCandidate> findDuplicateCandidates(
      Set<TransactionDuplicateCandidateCriteria> candidateCriteria, String ownerId) {
    if (candidateCriteria.isEmpty()) {
      return List.of();
    }

    var criteriaByLookupValue =
        candidateCriteria.stream()
            .collect(
                Collectors.toMap(
                    TransactionDuplicateCandidateCriteria::toLegacyLookupValue,
                    criteria -> criteria,
                    (existingCriteria, duplicateCriteria) -> existingCriteria));
    return findDuplicateCandidatesByLookupValues(criteriaByLookupValue.keySet(), ownerId).stream()
        .map(encodedCandidate -> toCandidate(encodedCandidate, criteriaByLookupValue))
        .toList();
  }

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
          duplicate_candidate_key AS "candidateLookupValue",
          id AS "transactionId",
          description AS "description"
      FROM duplicate_candidates
      WHERE duplicate_candidate_key IN (:candidateKeys)
      """,
      nativeQuery = true)
  List<EncodedTransactionDuplicateCandidate> findDuplicateCandidatesByLookupValues(
      @Param("candidateKeys") Set<String> candidateKeys, @Param("ownerId") String ownerId);

  private static TransactionDuplicateCandidate toCandidate(
      EncodedTransactionDuplicateCandidate encodedCandidate,
      Map<String, TransactionDuplicateCandidateCriteria> criteriaByLookupValue) {
    return new TransactionDuplicateCandidateResult(
        criteriaByLookupValue.get(encodedCandidate.getCandidateLookupValue()),
        encodedCandidate.getTransactionId(),
        encodedCandidate.getDescription());
  }

  /** Default-method result that exposes structured candidate criteria to service callers. */
  final class TransactionDuplicateCandidateResult implements TransactionDuplicateCandidate {

    private final TransactionDuplicateCandidateCriteria candidateCriteria;
    private final Long transactionId;
    private final String description;

    TransactionDuplicateCandidateResult(
        TransactionDuplicateCandidateCriteria candidateCriteria,
        Long transactionId,
        String description) {
      this.candidateCriteria = candidateCriteria;
      this.transactionId = transactionId;
      this.description = description;
    }

    @Override
    public TransactionDuplicateCandidateCriteria getCandidateCriteria() {
      return candidateCriteria;
    }

    @Override
    public Long getTransactionId() {
      return transactionId;
    }

    @Override
    public String getDescription() {
      return description;
    }
  }
}
