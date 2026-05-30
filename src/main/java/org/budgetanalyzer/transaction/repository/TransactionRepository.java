package org.budgetanalyzer.transaction.repository;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import org.budgetanalyzer.core.repository.SoftDeleteOperations;
import org.budgetanalyzer.transaction.domain.Transaction;
import org.budgetanalyzer.transaction.domain.TransactionType;

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

  /** SQL projection returned by structured repository candidate lookup. */
  interface StructuredTransactionDuplicateCandidate {
    /**
     * Returns the bank name.
     *
     * @return the bank name
     */
    String getBankName();

    /**
     * Returns the transaction date.
     *
     * @return the transaction date
     */
    LocalDate getDate();

    /**
     * Returns the transaction amount.
     *
     * @return the transaction amount
     */
    BigDecimal getAmount();

    /**
     * Returns the transaction type.
     *
     * @return the transaction type
     */
    String getType();

    /**
     * Returns the transaction currency ISO code.
     *
     * @return the transaction currency ISO code
     */
    String getCurrencyIsoCode();

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

    var candidateCriteriaList = List.copyOf(candidateCriteria);
    return findDuplicateCandidatesByStructuredCriteria(
            candidateCriteriaList.stream()
                .map(TransactionDuplicateCandidateCriteria::bankName)
                .toArray(String[]::new),
            candidateCriteriaList.stream()
                .map(TransactionDuplicateCandidateCriteria::date)
                .toArray(LocalDate[]::new),
            candidateCriteriaList.stream()
                .map(TransactionDuplicateCandidateCriteria::amount)
                .toArray(BigDecimal[]::new),
            candidateCriteriaList.stream()
                .map(criteria -> criteria.type().name())
                .toArray(String[]::new),
            candidateCriteriaList.stream()
                .map(TransactionDuplicateCandidateCriteria::currencyIsoCode)
                .toArray(String[]::new),
            ownerId)
        .stream()
        .map(TransactionRepository::toCandidate)
        .toList();
  }

  @Query(
      value =
          """
      WITH candidate_criteria AS (
        SELECT
            bank_name,
            transaction_date,
            amount,
            transaction_type,
            currency_iso_code
        FROM UNNEST(
            CAST(:bankNames AS text[]),
            CAST(:dates AS date[]),
            CAST(:amounts AS numeric[]),
            CAST(:types AS text[]),
            CAST(:currencyIsoCodes AS text[])
        ) AS candidate_criteria(
            bank_name,
            transaction_date,
            amount,
            transaction_type,
            currency_iso_code
        )
      )
      SELECT
          candidate_criteria.bank_name AS "bankName",
          candidate_criteria.transaction_date AS "date",
          candidate_criteria.amount AS "amount",
          candidate_criteria.transaction_type AS "type",
          candidate_criteria.currency_iso_code AS "currencyIsoCode",
          transaction.id AS "transactionId",
          transaction.description AS "description"
      FROM candidate_criteria
      JOIN transaction
        ON transaction.owner_id = :ownerId
       AND transaction.deleted = false
       AND transaction.bank_name = candidate_criteria.bank_name
       AND transaction.date = candidate_criteria.transaction_date
       AND transaction.amount = candidate_criteria.amount
       AND transaction.type = candidate_criteria.transaction_type
       AND transaction.currency_iso_code = candidate_criteria.currency_iso_code
      """,
      nativeQuery = true)
  List<StructuredTransactionDuplicateCandidate> findDuplicateCandidatesByStructuredCriteria(
      @Param("bankNames") String[] bankNames,
      @Param("dates") LocalDate[] dates,
      @Param("amounts") BigDecimal[] amounts,
      @Param("types") String[] types,
      @Param("currencyIsoCodes") String[] currencyIsoCodes,
      @Param("ownerId") String ownerId);

  private static TransactionDuplicateCandidate toCandidate(
      StructuredTransactionDuplicateCandidate structuredCandidate) {
    return new TransactionDuplicateCandidateResult(
        new TransactionDuplicateCandidateCriteria(
            structuredCandidate.getBankName(),
            structuredCandidate.getDate(),
            structuredCandidate.getAmount(),
            TransactionType.valueOf(structuredCandidate.getType()),
            structuredCandidate.getCurrencyIsoCode()),
        structuredCandidate.getTransactionId(),
        structuredCandidate.getDescription());
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
