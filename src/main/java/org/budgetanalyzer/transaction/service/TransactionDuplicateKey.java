package org.budgetanalyzer.transaction.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
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

  private static final int AMOUNT_SCALE = 2;
  private static final String COMPONENT_SEPARATOR = "|";
  private static final String NULL_COMPONENT = "N";
  private static final String VALUE_PREFIX = "V";
  private static final String VALUE_LENGTH_SEPARATOR = ":";

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
    accountId = normalizeAccountId(accountId);
    bankName = Objects.requireNonNull(bankName, "bankName");
    date = Objects.requireNonNull(date, "date");
    amount = canonicalizeAmount(amount);
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
   * Returns the canonical lookup value used for string-based repository duplicate matching.
   *
   * @return the canonical lookup value
   */
  public String toLookupValue() {
    return String.join(
        COMPONENT_SEPARATOR,
        encodeComponent(accountId),
        encodeComponent(bankName),
        encodeComponent(date.toString()),
        encodeComponent(amount.toPlainString()),
        encodeComponent(type.name()),
        encodeComponent(currencyIsoCode),
        encodeComponent(description));
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

  private static String encodeComponent(String value) {
    if (value == null) {
      return NULL_COMPONENT;
    }

    var valueLength = value.getBytes(StandardCharsets.UTF_8).length;
    return VALUE_PREFIX + valueLength + VALUE_LENGTH_SEPARATOR + value;
  }
}
