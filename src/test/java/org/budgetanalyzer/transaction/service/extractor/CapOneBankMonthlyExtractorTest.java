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

import org.budgetanalyzer.transaction.domain.TransactionType;
import org.budgetanalyzer.transaction.service.dto.PreviewTransaction;

class CapOneBankMonthlyExtractorTest {

  private CapitalOneBankMonthlyStatementExtractor extractor;
  private byte[] pdfContent;

  @BeforeEach
  void setUp() throws IOException {
    extractor = new CapitalOneBankMonthlyStatementExtractor();
    pdfContent =
        Files.readAllBytes(
            Paths.get("src/test/resources/fixtures/cap-one-bank-monthly-sample.pdf"));
  }

  @Test
  void canHandle_withValidCapitalOneMonthlyPdf_returnsTrue() {
    assertTrue(extractor.canHandle(pdfContent, "cap-one-bank-monthly-sample.pdf"));
  }

  @Test
  void canHandle_withCsvFile_returnsFalse() {
    assertFalse(extractor.canHandle(pdfContent, "transactions.csv"));
  }

  @Test
  void canHandle_withNonMatchingPdf_returnsFalse() throws IOException {
    // Year-end summary PDF should not match
    byte[] yearlyPdf =
        Files.readAllBytes(
            Paths.get("src/test/resources/fixtures/cap-one-credit-yearly-summary-sample.pdf"));
    assertFalse(extractor.canHandle(yearlyPdf, "cap-one-credit-yearly-summary-sample.pdf"));
  }

  @Test
  void getFormatKey_returnsCorrectKey() {
    assertEquals("capital-one-bank-monthly-statement", extractor.getFormatKey());
  }

  @Test
  void extract_withSamplePdf_extractsTransactions() {
    StatementExtractor.ExtractionResult result = extractor.extract(pdfContent, null);

    assertNotNull(result);
    assertNotNull(result.transactions());
    assertFalse(result.transactions().isEmpty());

    // November 2025 fixture has 6 transactions (excluding opening/closing balance)
    assertEquals(6, result.transactions().size());
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
  void extract_withSamplePdf_parsesYear2025Correctly() {
    StatementExtractor.ExtractionResult result = extractor.extract(pdfContent, null);

    for (PreviewTransaction transaction : result.transactions()) {
      assertEquals(2025, transaction.date().getYear());
    }
  }

  @Test
  void extract_withSamplePdf_extractsBillPayTransaction() {
    StatementExtractor.ExtractionResult result = extractor.extract(pdfContent, null);

    // Find the ONLINE BILL PAY transaction
    PreviewTransaction billPay =
        result.transactions().stream()
            .filter(t -> t.description().contains("ONLINE BILL PAY"))
            .findFirst()
            .orElse(null);

    assertNotNull(billPay, "Should find ONLINE BILL PAY transaction");
    assertEquals(LocalDate.of(2025, 11, 13), billPay.date());
    assertEquals(new BigDecimal("1862.72"), billPay.amount());
    assertEquals(TransactionType.DEBIT, billPay.type());
  }

  @Test
  void extract_withSamplePdf_extractsDepositTransaction() {
    StatementExtractor.ExtractionResult result = extractor.extract(pdfContent, null);

    // Find the EMPLOYER DIRECT DEPOSIT
    PreviewTransaction deposit =
        result.transactions().stream()
            .filter(t -> t.description().contains("EMPLOYER DIRECT DEPOSIT"))
            .findFirst()
            .orElse(null);

    assertNotNull(deposit, "Should find EMPLOYER DIRECT DEPOSIT transaction");
    assertEquals(LocalDate.of(2025, 11, 24), deposit.date());
    assertEquals(new BigDecimal("5000.00"), deposit.amount());
    assertEquals(TransactionType.CREDIT, deposit.type());
  }

  @Test
  void extract_withSamplePdf_handlesCreditsCorrectly() {
    StatementExtractor.ExtractionResult result = extractor.extract(pdfContent, null);

    // Find credit transactions
    long creditCount =
        result.transactions().stream().filter(t -> t.type() == TransactionType.CREDIT).count();

    // Fixture has 2 credits: EMPLOYER DIRECT DEPOSIT and Monthly Interest Paid
    assertEquals(2, creditCount);
  }

  @Test
  void extract_withSamplePdf_handlesDebitsCorrectly() {
    StatementExtractor.ExtractionResult result = extractor.extract(pdfContent, null);

    // Find debit transactions
    long debitCount =
        result.transactions().stream().filter(t -> t.type() == TransactionType.DEBIT).count();

    // Fixture has 4 debits: ELECTRIC COMPANY, ONLINE BILL PAY, 2x ATM WITHDRAWAL
    assertEquals(4, debitCount);
  }

  @Test
  void extract_withSamplePdf_extractsInterestTransaction() {
    StatementExtractor.ExtractionResult result = extractor.extract(pdfContent, null);

    // Find the Monthly Interest Paid transaction
    PreviewTransaction interest =
        result.transactions().stream()
            .filter(t -> t.description().contains("Monthly Interest"))
            .findFirst()
            .orElse(null);

    assertNotNull(interest, "Should find Monthly Interest Paid transaction");
    assertEquals(LocalDate.of(2025, 11, 30), interest.date());
    assertEquals(new BigDecimal("0.14"), interest.amount());
    assertEquals(TransactionType.CREDIT, interest.type());
  }
}
