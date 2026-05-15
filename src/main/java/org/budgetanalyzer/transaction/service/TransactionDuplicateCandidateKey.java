package org.budgetanalyzer.transaction.service;

import java.math.BigDecimal;
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

  /** Creates a normalized duplicate candidate key. */
  public TransactionDuplicateCandidateKey {
    accountId = TransactionDuplicateKeySupport.normalizeAccountId(accountId);
    bankName = Objects.requireNonNull(bankName, "bankName");
    date = Objects.requireNonNull(date, "date");
    amount = TransactionDuplicateKeySupport.canonicalizeAmount(amount);
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

  /**
   * Returns the canonical lookup value used for string-based candidate matching.
   *
   * @return the canonical lookup value
   */
  public String toLookupValue() {
    return TransactionDuplicateKeySupport.encodeComponents(
        accountId, bankName, date.toString(), amount.toPlainString(), type.name(), currencyIsoCode);
  }
}
