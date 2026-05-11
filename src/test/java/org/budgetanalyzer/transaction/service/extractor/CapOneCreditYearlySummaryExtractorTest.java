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
    assertThat(extractor.canHandle(pdfContent, "cap-one-credit-yearly-summary-sample.pdf"))
        .isTrue();
  }

  @Test
  void canHandle_withCsvFile_returnsFalse() {
    assertThat(extractor.canHandle(pdfContent, "transactions.csv")).isFalse();
  }

  @Test
  void canHandle_withNonMatchingPdf_returnsFalse() {
    // Random bytes won't match
    var randomBytes = new byte[] {0x25, 0x50, 0x44, 0x46}; // PDF magic bytes only
    assertThat(extractor.canHandle(randomBytes, "random.pdf")).isFalse();
  }

  @Test
  void getFormatKey_returnsCorrectKey() {
    assertThat(extractor.getFormatKey()).isEqualTo("capital-one-credit-yearly-statement");
  }

  @Test
  void extract_withSamplePdf_extractsTransactions() {
    var transactions = extractor.extract(pdfContent, null);

    // Fixture contains 15 transactions across 7 categories
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
  void extract_withSamplePdf_parsesYear2024Correctly() {
    var transactions = extractor.extract(pdfContent, null);

    for (var previewTransaction : transactions) {
      assertThat(previewTransaction.date().getYear()).isEqualTo(2024);
    }
  }

  @Test
  void extract_withSamplePdf_extractsKnownTransactions() {
    var transactions = extractor.extract(pdfContent, null);

    // Find the TAQUERIA DEL SOL transaction from the dining category
    var taqueriaTransaction =
        transactions.stream().filter(t -> t.description().contains("TAQUERIA DEL SOL")).findFirst();

    assertThat(taqueriaTransaction).as("Should find TAQUERIA DEL SOL transaction").isPresent();
    assertThat(taqueriaTransaction.get().date()).isEqualTo(LocalDate.of(2024, 4, 12));
    assertThat(taqueriaTransaction.get().amount()).isEqualByComparingTo(new BigDecimal("55.12"));
    assertThat(taqueriaTransaction.get().type()).isEqualTo(TransactionType.DEBIT);
    assertThat(taqueriaTransaction.get().category()).isEqualTo("Dining");
  }

  @Test
  void extract_withSamplePdf_handlesCreditsCorrectly() {
    var transactions = extractor.extract(pdfContent, null);

    // Find the REFUND FROM ONLINE SHOP credit (-$37.27)
    var creditTransaction =
        transactions.stream()
            .filter(
                t ->
                    t.description().contains("REFUND FROM ONLINE SHOP")
                        && t.amount().compareTo(new BigDecimal("37.27")) == 0
                        && t.type() == TransactionType.CREDIT)
            .findFirst();

    assertThat(creditTransaction)
        .as("Should find a REFUND FROM ONLINE SHOP credit transaction")
        .isPresent();
    assertThat(creditTransaction.get().type()).isEqualTo(TransactionType.CREDIT);
  }

  @Test
  void extract_withSamplePdf_extractsCategories() {
    var transactions = extractor.extract(pdfContent, null);

    // Verify we have multiple categories
    var categoryCount =
        transactions.stream()
            .map(previewTransaction -> previewTransaction.category())
            .filter(c -> c != null)
            .distinct()
            .count();

    assertThat(categoryCount).isGreaterThanOrEqualTo(5L);
  }
}
