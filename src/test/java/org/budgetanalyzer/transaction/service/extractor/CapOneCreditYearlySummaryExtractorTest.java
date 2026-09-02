package org.budgetanalyzer.transaction.service.extractor;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.time.LocalDate;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import org.budgetanalyzer.transaction.domain.ParserRevision;
import org.budgetanalyzer.transaction.domain.StatementFormat;
import org.budgetanalyzer.transaction.domain.TransactionType;
import org.budgetanalyzer.transaction.service.dto.ParserAttemptStatus;
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
  void attemptWithValidCapitalOnePdfMatches() {
    var parserAttempt = attempt(pdfContent, "cap-one-credit-yearly-summary-sample.pdf", null);

    assertThat(parserAttempt.status()).isEqualTo(ParserAttemptStatus.MATCHED);
  }

  @Test
  void attemptWithCsvFileReturnsNotApplicable() {
    var parserAttempt = attempt(pdfContent, "transactions.csv", null);

    assertThat(parserAttempt.status()).isEqualTo(ParserAttemptStatus.NOT_APPLICABLE);
  }

  @Test
  void attemptWithNonMatchingPdfReturnsNotApplicable() {
    // Random bytes won't match
    var randomBytes = new byte[] {0x25, 0x50, 0x44, 0x46}; // PDF magic bytes only

    var parserAttempt = attempt(randomBytes, "random.pdf", null);

    assertThat(parserAttempt.status()).isEqualTo(ParserAttemptStatus.NOT_APPLICABLE);
  }

  @Test
  void getHandlerKeyReturnsCorrectKey() {
    assertThat(extractor.getHandlerKey()).isEqualTo("capital-one-credit-yearly-statement");
  }

  @Test
  void extractWithSamplePdfExtractsTransactions() {
    var transactions = transactions(null);

    // Fixture contains 15 transactions across 7 categories
    assertThat(transactions).hasSizeGreaterThan(10);
  }

  @Test
  void extractWithSamplePdfSetsCorrectBankAndCurrency() {
    var transactions = transactions(null);

    for (var previewTransaction : transactions) {
      assertThat(previewTransaction.bankName()).isEqualTo("Capital One");
      assertThat(previewTransaction.currencyIsoCode()).isEqualTo("USD");
    }
  }

  @Test
  void extractWithAccountIdSetsAccountIdOnAllTransactions() {
    var accountId = "test-account-123";
    var transactions = transactions(accountId);

    for (var previewTransaction : transactions) {
      assertThat(previewTransaction.accountId()).isEqualTo(accountId);
    }
  }

  @Test
  void extractWithSamplePdfParsesYear2024Correctly() {
    var transactions = transactions(null);

    for (var previewTransaction : transactions) {
      assertThat(previewTransaction.date().getYear()).isEqualTo(2024);
    }
  }

  @Test
  void extractWithSamplePdfExtractsKnownTransactions() {
    var transactions = transactions(null);

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
  void extractWithSamplePdfHandlesCreditsCorrectly() {
    var transactions = transactions(null);

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
  void extractWithSamplePdfExtractsCategories() {
    var transactions = transactions(null);

    // Verify we have multiple categories
    var categoryCount =
        transactions.stream()
            .map(previewTransaction -> previewTransaction.category())
            .filter(c -> c != null)
            .distinct()
            .count();

    assertThat(categoryCount).isGreaterThanOrEqualTo(5L);
  }

  private org.budgetanalyzer.transaction.service.dto.ParserAttempt attempt(
      byte[] content, String filename, String accountId) {
    return extractor.attempt(parserRevision(), content, filename, accountId);
  }

  private java.util.List<PreviewTransaction> transactions(String accountId) {
    var parserAttempt = attempt(pdfContent, "cap-one-credit-yearly-summary-sample.pdf", accountId);
    assertThat(parserAttempt.status()).isEqualTo(ParserAttemptStatus.MATCHED);
    return parserAttempt.transactions();
  }

  private ParserRevision parserRevision() {
    var statementFormat =
        StatementFormat.createSystemPdfFormat("Capital One Yearly", "Capital One", "USD");
    return ParserRevision.createStaticHandler(
        statementFormat, 1, "capital-one-credit-yearly-statement");
  }
}
