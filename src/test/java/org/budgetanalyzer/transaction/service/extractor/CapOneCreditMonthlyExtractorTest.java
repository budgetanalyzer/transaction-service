package org.budgetanalyzer.transaction.service.extractor;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.time.LocalDate;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import org.budgetanalyzer.transaction.domain.TransactionType;

class CapOneCreditMonthlyExtractorTest {

  private CapitalOneCreditMonthlyStatementExtractor extractor;
  private byte[] pdfContent;

  @BeforeEach
  void setUp() throws IOException {
    extractor = new CapitalOneCreditMonthlyStatementExtractor();
    pdfContent =
        Files.readAllBytes(
            Paths.get("src/test/resources/fixtures/cap-one-credit-monthly-sample.pdf"));
  }

  @Test
  void canHandle_withValidCapitalOneCreditMonthlyPdf_returnsTrue() {
    assertThat(extractor.canHandle(pdfContent, "cap-one-credit-monthly-sample.pdf")).isTrue();
  }

  @Test
  void canHandle_withCsvFile_returnsFalse() {
    assertThat(extractor.canHandle(pdfContent, "transactions.csv")).isFalse();
  }

  @Test
  void canHandle_withNonMatchingPdf_returnsFalse() throws IOException {
    // Bank statement PDF should not match (different format)
    var bankPdf =
        Files.readAllBytes(
            Paths.get("src/test/resources/fixtures/cap-one-bank-monthly-sample.pdf"));
    assertThat(extractor.canHandle(bankPdf, "cap-one-bank-monthly-sample.pdf")).isFalse();
  }

  @Test
  void canHandle_withYearlySummaryPdf_returnsFalse() throws IOException {
    // Year-end summary PDF should not match
    var yearlyPdf =
        Files.readAllBytes(
            Paths.get("src/test/resources/fixtures/cap-one-credit-yearly-summary-sample.pdf"));
    assertThat(extractor.canHandle(yearlyPdf, "cap-one-credit-yearly-summary-sample.pdf"))
        .isFalse();
  }

  @Test
  void getFormatKey_returnsCorrectKey() {
    assertThat(extractor.getFormatKey()).isEqualTo("capital-one-credit-monthly-statement");
  }

  @Test
  void extract_withSamplePdf_extractsTransactions() {
    var transactions = extractor.extract(pdfContent, null);

    // Fixture contains 13 transactions (2 payments + 11 purchases including 1 refund)
    assertThat(transactions).hasSizeGreaterThan(10);
  }

  @Test
  void extract_withSamplePdf_setsCorrectBankAndCurrency() {
    var transactions = extractor.extract(pdfContent, null);

    for (var previewTransaction : transactions) {
      assertThat(previewTransaction.bankName()).isEqualTo("Capital One");
      assertThat(previewTransaction.currencyIsoCode()).isEqualTo("USD");
    }
  }

  @Test
  void extract_withAccountId_setsAccountIdOnAllTransactions() {
    var accountId = "test-account-123";
    var transactions = extractor.extract(pdfContent, accountId);

    for (var previewTransaction : transactions) {
      assertThat(previewTransaction.accountId()).isEqualTo(accountId);
    }
  }

  @Test
  void extract_withSamplePdf_parsesDateCorrectly() {
    var transactions = extractor.extract(pdfContent, null);

    // Statement period is Nov 19, 2025 - Dec 19, 2025
    // All transactions should be in Nov or Dec 2025
    for (var previewTransaction : transactions) {
      assertThat(previewTransaction.date().getYear()).isEqualTo(2025);
      var month = previewTransaction.date().getMonthValue();
      assertThat(month)
          .as("Expected Nov or Dec for " + previewTransaction.description())
          .isIn(11, 12);
    }
  }

  @Test
  void extract_withSamplePdf_extractsPaymentsAsCredits() {
    var transactions = extractor.extract(pdfContent, null);

    // Find the ONLINE PAYMENT THANK YOU transaction ($500)
    var paymentTransaction =
        transactions.stream()
            .filter(t -> t.description().contains("ONLINE PAYMENT THANK YOU"))
            .findFirst();

    assertThat(paymentTransaction)
        .as("Should find ONLINE PAYMENT THANK YOU transaction")
        .isPresent();
    assertThat(paymentTransaction.get().type()).isEqualTo(TransactionType.CREDIT);
    assertThat(paymentTransaction.get().amount()).isEqualByComparingTo(new BigDecimal("500.00"));
  }

  @Test
  void extract_withSamplePdf_extractsPurchasesAsDebits() {
    var transactions = extractor.extract(pdfContent, null);

    // Find the ACME SOFTWARE SUBSCRIPTION transaction
    var purchaseTransaction =
        transactions.stream()
            .filter(t -> t.description().contains("ACME SOFTWARE SUBSCRIPTION"))
            .findFirst();

    assertThat(purchaseTransaction)
        .as("Should find ACME SOFTWARE SUBSCRIPTION transaction")
        .isPresent();
    assertThat(purchaseTransaction.get().type()).isEqualTo(TransactionType.DEBIT);
    assertThat(purchaseTransaction.get().amount()).isEqualByComparingTo(new BigDecimal("166.74"));
    assertThat(purchaseTransaction.get().date()).isEqualTo(LocalDate.of(2025, 11, 24));
  }

  @Test
  void extract_withSamplePdf_extractsKnownTransaction() {
    var transactions = extractor.extract(pdfContent, null);

    // Find the TECH COMPANY transaction ($5.00)
    var techCompanyTransaction =
        transactions.stream().filter(t -> t.description().contains("TECH COMPANY")).findFirst();

    assertThat(techCompanyTransaction).as("Should find TECH COMPANY transaction").isPresent();
    assertThat(techCompanyTransaction.get().date()).isEqualTo(LocalDate.of(2025, 11, 28));
    assertThat(techCompanyTransaction.get().amount()).isEqualByComparingTo(new BigDecimal("5.00"));
    assertThat(techCompanyTransaction.get().type()).isEqualTo(TransactionType.DEBIT);
  }

  @Test
  void extract_withSamplePdf_extractsRefundAsCredit() {
    var transactions = extractor.extract(pdfContent, null);

    // Find the MERCHANDISE RETURN refund (- $124.99)
    var refundTransaction =
        transactions.stream()
            .filter(t -> t.description().contains("MERCHANDISE RETURN"))
            .findFirst();

    assertThat(refundTransaction).as("Should find MERCHANDISE RETURN transaction").isPresent();
    assertThat(refundTransaction.get().type()).isEqualTo(TransactionType.CREDIT);
    assertThat(refundTransaction.get().amount()).isEqualByComparingTo(new BigDecimal("124.99"));
    assertThat(refundTransaction.get().date()).isEqualTo(LocalDate.of(2025, 11, 22));
  }

  @Test
  void extract_withSamplePdf_handlesLargeAmounts() {
    var transactions = extractor.extract(pdfContent, null);

    // Find the FURNITURE STORE ONLINE transaction ($2,400.32)
    var largeTransaction =
        transactions.stream()
            .filter(
                t ->
                    t.description().contains("FURNITURE STORE ONLINE")
                        && t.amount().compareTo(new BigDecimal("2400.32")) == 0)
            .findFirst();

    assertThat(largeTransaction)
        .as("Should find FURNITURE STORE ONLINE transaction for $2,400.32")
        .isPresent();
    assertThat(largeTransaction.get().amount()).isEqualByComparingTo(new BigDecimal("2400.32"));
    assertThat(largeTransaction.get().type()).isEqualTo(TransactionType.DEBIT);
  }

  @Test
  void extract_withSamplePdf_hasCorrectTransactionCounts() {
    var transactions = extractor.extract(pdfContent, null);

    var credits = transactions.stream().filter(t -> t.type() == TransactionType.CREDIT).count();
    var debits = transactions.stream().filter(t -> t.type() == TransactionType.DEBIT).count();

    // Should have 3 credits (2 payments + 1 refund) and 10 debits (purchases)
    assertThat(credits).isGreaterThanOrEqualTo(2L);
    assertThat(debits).isGreaterThan(8L);
  }
}
