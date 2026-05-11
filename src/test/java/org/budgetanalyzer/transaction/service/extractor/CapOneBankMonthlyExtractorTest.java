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
    assertThat(extractor.canHandle(pdfContent, "cap-one-bank-monthly-sample.pdf")).isTrue();
  }

  @Test
  void canHandle_withCsvFile_returnsFalse() {
    assertThat(extractor.canHandle(pdfContent, "transactions.csv")).isFalse();
  }

  @Test
  void canHandle_withNonMatchingPdf_returnsFalse() throws IOException {
    // Year-end summary PDF should not match
    var yearlyPdf =
        Files.readAllBytes(
            Paths.get("src/test/resources/fixtures/cap-one-credit-yearly-summary-sample.pdf"));
    assertThat(extractor.canHandle(yearlyPdf, "cap-one-credit-yearly-summary-sample.pdf"))
        .isFalse();
  }

  @Test
  void getFormatKey_returnsCorrectKey() {
    assertThat(extractor.getFormatKey()).isEqualTo("capital-one-bank-monthly-statement");
  }

  @Test
  void extract_withSamplePdf_extractsTransactions() {
    var transactions = extractor.extract(pdfContent, null);

    // November 2025 fixture has 6 transactions (excluding opening/closing balance)
    assertThat(transactions).hasSize(6);
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
  void extract_withSamplePdf_parsesYear2025Correctly() {
    var transactions = extractor.extract(pdfContent, null);

    for (var previewTransaction : transactions) {
      assertThat(previewTransaction.date().getYear()).isEqualTo(2025);
    }
  }

  @Test
  void extract_withSamplePdf_extractsBillPayTransaction() {
    var transactions = extractor.extract(pdfContent, null);

    // Find the ONLINE BILL PAY transaction
    var billPayTransaction =
        transactions.stream().filter(t -> t.description().contains("ONLINE BILL PAY")).findFirst();

    assertThat(billPayTransaction).as("Should find ONLINE BILL PAY transaction").isPresent();
    assertThat(billPayTransaction.get().date()).isEqualTo(LocalDate.of(2025, 11, 13));
    assertThat(billPayTransaction.get().amount()).isEqualByComparingTo(new BigDecimal("1862.72"));
    assertThat(billPayTransaction.get().type()).isEqualTo(TransactionType.DEBIT);
  }

  @Test
  void extract_withSamplePdf_extractsDepositTransaction() {
    var transactions = extractor.extract(pdfContent, null);

    // Find the EMPLOYER DIRECT DEPOSIT
    var depositTransaction =
        transactions.stream()
            .filter(t -> t.description().contains("EMPLOYER DIRECT DEPOSIT"))
            .findFirst();

    assertThat(depositTransaction)
        .as("Should find EMPLOYER DIRECT DEPOSIT transaction")
        .isPresent();
    assertThat(depositTransaction.get().date()).isEqualTo(LocalDate.of(2025, 11, 24));
    assertThat(depositTransaction.get().amount()).isEqualByComparingTo(new BigDecimal("5000.00"));
    assertThat(depositTransaction.get().type()).isEqualTo(TransactionType.CREDIT);
  }

  @Test
  void extract_withSamplePdf_handlesCreditsCorrectly() {
    var transactions = extractor.extract(pdfContent, null);

    // Find credit transactions
    var creditCount = transactions.stream().filter(t -> t.type() == TransactionType.CREDIT).count();

    // Fixture has 2 credits: EMPLOYER DIRECT DEPOSIT and Monthly Interest Paid
    assertThat(creditCount).isEqualTo(2L);
  }

  @Test
  void extract_withSamplePdf_handlesDebitsCorrectly() {
    var transactions = extractor.extract(pdfContent, null);

    // Find debit transactions
    var debitCount = transactions.stream().filter(t -> t.type() == TransactionType.DEBIT).count();

    // Fixture has 4 debits: ELECTRIC COMPANY, ONLINE BILL PAY, 2x ATM WITHDRAWAL
    assertThat(debitCount).isEqualTo(4L);
  }

  @Test
  void extract_withSamplePdf_extractsInterestTransaction() {
    var transactions = extractor.extract(pdfContent, null);

    // Find the Monthly Interest Paid transaction
    var interestTransaction =
        transactions.stream().filter(t -> t.description().contains("Monthly Interest")).findFirst();

    assertThat(interestTransaction).as("Should find Monthly Interest Paid transaction").isPresent();
    assertThat(interestTransaction.get().date()).isEqualTo(LocalDate.of(2025, 11, 30));
    assertThat(interestTransaction.get().amount()).isEqualByComparingTo(new BigDecimal("0.14"));
    assertThat(interestTransaction.get().type()).isEqualTo(TransactionType.CREDIT);
  }
}
