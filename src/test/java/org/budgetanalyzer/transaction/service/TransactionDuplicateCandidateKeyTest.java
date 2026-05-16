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
  void fromPreviewTransaction_includesFinancialIdentityFieldsOnly() {
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

    assertThat(transactionDuplicateCandidateKey.accountId()).isEqualTo("checking");
    assertThat(transactionDuplicateCandidateKey.bankName()).isEqualTo("Test Bank");
    assertThat(transactionDuplicateCandidateKey.date()).isEqualTo(LocalDate.of(2024, 1, 15));
    assertThat(transactionDuplicateCandidateKey.amount()).isEqualByComparingTo("12.30");
    assertThat(transactionDuplicateCandidateKey.type()).isEqualTo(TransactionType.DEBIT);
    assertThat(transactionDuplicateCandidateKey.currencyIsoCode()).isEqualTo("USD");
  }

  @Test
  void fromPreviewTransaction_usesSameKeyForDifferentDescriptions() {
    var firstPreviewTransaction = previewTransaction("Coffee");
    var secondPreviewTransaction = previewTransaction("Coffee Shop");

    var firstTransactionDuplicateCandidateKey =
        TransactionDuplicateCandidateKey.from(firstPreviewTransaction);
    var secondTransactionDuplicateCandidateKey =
        TransactionDuplicateCandidateKey.from(secondPreviewTransaction);

    assertThat(firstTransactionDuplicateCandidateKey)
        .isEqualTo(secondTransactionDuplicateCandidateKey);
  }

  @Test
  void fromTransaction_includesFinancialIdentityFieldsOnly() {
    var transaction = transaction("checking", new BigDecimal("12.30"));
    transaction.setDescription("Coffee");

    var transactionDuplicateCandidateKey = TransactionDuplicateCandidateKey.from(transaction);

    assertThat(transactionDuplicateCandidateKey)
        .isEqualTo(
            new TransactionDuplicateCandidateKey(
                "checking",
                "Test Bank",
                LocalDate.of(2024, 1, 15),
                new BigDecimal("12.30"),
                TransactionType.DEBIT,
                "USD"));
  }

  @Test
  void constructor_distinguishesDifferentAccountIds() {
    var firstTransactionDuplicateCandidateKey = duplicateCandidateKey("checking");
    var secondTransactionDuplicateCandidateKey = duplicateCandidateKey("savings");

    assertThat(firstTransactionDuplicateCandidateKey)
        .isNotEqualTo(secondTransactionDuplicateCandidateKey);
  }

  @Test
  void constructor_distinguishesDifferentBankNames() {
    var firstTransactionDuplicateCandidateKey =
        new TransactionDuplicateCandidateKey(
            "checking",
            "Test Bank",
            LocalDate.of(2024, 1, 15),
            new BigDecimal("12.30"),
            TransactionType.DEBIT,
            "USD");
    var secondTransactionDuplicateCandidateKey =
        new TransactionDuplicateCandidateKey(
            "checking",
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
    var firstTransactionDuplicateCandidateKey =
        new TransactionDuplicateCandidateKey(
            "checking",
            "Test Bank",
            LocalDate.of(2024, 1, 15),
            new BigDecimal("12.30"),
            TransactionType.DEBIT,
            "USD");
    var secondTransactionDuplicateCandidateKey =
        new TransactionDuplicateCandidateKey(
            "checking",
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
    var firstTransactionDuplicateCandidateKey =
        duplicateCandidateKey("checking", new BigDecimal("12.30"));
    var secondTransactionDuplicateCandidateKey =
        duplicateCandidateKey("checking", new BigDecimal("12.31"));

    assertThat(firstTransactionDuplicateCandidateKey)
        .isNotEqualTo(secondTransactionDuplicateCandidateKey);
  }

  @Test
  void constructor_distinguishesDifferentTypes() {
    var firstTransactionDuplicateCandidateKey =
        new TransactionDuplicateCandidateKey(
            "checking",
            "Test Bank",
            LocalDate.of(2024, 1, 15),
            new BigDecimal("12.30"),
            TransactionType.DEBIT,
            "USD");
    var secondTransactionDuplicateCandidateKey =
        new TransactionDuplicateCandidateKey(
            "checking",
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
    var firstTransactionDuplicateCandidateKey =
        new TransactionDuplicateCandidateKey(
            "checking",
            "Test Bank",
            LocalDate.of(2024, 1, 15),
            new BigDecimal("12.30"),
            TransactionType.DEBIT,
            "USD");
    var secondTransactionDuplicateCandidateKey =
        new TransactionDuplicateCandidateKey(
            "checking",
            "Test Bank",
            LocalDate.of(2024, 1, 15),
            new BigDecimal("12.30"),
            TransactionType.DEBIT,
            "THB");

    assertThat(firstTransactionDuplicateCandidateKey)
        .isNotEqualTo(secondTransactionDuplicateCandidateKey);
  }

  @Test
  void constructor_emptyAccountIdEqualsNullAccountId() {
    var emptyAccountKey = duplicateCandidateKey("");
    var nullAccountKey = duplicateCandidateKey(null);

    assertThat(emptyAccountKey).isEqualTo(nullAccountKey);
    assertThat(emptyAccountKey.accountId()).isNull();
  }

  @Test
  void constructor_canonicalizesAmountToScaleTwo() {
    var wholeAmountKey = duplicateCandidateKey("checking", new BigDecimal("12"));
    var scaledAmountKey = duplicateCandidateKey("checking", new BigDecimal("12.00"));
    var roundedAmountKey = duplicateCandidateKey("checking", new BigDecimal("12.005"));

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
                    null,
                    LocalDate.of(2024, 1, 15),
                    new BigDecimal("12.00"),
                    TransactionType.DEBIT,
                    "USD"))
        .withMessage("bankName");
  }

  private static TransactionDuplicateCandidateKey duplicateCandidateKey(String accountId) {
    return duplicateCandidateKey(accountId, new BigDecimal("12.30"));
  }

  private static TransactionDuplicateCandidateKey duplicateCandidateKey(
      String accountId, BigDecimal amount) {
    return new TransactionDuplicateCandidateKey(
        accountId, "Test Bank", LocalDate.of(2024, 1, 15), amount, TransactionType.DEBIT, "USD");
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

  private static PreviewTransaction previewTransaction(String description) {
    return new PreviewTransaction(
        LocalDate.of(2024, 1, 15),
        description,
        new BigDecimal("12.30"),
        TransactionType.DEBIT,
        null,
        "Test Bank",
        "USD",
        "checking");
  }
}
