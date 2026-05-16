package org.budgetanalyzer.transaction.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.Objects;

import org.budgetanalyzer.transaction.domain.Transaction;
import org.budgetanalyzer.transaction.domain.TransactionType;
import org.budgetanalyzer.transaction.service.dto.PreviewTransaction;

/**
 * Owner-scoped transaction duplicate candidate identity, excluding description comparison.
 *
 * <p>This key represents the strict financial identity fields used to find candidate duplicates
 * before description matching is applied.
 *
 * @param accountId the account ID, with null and empty string treated equivalently
 * @param bankName the bank name
 * @param date the transaction date
 * @param amount the transaction amount, canonicalized to scale 2
 * @param type the transaction type
 * @param currencyIsoCode the ISO currency code
 */
public record TransactionDuplicateCandidateKey(
    String accountId,
    String bankName,
    LocalDate date,
    BigDecimal amount,
    TransactionType type,
    String currencyIsoCode) {

  private static final int AMOUNT_SCALE = 2;

  /** Creates a normalized duplicate candidate key. */
  public TransactionDuplicateCandidateKey {
    accountId = normalizeAccountId(accountId);
    bankName = Objects.requireNonNull(bankName, "bankName");
    date = Objects.requireNonNull(date, "date");
    amount = canonicalizeAmount(amount);
    type = Objects.requireNonNull(type, "type");
    currencyIsoCode = Objects.requireNonNull(currencyIsoCode, "currencyIsoCode");
  }

  /**
   * Creates a duplicate candidate key from a preview transaction.
   *
   * @param previewTransaction the preview transaction
   * @return the normalized duplicate candidate key
   */
  public static TransactionDuplicateCandidateKey from(PreviewTransaction previewTransaction) {
    Objects.requireNonNull(previewTransaction, "previewTransaction");
    return new TransactionDuplicateCandidateKey(
        previewTransaction.accountId(),
        previewTransaction.bankName(),
        previewTransaction.date(),
        previewTransaction.amount(),
        previewTransaction.type(),
        previewTransaction.currencyIsoCode());
  }

  /**
   * Creates a duplicate candidate key from a persisted transaction.
   *
   * @param transaction the persisted transaction
   * @return the normalized duplicate candidate key
   */
  public static TransactionDuplicateCandidateKey from(Transaction transaction) {
    Objects.requireNonNull(transaction, "transaction");
    return new TransactionDuplicateCandidateKey(
        transaction.getAccountId(),
        transaction.getBankName(),
        transaction.getDate(),
        transaction.getAmount(),
        transaction.getType(),
        transaction.getCurrencyIsoCode());
  }

  private static String normalizeAccountId(String accountId) {
    if (accountId == null || accountId.isEmpty()) {
      return null;
    }
    return accountId;
  }

  private static BigDecimal canonicalizeAmount(BigDecimal amount) {
    return Objects.requireNonNull(amount, "amount").setScale(AMOUNT_SCALE, RoundingMode.HALF_UP);
  }
}
