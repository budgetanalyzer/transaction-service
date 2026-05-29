package org.budgetanalyzer.transaction.repository;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.Objects;

import org.budgetanalyzer.transaction.domain.TransactionType;

/**
 * Structured repository input for owner-scoped duplicate candidate lookup.
 *
 * @param bankName the bank name
 * @param date the transaction date
 * @param amount the transaction amount, canonicalized to scale 2
 * @param type the transaction type
 * @param currencyIsoCode the ISO currency code
 */
public record TransactionDuplicateCandidateCriteria(
    String bankName,
    LocalDate date,
    BigDecimal amount,
    TransactionType type,
    String currencyIsoCode) {

  private static final int AMOUNT_SCALE = 2;

  /** Creates normalized duplicate candidate criteria. */
  public TransactionDuplicateCandidateCriteria {
    bankName = Objects.requireNonNull(bankName, "bankName");
    date = Objects.requireNonNull(date, "date");
    amount = canonicalizeAmount(amount);
    type = Objects.requireNonNull(type, "type");
    currencyIsoCode = Objects.requireNonNull(currencyIsoCode, "currencyIsoCode");
  }

  private static BigDecimal canonicalizeAmount(BigDecimal amount) {
    return Objects.requireNonNull(amount, "amount").setScale(AMOUNT_SCALE, RoundingMode.HALF_UP);
  }
}
