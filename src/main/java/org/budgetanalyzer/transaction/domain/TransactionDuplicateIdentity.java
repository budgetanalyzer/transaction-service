package org.budgetanalyzer.transaction.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;

/**
 * Normalized financial identity used to find duplicate transaction candidates before description
 * matching.
 *
 * @param bankName the bank name
 * @param date the transaction date
 * @param amount the transaction amount, canonicalized to scale 2
 * @param type the transaction type
 * @param currencyIsoCode the ISO currency code
 */
public record TransactionDuplicateIdentity(
    String bankName,
    LocalDate date,
    BigDecimal amount,
    TransactionType type,
    String currencyIsoCode) {

  private static final int AMOUNT_SCALE = 2;

  /** Creates a normalized duplicate identity. */
  public TransactionDuplicateIdentity {
    amount = amount.setScale(AMOUNT_SCALE, RoundingMode.HALF_UP);
  }
}
