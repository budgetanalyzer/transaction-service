package org.budgetanalyzer.transaction.service.dto;

import java.math.BigDecimal;
import java.time.LocalDate;

import org.budgetanalyzer.transaction.domain.TransactionType;

/** Service-layer preview of an extracted transaction (no HTTP annotations). */
public record PreviewTransaction(
    LocalDate date,
    String description,
    BigDecimal amount,
    TransactionType type,
    String category,
    String bankName,
    String currencyIsoCode,
    String accountId,
    boolean allowDuplicate,
    boolean duplicate,
    PreviewDuplicateReason duplicateReason) {

  /** Creates a preview transaction that does not allow duplicate import by default. */
  public PreviewTransaction(
      LocalDate date,
      String description,
      BigDecimal amount,
      TransactionType type,
      String category,
      String bankName,
      String currencyIsoCode,
      String accountId) {
    this(date, description, amount, type, category, bankName, currencyIsoCode, accountId, false);
  }

  /** Creates a preview transaction with import duplicate override metadata only. */
  public PreviewTransaction(
      LocalDate date,
      String description,
      BigDecimal amount,
      TransactionType type,
      String category,
      String bankName,
      String currencyIsoCode,
      String accountId,
      boolean allowDuplicate) {
    this(
        date,
        description,
        amount,
        type,
        category,
        bankName,
        currencyIsoCode,
        accountId,
        allowDuplicate,
        false,
        null);
  }

  /**
   * Returns this transaction marked as a duplicate for preview purposes.
   *
   * @param duplicateReason the duplicate reason to expose in preview metadata
   * @return a copy with duplicate metadata set
   */
  public PreviewTransaction withDuplicate(PreviewDuplicateReason duplicateReason) {
    return new PreviewTransaction(
        date,
        description,
        amount,
        type,
        category,
        bankName,
        currencyIsoCode,
        accountId,
        allowDuplicate,
        true,
        duplicateReason);
  }
}
