package org.budgetanalyzer.transaction.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import java.math.BigDecimal;
import java.time.LocalDate;

import org.junit.jupiter.api.Test;

import org.budgetanalyzer.transaction.domain.TransactionDuplicateIdentity;
import org.budgetanalyzer.transaction.domain.TransactionType;
import org.budgetanalyzer.transaction.service.dto.PreviewTransaction;

class TransactionDuplicateIdentityTest {

  @Test
  void fromPreviewTransactionIncludesFinancialIdentityFieldsExceptAccountId() {
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

    var transactionDuplicateIdentity =
        TransactionDuplicateMatcher.duplicateIdentity(previewTransaction);

    assertThat(transactionDuplicateIdentity.bankName()).isEqualTo("Test Bank");
    assertThat(transactionDuplicateIdentity.date()).isEqualTo(LocalDate.of(2024, 1, 15));
    assertThat(transactionDuplicateIdentity.amount()).isEqualByComparingTo("12.30");
    assertThat(transactionDuplicateIdentity.type()).isEqualTo(TransactionType.DEBIT);
    assertThat(transactionDuplicateIdentity.currencyIsoCode()).isEqualTo("USD");
  }

  @Test
  void fromPreviewTransactionUsesSameIdentityForDifferentDescriptions() {
    var firstPreviewTransaction = previewTransaction("Coffee", "checking");
    var secondPreviewTransaction = previewTransaction("Coffee Shop", "checking");

    var firstTransactionDuplicateIdentity =
        TransactionDuplicateMatcher.duplicateIdentity(firstPreviewTransaction);
    var secondTransactionDuplicateIdentity =
        TransactionDuplicateMatcher.duplicateIdentity(secondPreviewTransaction);

    assertThat(firstTransactionDuplicateIdentity).isEqualTo(secondTransactionDuplicateIdentity);
  }

  @Test
  void fromPreviewTransactionUsesSameIdentityForDifferentAccountIds() {
    var firstPreviewTransaction = previewTransaction("Coffee", "checking");
    var secondPreviewTransaction = previewTransaction("Coffee", "savings");

    var firstTransactionDuplicateIdentity =
        TransactionDuplicateMatcher.duplicateIdentity(firstPreviewTransaction);
    var secondTransactionDuplicateIdentity =
        TransactionDuplicateMatcher.duplicateIdentity(secondPreviewTransaction);

    assertThat(firstTransactionDuplicateIdentity).isEqualTo(secondTransactionDuplicateIdentity);
  }

  @Test
  void constructorDistinguishesDifferentBankNames() {
    var firstTransactionDuplicateIdentity =
        new TransactionDuplicateIdentity(
            "Test Bank",
            LocalDate.of(2024, 1, 15),
            new BigDecimal("12.30"),
            TransactionType.DEBIT,
            "USD");
    var secondTransactionDuplicateIdentity =
        new TransactionDuplicateIdentity(
            "Other Bank",
            LocalDate.of(2024, 1, 15),
            new BigDecimal("12.30"),
            TransactionType.DEBIT,
            "USD");

    assertThat(firstTransactionDuplicateIdentity).isNotEqualTo(secondTransactionDuplicateIdentity);
  }

  @Test
  void constructorDistinguishesDifferentDates() {
    var firstTransactionDuplicateIdentity = duplicateIdentity();
    var secondTransactionDuplicateIdentity =
        new TransactionDuplicateIdentity(
            "Test Bank",
            LocalDate.of(2024, 1, 16),
            new BigDecimal("12.30"),
            TransactionType.DEBIT,
            "USD");

    assertThat(firstTransactionDuplicateIdentity).isNotEqualTo(secondTransactionDuplicateIdentity);
  }

  @Test
  void constructorDistinguishesDifferentAmounts() {
    var firstTransactionDuplicateIdentity = duplicateIdentity(new BigDecimal("12.30"));
    var secondTransactionDuplicateIdentity = duplicateIdentity(new BigDecimal("12.31"));

    assertThat(firstTransactionDuplicateIdentity).isNotEqualTo(secondTransactionDuplicateIdentity);
  }

  @Test
  void constructorDistinguishesDifferentTypes() {
    var firstTransactionDuplicateIdentity = duplicateIdentity();
    var secondTransactionDuplicateIdentity =
        new TransactionDuplicateIdentity(
            "Test Bank",
            LocalDate.of(2024, 1, 15),
            new BigDecimal("12.30"),
            TransactionType.CREDIT,
            "USD");

    assertThat(firstTransactionDuplicateIdentity).isNotEqualTo(secondTransactionDuplicateIdentity);
  }

  @Test
  void constructorDistinguishesDifferentCurrencyIsoCodes() {
    var firstTransactionDuplicateIdentity = duplicateIdentity();
    var secondTransactionDuplicateIdentity =
        new TransactionDuplicateIdentity(
            "Test Bank",
            LocalDate.of(2024, 1, 15),
            new BigDecimal("12.30"),
            TransactionType.DEBIT,
            "THB");

    assertThat(firstTransactionDuplicateIdentity).isNotEqualTo(secondTransactionDuplicateIdentity);
  }

  @Test
  void constructorCanonicalizesAmountToScaleTwo() {
    var wholeAmountIdentity = duplicateIdentity(new BigDecimal("12"));
    var scaledAmountIdentity = duplicateIdentity(new BigDecimal("12.00"));
    var roundedAmountIdentity = duplicateIdentity(new BigDecimal("12.005"));

    assertThat(wholeAmountIdentity).isEqualTo(scaledAmountIdentity);
    assertThat(wholeAmountIdentity.amount()).isEqualByComparingTo("12.00");
    assertThat(roundedAmountIdentity.amount()).isEqualByComparingTo("12.01");
  }

  @Test
  void constructorRequiresAmountForCanonicalization() {
    assertThatNullPointerException()
        .isThrownBy(
            () ->
                new TransactionDuplicateIdentity(
                    "Test Bank", LocalDate.of(2024, 1, 15), null, TransactionType.DEBIT, "USD"));
  }

  private static TransactionDuplicateIdentity duplicateIdentity() {
    return duplicateIdentity(new BigDecimal("12.30"));
  }

  private static TransactionDuplicateIdentity duplicateIdentity(BigDecimal amount) {
    return new TransactionDuplicateIdentity(
        "Test Bank", LocalDate.of(2024, 1, 15), amount, TransactionType.DEBIT, "USD");
  }

  private static PreviewTransaction previewTransaction(String description, String accountId) {
    return new PreviewTransaction(
        LocalDate.of(2024, 1, 15),
        description,
        new BigDecimal("12.30"),
        TransactionType.DEBIT,
        null,
        "Test Bank",
        "USD",
        accountId);
  }
}
