package org.budgetanalyzer.transaction.service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Objects;

import org.budgetanalyzer.transaction.domain.TransactionType;
import org.budgetanalyzer.transaction.service.dto.PreviewTransaction;

/** Owner-scoped transaction duplicate key fields, excluding the owner query scope. */
public record TransactionDuplicateKey(
    String accountId,
    String bankName,
    LocalDate date,
    BigDecimal amount,
    TransactionType type,
    String currencyIsoCode,
    String description) {

  /**
   * Creates a normalized duplicate key.
   *
   * @param accountId the account ID, with null and empty string treated equivalently
   * @param bankName the bank name
   * @param date the transaction date
   * @param amount the transaction amount, canonicalized to scale 2
   * @param type the transaction type
   * @param currencyIsoCode the ISO currency code
   * @param description the exact transaction description
   */
  public TransactionDuplicateKey {
    accountId = TransactionDuplicateKeySupport.normalizeAccountId(accountId);
    bankName = Objects.requireNonNull(bankName, "bankName");
    date = Objects.requireNonNull(date, "date");
    amount = TransactionDuplicateKeySupport.canonicalizeAmount(amount);
    type = Objects.requireNonNull(type, "type");
    currencyIsoCode = Objects.requireNonNull(currencyIsoCode, "currencyIsoCode");
    description = Objects.requireNonNull(description, "description");
  }

  /**
   * Creates a duplicate key from a preview transaction.
   *
   * @param previewTransaction the preview transaction
   * @return the normalized duplicate key
   */
  public static TransactionDuplicateKey from(PreviewTransaction previewTransaction) {
    Objects.requireNonNull(previewTransaction, "previewTransaction");
    return new TransactionDuplicateKey(
        previewTransaction.accountId(),
        previewTransaction.bankName(),
        previewTransaction.date(),
        previewTransaction.amount(),
        previewTransaction.type(),
        previewTransaction.currencyIsoCode(),
        previewTransaction.description());
  }

  /**
   * Returns the duplicate candidate identity for this full duplicate key.
   *
   * @return the duplicate candidate key
   */
  public TransactionDuplicateCandidateKey candidateKey() {
    return new TransactionDuplicateCandidateKey(
        accountId, bankName, date, amount, type, currencyIsoCode);
  }

  /**
   * Returns the canonical lookup value used for string-based repository duplicate matching.
   *
   * @return the canonical lookup value
   */
  public String toLookupValue() {
    return TransactionDuplicateKeySupport.encodeComponents(
        accountId,
        bankName,
        date.toString(),
        amount.toPlainString(),
        type.name(),
        currencyIsoCode,
        description);
  }
}
