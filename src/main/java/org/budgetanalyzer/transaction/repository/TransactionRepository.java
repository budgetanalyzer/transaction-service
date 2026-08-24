package org.budgetanalyzer.transaction.repository;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Set;

import jakarta.persistence.LockModeType;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import org.budgetanalyzer.core.repository.SoftDeleteOperations;
import org.budgetanalyzer.transaction.domain.Transaction;
import org.budgetanalyzer.transaction.domain.TransactionDuplicateIdentity;
import org.budgetanalyzer.transaction.domain.TransactionType;

public interface TransactionRepository
    extends JpaRepository<Transaction, Long>, SoftDeleteOperations<Transaction, Long> {

  /** Locks active owner-scoped transactions in deterministic ID order. */
  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query(
      "SELECT t FROM Transaction t "
          + "WHERE t.ownerId = :ownerId AND t.id IN :ids AND t.deleted = false ORDER BY t.id")
  List<Transaction> lockActiveByOwnerIdAndIdIn(
      @Param("ownerId") String ownerId, @Param("ids") Collection<Long> ids);

  /** Locks active transactions in deterministic ID order. */
  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("SELECT t FROM Transaction t WHERE t.id IN :ids AND t.deleted = false ORDER BY t.id")
  List<Transaction> lockActiveByIdIn(@Param("ids") Collection<Long> ids);

  /** Active transaction candidate returned by owner-scoped duplicate candidate lookup. */
  interface TransactionDuplicateCandidate {

    /**
     * Returns the structured description-free duplicate identity.
     *
     * @return the candidate identity
     */
    TransactionDuplicateIdentity getDuplicateIdentity();

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
     * Returns the persisted transaction description.
     *
     * @return the transaction description
     */
    String getDescription();
  }

  /**
   * Finds active duplicate candidates for a specific owner using description-free identities.
   *
   * <p>Used by duplicate detection to retrieve candidate descriptions after strict financial
   * identity fields have matched.
   *
   * @param duplicateIdentities set of structured description-free duplicate identities
   * @param ownerId the ID of the transaction owner
   * @return active matching duplicate candidates for this owner
   */
  default List<TransactionDuplicateCandidate> findDuplicateCandidates(
      Set<TransactionDuplicateIdentity> duplicateIdentities, String ownerId) {
    if (duplicateIdentities.isEmpty()) {
      return List.of();
    }

    var duplicateIdentityList = List.copyOf(duplicateIdentities);
    return findDuplicateCandidatesByStructuredCriteria(
            duplicateIdentityList.stream()
                .map(TransactionDuplicateIdentity::bankName)
                .toArray(String[]::new),
            duplicateIdentityList.stream()
                .map(TransactionDuplicateIdentity::date)
                .toArray(LocalDate[]::new),
            duplicateIdentityList.stream()
                .map(TransactionDuplicateIdentity::amount)
                .toArray(BigDecimal[]::new),
            duplicateIdentityList.stream()
                .map(identity -> identity.type().name())
                .toArray(String[]::new),
            duplicateIdentityList.stream()
                .map(TransactionDuplicateIdentity::currencyIsoCode)
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
        new TransactionDuplicateIdentity(
            structuredCandidate.getBankName(),
            structuredCandidate.getDate(),
            structuredCandidate.getAmount(),
            TransactionType.valueOf(structuredCandidate.getType()),
            structuredCandidate.getCurrencyIsoCode()),
        structuredCandidate.getDescription());
  }

  /** Default-method result that exposes structured duplicate identity to service callers. */
  final class TransactionDuplicateCandidateResult implements TransactionDuplicateCandidate {

    private final TransactionDuplicateIdentity duplicateIdentity;
    private final String description;

    TransactionDuplicateCandidateResult(
        TransactionDuplicateIdentity duplicateIdentity, String description) {
      this.duplicateIdentity = duplicateIdentity;
      this.description = description;
    }

    @Override
    public TransactionDuplicateIdentity getDuplicateIdentity() {
      return duplicateIdentity;
    }

    @Override
    public String getDescription() {
      return description;
    }
  }
}
