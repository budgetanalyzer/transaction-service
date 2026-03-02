package org.budgetanalyzer.transaction.service.extractor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.time.LocalDate;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import org.budgetanalyzer.transaction.api.response.PreviewTransaction;
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
    assertTrue(extractor.canHandle(pdfContent, "cap-one-credit-monthly-sample.pdf"));
  }

  @Test
  void canHandle_withCsvFile_returnsFalse() {
    assertFalse(extractor.canHandle(pdfContent, "transactions.csv"));
  }

  @Test
  void canHandle_withNonMatchingPdf_returnsFalse() throws IOException {
    // Bank statement PDF should not match (different format)
    byte[] bankPdf =
        Files.readAllBytes(
            Paths.get("src/test/resources/fixtures/cap-one-bank-monthly-sample.pdf"));
    assertFalse(extractor.canHandle(bankPdf, "cap-one-bank-monthly-sample.pdf"));
  }

  @Test
  void canHandle_withYearlySummaryPdf_returnsFalse() throws IOException {
    // Year-end summary PDF should not match
    byte[] yearlyPdf =
        Files.readAllBytes(
            Paths.get("src/test/resources/fixtures/cap-one-credit-yearly-summary-sample.pdf"));
    assertFalse(extractor.canHandle(yearlyPdf, "cap-one-credit-yearly-summary-sample.pdf"));
  }

  @Test
  void getFormatKey_returnsCorrectKey() {
    assertEquals("capital-one-credit-monthly-statement", extractor.getFormatKey());
  }

  @Test
  void extract_withSamplePdf_extractsTransactions() {
    StatementExtractor.ExtractionResult result = extractor.extract(pdfContent, null);

    assertNotNull(result);
    assertNotNull(result.transactions());
    assertFalse(result.transactions().isEmpty());

    // Fixture contains 13 transactions (2 payments + 11 purchases including 1 refund)
    assertTrue(
        result.transactions().size() > 10,
        "Expected more than 10 transactions but got " + result.transactions().size());
  }

  @Test
  void extract_withSamplePdf_setsCorrectBankAndCurrency() {
    StatementExtractor.ExtractionResult result = extractor.extract(pdfContent, null);

    for (PreviewTransaction transaction : result.transactions()) {
      assertEquals("Capital One", transaction.bankName());
      assertEquals("USD", transaction.currencyIsoCode());
    }
  }

  @Test
  void extract_withAccountId_setsAccountIdOnAllTransactions() {
    String accountId = "test-account-123";
    StatementExtractor.ExtractionResult result = extractor.extract(pdfContent, accountId);

    for (PreviewTransaction transaction : result.transactions()) {
      assertEquals(accountId, transaction.accountId());
    }
  }

  @Test
  void extract_withSamplePdf_parsesDateCorrectly() {
    StatementExtractor.ExtractionResult result = extractor.extract(pdfContent, null);

    // Statement period is Nov 19, 2025 - Dec 19, 2025
    // All transactions should be in Nov or Dec 2025
    for (PreviewTransaction transaction : result.transactions()) {
      assertEquals(2025, transaction.date().getYear());
      int month = transaction.date().getMonthValue();
      assertTrue(
          month == 11 || month == 12,
          "Expected Nov or Dec but got month " + month + " for " + transaction.description());
    }
  }

  @Test
  void extract_withSamplePdf_extractsPaymentsAsCredits() {
    StatementExtractor.ExtractionResult result = extractor.extract(pdfContent, null);

    // Find the ONLINE PAYMENT THANK YOU transaction ($500)
    PreviewTransaction payment =
        result.transactions().stream()
            .filter(t -> t.description().contains("ONLINE PAYMENT THANK YOU"))
            .findFirst()
            .orElse(null);

    assertNotNull(payment, "Should find ONLINE PAYMENT THANK YOU transaction");
    assertEquals(TransactionType.CREDIT, payment.type());
    assertEquals(new BigDecimal("500.00"), payment.amount());
  }

  @Test
  void extract_withSamplePdf_extractsPurchasesAsDebits() {
    StatementExtractor.ExtractionResult result = extractor.extract(pdfContent, null);

    // Find the ACME SOFTWARE SUBSCRIPTION transaction
    PreviewTransaction purchase =
        result.transactions().stream()
            .filter(t -> t.description().contains("ACME SOFTWARE SUBSCRIPTION"))
            .findFirst()
            .orElse(null);

    assertNotNull(purchase, "Should find ACME SOFTWARE SUBSCRIPTION transaction");
    assertEquals(TransactionType.DEBIT, purchase.type());
    assertEquals(new BigDecimal("166.74"), purchase.amount());
    assertEquals(LocalDate.of(2025, 11, 24), purchase.date());
  }

  @Test
  void extract_withSamplePdf_extractsKnownTransaction() {
    StatementExtractor.ExtractionResult result = extractor.extract(pdfContent, null);

    // Find the TECH COMPANY transaction ($5.00)
    PreviewTransaction techCompany =
        result.transactions().stream()
            .filter(t -> t.description().contains("TECH COMPANY"))
            .findFirst()
            .orElse(null);

    assertNotNull(techCompany, "Should find TECH COMPANY transaction");
    assertEquals(LocalDate.of(2025, 11, 28), techCompany.date());
    assertEquals(new BigDecimal("5.00"), techCompany.amount());
    assertEquals(TransactionType.DEBIT, techCompany.type());
  }

  @Test
  void extract_withSamplePdf_extractsRefundAsCredit() {
    StatementExtractor.ExtractionResult result = extractor.extract(pdfContent, null);

    // Find the MERCHANDISE RETURN refund (- $124.99)
    PreviewTransaction refund =
        result.transactions().stream()
            .filter(t -> t.description().contains("MERCHANDISE RETURN"))
            .findFirst()
            .orElse(null);

    assertNotNull(refund, "Should find MERCHANDISE RETURN transaction");
    assertEquals(TransactionType.CREDIT, refund.type());
    assertEquals(new BigDecimal("124.99"), refund.amount());
    assertEquals(LocalDate.of(2025, 11, 22), refund.date());
  }

  @Test
  void extract_withSamplePdf_handlesLargeAmounts() {
    StatementExtractor.ExtractionResult result = extractor.extract(pdfContent, null);

    // Find the FURNITURE STORE ONLINE transaction ($2,400.32)
    PreviewTransaction largeTransaction =
        result.transactions().stream()
            .filter(
                t ->
                    t.description().contains("FURNITURE STORE ONLINE")
                        && t.amount().compareTo(new BigDecimal("2400.32")) == 0)
            .findFirst()
            .orElse(null);

    assertNotNull(largeTransaction, "Should find FURNITURE STORE ONLINE transaction for $2,400.32");
    assertEquals(new BigDecimal("2400.32"), largeTransaction.amount());
    assertEquals(TransactionType.DEBIT, largeTransaction.type());
  }

  @Test
  void extract_withSamplePdf_hasCorrectTransactionCounts() {
    StatementExtractor.ExtractionResult result = extractor.extract(pdfContent, null);

    long credits =
        result.transactions().stream().filter(t -> t.type() == TransactionType.CREDIT).count();
    long debits =
        result.transactions().stream().filter(t -> t.type() == TransactionType.DEBIT).count();

    // Should have 3 credits (2 payments + 1 refund) and 10 debits (purchases)
    assertTrue(credits >= 2, "Expected at least 2 credits but got " + credits);
    assertTrue(debits > 8, "Expected more than 8 debits but got " + debits);
  }
}
