package org.budgetanalyzer.transaction.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import java.math.BigDecimal;
import java.time.LocalDate;

import org.junit.jupiter.api.Test;

import org.budgetanalyzer.transaction.domain.Transaction;
import org.budgetanalyzer.transaction.domain.TransactionType;
import org.budgetanalyzer.transaction.service.dto.PreviewTransaction;

class TransactionDuplicateCandidateKeyTest {

  @Test
  void fromPreviewTransaction_includesFinancialIdentityFieldsExceptAccountId() {
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

    var transactionDuplicateCandidateKey =
        TransactionDuplicateCandidateKey.from(previewTransaction);

    assertThat(transactionDuplicateCandidateKey.bankName()).isEqualTo("Test Bank");
    assertThat(transactionDuplicateCandidateKey.date()).isEqualTo(LocalDate.of(2024, 1, 15));
    assertThat(transactionDuplicateCandidateKey.amount()).isEqualByComparingTo("12.30");
    assertThat(transactionDuplicateCandidateKey.type()).isEqualTo(TransactionType.DEBIT);
    assertThat(transactionDuplicateCandidateKey.currencyIsoCode()).isEqualTo("USD");
  }

  @Test
  void fromPreviewTransaction_usesSameKeyForDifferentDescriptions() {
    var firstPreviewTransaction = previewTransaction("Coffee", "checking");
    var secondPreviewTransaction = previewTransaction("Coffee Shop", "checking");

    var firstTransactionDuplicateCandidateKey =
        TransactionDuplicateCandidateKey.from(firstPreviewTransaction);
    var secondTransactionDuplicateCandidateKey =
        TransactionDuplicateCandidateKey.from(secondPreviewTransaction);

    assertThat(firstTransactionDuplicateCandidateKey)
        .isEqualTo(secondTransactionDuplicateCandidateKey);
  }

  @Test
  void fromPreviewTransaction_usesSameKeyForDifferentAccountIds() {
    var firstPreviewTransaction = previewTransaction("Coffee", "checking");
    var secondPreviewTransaction = previewTransaction("Coffee", "savings");

    var firstTransactionDuplicateCandidateKey =
        TransactionDuplicateCandidateKey.from(firstPreviewTransaction);
    var secondTransactionDuplicateCandidateKey =
        TransactionDuplicateCandidateKey.from(secondPreviewTransaction);

    assertThat(firstTransactionDuplicateCandidateKey)
        .isEqualTo(secondTransactionDuplicateCandidateKey);
  }

  @Test
  void fromTransaction_includesFinancialIdentityFieldsExceptAccountId() {
    var transaction = transaction("checking", new BigDecimal("12.30"));
    transaction.setDescription("Coffee");

    var transactionDuplicateCandidateKey = TransactionDuplicateCandidateKey.from(transaction);

    assertThat(transactionDuplicateCandidateKey)
        .isEqualTo(
            new TransactionDuplicateCandidateKey(
                "Test Bank",
                LocalDate.of(2024, 1, 15),
                new BigDecimal("12.30"),
                TransactionType.DEBIT,
                "USD"));
  }

  @Test
  void constructor_distinguishesDifferentBankNames() {
    var firstTransactionDuplicateCandidateKey =
        new TransactionDuplicateCandidateKey(
            "Test Bank",
            LocalDate.of(2024, 1, 15),
            new BigDecimal("12.30"),
            TransactionType.DEBIT,
            "USD");
    var secondTransactionDuplicateCandidateKey =
        new TransactionDuplicateCandidateKey(
            "Other Bank",
            LocalDate.of(2024, 1, 15),
            new BigDecimal("12.30"),
            TransactionType.DEBIT,
            "USD");

    assertThat(firstTransactionDuplicateCandidateKey)
        .isNotEqualTo(secondTransactionDuplicateCandidateKey);
  }

  @Test
  void constructor_distinguishesDifferentDates() {
    var firstTransactionDuplicateCandidateKey = duplicateCandidateKey();
    var secondTransactionDuplicateCandidateKey =
        new TransactionDuplicateCandidateKey(
            "Test Bank",
            LocalDate.of(2024, 1, 16),
            new BigDecimal("12.30"),
            TransactionType.DEBIT,
            "USD");

    assertThat(firstTransactionDuplicateCandidateKey)
        .isNotEqualTo(secondTransactionDuplicateCandidateKey);
  }

  @Test
  void constructor_distinguishesDifferentAmounts() {
    var firstTransactionDuplicateCandidateKey = duplicateCandidateKey(new BigDecimal("12.30"));
    var secondTransactionDuplicateCandidateKey = duplicateCandidateKey(new BigDecimal("12.31"));

    assertThat(firstTransactionDuplicateCandidateKey)
        .isNotEqualTo(secondTransactionDuplicateCandidateKey);
  }

  @Test
  void constructor_distinguishesDifferentTypes() {
    var firstTransactionDuplicateCandidateKey = duplicateCandidateKey();
    var secondTransactionDuplicateCandidateKey =
        new TransactionDuplicateCandidateKey(
            "Test Bank",
            LocalDate.of(2024, 1, 15),
            new BigDecimal("12.30"),
            TransactionType.CREDIT,
            "USD");

    assertThat(firstTransactionDuplicateCandidateKey)
        .isNotEqualTo(secondTransactionDuplicateCandidateKey);
  }

  @Test
  void constructor_distinguishesDifferentCurrencyIsoCodes() {
    var firstTransactionDuplicateCandidateKey = duplicateCandidateKey();
    var secondTransactionDuplicateCandidateKey =
        new TransactionDuplicateCandidateKey(
            "Test Bank",
            LocalDate.of(2024, 1, 15),
            new BigDecimal("12.30"),
            TransactionType.DEBIT,
            "THB");

    assertThat(firstTransactionDuplicateCandidateKey)
        .isNotEqualTo(secondTransactionDuplicateCandidateKey);
  }

  @Test
  void constructor_canonicalizesAmountToScaleTwo() {
    var wholeAmountKey = duplicateCandidateKey(new BigDecimal("12"));
    var scaledAmountKey = duplicateCandidateKey(new BigDecimal("12.00"));
    var roundedAmountKey = duplicateCandidateKey(new BigDecimal("12.005"));

    assertThat(wholeAmountKey).isEqualTo(scaledAmountKey);
    assertThat(wholeAmountKey.amount()).isEqualByComparingTo("12.00");
    assertThat(roundedAmountKey.amount()).isEqualByComparingTo("12.01");
  }

  @Test
  void constructor_requiresNonAccountFields() {
    assertThatNullPointerException()
        .isThrownBy(
            () ->
                new TransactionDuplicateCandidateKey(
                    null,
                    LocalDate.of(2024, 1, 15),
                    new BigDecimal("12.00"),
                    TransactionType.DEBIT,
                    "USD"))
        .withMessage("bankName");
  }

  private static TransactionDuplicateCandidateKey duplicateCandidateKey() {
    return duplicateCandidateKey(new BigDecimal("12.30"));
  }

  private static TransactionDuplicateCandidateKey duplicateCandidateKey(BigDecimal amount) {
    return new TransactionDuplicateCandidateKey(
        "Test Bank", LocalDate.of(2024, 1, 15), amount, TransactionType.DEBIT, "USD");
  }

  private static Transaction transaction(String accountId, BigDecimal amount) {
    var transaction = new Transaction();
    transaction.setAccountId(accountId);
    transaction.setBankName("Test Bank");
    transaction.setDate(LocalDate.of(2024, 1, 15));
    transaction.setAmount(amount);
    transaction.setType(TransactionType.DEBIT);
    transaction.setCurrencyIsoCode("USD");
    return transaction;
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
