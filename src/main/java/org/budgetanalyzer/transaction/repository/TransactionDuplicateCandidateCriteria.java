package org.budgetanalyzer.transaction.repository;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.Objects;

import org.budgetanalyzer.transaction.domain.TransactionType;

/**
 * Structured repository input for owner-scoped duplicate candidate lookup.
 *
 * @param accountId the account ID, with null and empty string treated equivalently
 * @param bankName the bank name
 * @param date the transaction date
 * @param amount the transaction amount, canonicalized to scale 2
 * @param type the transaction type
 * @param currencyIsoCode the ISO currency code
 */
public record TransactionDuplicateCandidateCriteria(
    String accountId,
    String bankName,
    LocalDate date,
    BigDecimal amount,
    TransactionType type,
    String currencyIsoCode) {

  private static final int AMOUNT_SCALE = 2;
  private static final String COMPONENT_SEPARATOR = "|";
  private static final String NULL_COMPONENT = "N";
  private static final String VALUE_PREFIX = "V";
  private static final String VALUE_LENGTH_SEPARATOR = ":";

  /** Creates normalized duplicate candidate criteria. */
  public TransactionDuplicateCandidateCriteria {
    accountId = normalizeAccountId(accountId);
    bankName = Objects.requireNonNull(bankName, "bankName");
    date = Objects.requireNonNull(date, "date");
    amount = canonicalizeAmount(amount);
    type = Objects.requireNonNull(type, "type");
    currencyIsoCode = Objects.requireNonNull(currencyIsoCode, "currencyIsoCode");
  }

  String toLegacyLookupValue() {
    return encodeComponents(
        accountId, bankName, date.toString(), amount.toPlainString(), type.name(), currencyIsoCode);
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

  private static String encodeComponents(String... values) {
    var encodedComponents = new String[values.length];
    for (int i = 0; i < values.length; i++) {
      encodedComponents[i] = encodeComponent(values[i]);
    }
    return String.join(COMPONENT_SEPARATOR, encodedComponents);
  }

  private static String encodeComponent(String value) {
    if (value == null) {
      return NULL_COMPONENT;
    }

    var valueLength = value.getBytes(StandardCharsets.UTF_8).length;
    return VALUE_PREFIX + valueLength + VALUE_LENGTH_SEPARATOR + value;
  }
}
