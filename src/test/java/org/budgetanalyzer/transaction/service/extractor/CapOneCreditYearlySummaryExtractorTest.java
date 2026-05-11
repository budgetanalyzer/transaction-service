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

class CapOneCreditYearlySummaryExtractorTest {

  private CapitalOneCreditYearlySummaryExtractor extractor;
  private byte[] pdfContent;

  @BeforeEach
  void setUp() throws IOException {
    extractor = new CapitalOneCreditYearlySummaryExtractor();
    pdfContent =
        Files.readAllBytes(
            Paths.get("src/test/resources/fixtures/cap-one-credit-yearly-summary-sample.pdf"));
  }

  @Test
  void canHandle_withValidCapitalOnePdf_returnsTrue() {
    assertTrue(extractor.canHandle(pdfContent, "cap-one-credit-yearly-summary-sample.pdf"));
  }

  @Test
  void canHandle_withCsvFile_returnsFalse() {
    assertFalse(extractor.canHandle(pdfContent, "transactions.csv"));
  }

  @Test
  void canHandle_withNonMatchingPdf_returnsFalse() throws IOException {
    // Random bytes won't match
    byte[] randomBytes = new byte[] {0x25, 0x50, 0x44, 0x46}; // PDF magic bytes only
    assertFalse(extractor.canHandle(randomBytes, "random.pdf"));
  }

  @Test
  void getFormatKey_returnsCorrectKey() {
    assertEquals("capital-one-credit-yearly-statement", extractor.getFormatKey());
  }

  @Test
  void extract_withSamplePdf_extractsTransactions() {
    var transactions = extractor.extract(pdfContent, null);

    assertNotNull(transactions);
    assertFalse(transactions.isEmpty());

    // Fixture contains 15 transactions across 7 categories
    assertTrue(
        transactions.size() > 10,
        "Expected more than 10 transactions but got " + transactions.size());
  }

  @Test
  void extract_withSamplePdf_setsCorrectBankAndCurrency() {
    var transactions = extractor.extract(pdfContent, null);

    for (PreviewTransaction transaction : transactions) {
      assertEquals("Capital One", transaction.bankName());
      assertEquals("USD", transaction.currencyIsoCode());
    }
  }

  @Test
  void extract_withAccountId_setsAccountIdOnAllTransactions() {
    String accountId = "test-account-123";
    var transactions = extractor.extract(pdfContent, accountId);

    for (PreviewTransaction transaction : transactions) {
      assertEquals(accountId, transaction.accountId());
    }
  }

  @Test
  void extract_withSamplePdf_parsesYear2024Correctly() {
    var transactions = extractor.extract(pdfContent, null);

    for (PreviewTransaction transaction : transactions) {
      assertEquals(2024, transaction.date().getYear());
    }
  }

  @Test
  void extract_withSamplePdf_extractsKnownTransactions() {
    var transactions = extractor.extract(pdfContent, null);

    // Find the TAQUERIA DEL SOL transaction from the dining category
    PreviewTransaction taqueriaTransaction =
        transactions.stream()
            .filter(t -> t.description().contains("TAQUERIA DEL SOL"))
            .findFirst()
            .orElse(null);

    assertNotNull(taqueriaTransaction, "Should find TAQUERIA DEL SOL transaction");
    assertEquals(LocalDate.of(2024, 4, 12), taqueriaTransaction.date());
    assertEquals(new BigDecimal("55.12"), taqueriaTransaction.amount());
    assertEquals(TransactionType.DEBIT, taqueriaTransaction.type());
    assertEquals("Dining", taqueriaTransaction.category());
  }

  @Test
  void extract_withSamplePdf_handlesCreditsCorrectly() {
    var transactions = extractor.extract(pdfContent, null);

    // Find the REFUND FROM ONLINE SHOP credit (-$37.27)
    PreviewTransaction creditTransaction =
        transactions.stream()
            .filter(
                t ->
                    t.description().contains("REFUND FROM ONLINE SHOP")
                        && t.amount().compareTo(new BigDecimal("37.27")) == 0
                        && t.type() == TransactionType.CREDIT)
            .findFirst()
            .orElse(null);

    assertNotNull(creditTransaction, "Should find a REFUND FROM ONLINE SHOP credit transaction");
    assertEquals(TransactionType.CREDIT, creditTransaction.type());
  }

  @Test
  void extract_withSamplePdf_extractsCategories() {
    var transactions = extractor.extract(pdfContent, null);

    // Verify we have multiple categories
    long categoryCount =
        transactions.stream()
            .map(PreviewTransaction::category)
            .filter(c -> c != null)
            .distinct()
            .count();

    assertTrue(categoryCount >= 5, "Expected at least 5 different categories");
  }
}
