package org.budgetanalyzer.transaction.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import java.math.BigDecimal;
import java.time.LocalDate;

import org.junit.jupiter.api.Test;

import org.budgetanalyzer.transaction.domain.TransactionType;
import org.budgetanalyzer.transaction.service.dto.PreviewTransaction;

class TransactionDuplicateKeyTest {

  @Test
  void from_includesAllDuplicateFields() {
    var previewTransaction =
        new PreviewTransaction(
            LocalDate.of(2024, 1, 15),
            "Coffee",
            new BigDecimal("12.30"),
            TransactionType.DEBIT,
            null,
            "Test Bank",
            "USD",
            "checking");

    var transactionDuplicateKey = TransactionDuplicateKey.from(previewTransaction);

    assertThat(transactionDuplicateKey.accountId()).isEqualTo("checking");
    assertThat(transactionDuplicateKey.bankName()).isEqualTo("Test Bank");
    assertThat(transactionDuplicateKey.date()).isEqualTo(LocalDate.of(2024, 1, 15));
    assertThat(transactionDuplicateKey.amount()).isEqualByComparingTo("12.30");
    assertThat(transactionDuplicateKey.type()).isEqualTo(TransactionType.DEBIT);
    assertThat(transactionDuplicateKey.currencyIsoCode()).isEqualTo("USD");
    assertThat(transactionDuplicateKey.description()).isEqualTo("Coffee");
  }

  @Test
  void constructor_nullAccountIdsAreEqual() {
    var nullAccountKey = duplicateKey(null);
    var anotherNullAccountKey = duplicateKey(null);

    assertThat(nullAccountKey).isEqualTo(anotherNullAccountKey);
    assertThat(nullAccountKey.toLookupValue()).isEqualTo(anotherNullAccountKey.toLookupValue());
  }

  @Test
  void constructor_nullAccountIdDoesNotEqualNonNullAccountId() {
    var nullAccountKey = duplicateKey(null);
    var nonNullAccountKey = duplicateKey("checking");

    assertThat(nullAccountKey).isNotEqualTo(nonNullAccountKey);
    assertThat(nullAccountKey.toLookupValue()).isNotEqualTo(nonNullAccountKey.toLookupValue());
  }

  @Test
  void constructor_emptyAccountIdEqualsNullAccountId() {
    var emptyAccountKey = duplicateKey("");
    var nullAccountKey = duplicateKey(null);

    assertThat(emptyAccountKey).isEqualTo(nullAccountKey);
    assertThat(emptyAccountKey.accountId()).isNull();
    assertThat(emptyAccountKey.toLookupValue()).isEqualTo(nullAccountKey.toLookupValue());
  }

  @Test
  void constructor_canonicalizesAmountToScaleTwo() {
    var wholeAmountKey = duplicateKey("checking", new BigDecimal("12"));
    var scaledAmountKey = duplicateKey("checking", new BigDecimal("12.00"));
    var roundedAmountKey = duplicateKey("checking", new BigDecimal("12.005"));

    assertThat(wholeAmountKey).isEqualTo(scaledAmountKey);
    assertThat(wholeAmountKey.amount()).isEqualByComparingTo("12.00");
    assertThat(roundedAmountKey.amount()).isEqualByComparingTo("12.01");
  }

  @Test
  void constructor_descriptionMatchingIsExact() {
    var exactDescriptionKey = duplicateKeyWithDescription("Coffee");
    var caseChangedDescriptionKey = duplicateKeyWithDescription("coffee");
    var spacedDescriptionKey = duplicateKeyWithDescription(" Coffee ");

    assertThat(exactDescriptionKey).isNotEqualTo(caseChangedDescriptionKey);
    assertThat(exactDescriptionKey).isNotEqualTo(spacedDescriptionKey);
  }

  @Test
  void toLookupValue_distinguishesNullFromLiteralNullMarker() {
    var nullAccountKey = duplicateKey(null);
    var markerAccountKey = duplicateKey("N");

    assertThat(nullAccountKey.toLookupValue()).isNotEqualTo(markerAccountKey.toLookupValue());
  }

  @Test
  void toLookupValue_distinguishesEmbeddedSeparators() {
    var firstTransactionDuplicateKey =
        new TransactionDuplicateKey(
            "a|b",
            "Bank",
            LocalDate.of(2024, 1, 15),
            new BigDecimal("12.00"),
            TransactionType.DEBIT,
            "USD",
            "Coffee");
    var secondTransactionDuplicateKey =
        new TransactionDuplicateKey(
            "a",
            "b|Bank",
            LocalDate.of(2024, 1, 15),
            new BigDecimal("12.00"),
            TransactionType.DEBIT,
            "USD",
            "Coffee");

    assertThat(firstTransactionDuplicateKey.toLookupValue())
        .isNotEqualTo(secondTransactionDuplicateKey.toLookupValue());
  }

  @Test
  void constructor_requiresNonAccountFields() {
    assertThatNullPointerException()
        .isThrownBy(
            () ->
                new TransactionDuplicateKey(
                    null,
                    null,
                    LocalDate.of(2024, 1, 15),
                    new BigDecimal("12.00"),
                    TransactionType.DEBIT,
                    "USD",
                    "Coffee"))
        .withMessage("bankName");
  }

  private static TransactionDuplicateKey duplicateKey(String accountId) {
    return duplicateKey(accountId, new BigDecimal("12.00"));
  }

  private static TransactionDuplicateKey duplicateKey(String accountId, BigDecimal amount) {
    return new TransactionDuplicateKey(
        accountId,
        "Test Bank",
        LocalDate.of(2024, 1, 15),
        amount,
        TransactionType.DEBIT,
        "USD",
        "Coffee");
  }

  private static TransactionDuplicateKey duplicateKeyWithDescription(String description) {
    return new TransactionDuplicateKey(
        "checking",
        "Test Bank",
        LocalDate.of(2024, 1, 15),
        new BigDecimal("12.00"),
        TransactionType.DEBIT,
        "USD",
        description);
  }
}
