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
    boolean allowDuplicate) {

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
}
