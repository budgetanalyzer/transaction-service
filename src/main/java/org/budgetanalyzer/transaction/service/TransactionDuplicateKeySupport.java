package org.budgetanalyzer.transaction.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

/** Shared normalization and encoding rules for transaction duplicate keys. */
final class TransactionDuplicateKeySupport {

  private static final int AMOUNT_SCALE = 2;
  private static final String COMPONENT_SEPARATOR = "|";
  private static final String NULL_COMPONENT = "N";
  private static final String VALUE_PREFIX = "V";
  private static final String VALUE_LENGTH_SEPARATOR = ":";

  private TransactionDuplicateKeySupport() {}

  static String normalizeAccountId(String accountId) {
    if (accountId == null || accountId.isEmpty()) {
      return null;
    }
    return accountId;
  }

  static BigDecimal canonicalizeAmount(BigDecimal amount) {
    return Objects.requireNonNull(amount, "amount").setScale(AMOUNT_SCALE, RoundingMode.HALF_UP);
  }

  static String encodeComponents(String... values) {
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
