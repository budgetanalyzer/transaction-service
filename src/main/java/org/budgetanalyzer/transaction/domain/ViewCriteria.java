package org.budgetanalyzer.transaction.domain;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Set;

/**
 * Criteria for filtering transactions in a saved view.
 *
 * <p>All fields are optional. When a field is null, that filter is not applied.
 */
public record ViewCriteria(
    LocalDate dateFrom,
    LocalDate dateTo,
    Set<String> accountIds,
    Set<String> bankNames,
    Set<String> currencyIsoCodes,
    BigDecimal minAmount,
    BigDecimal maxAmount,
    TransactionType type,
    String searchText) {

  /** Creates an empty criteria that matches all transactions. */
  public static ViewCriteria empty() {
    return new ViewCriteria(null, null, null, null, null, null, null, null, null);
  }
}
