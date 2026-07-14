package org.budgetanalyzer.transaction.service.extractor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.time.LocalDate;
import java.util.List;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import org.budgetanalyzer.transaction.domain.ParserRevision;
import org.budgetanalyzer.transaction.domain.StatementFormat;
import org.budgetanalyzer.transaction.domain.TransactionType;
import org.budgetanalyzer.transaction.service.BudgetAnalyzerError;
import org.budgetanalyzer.transaction.service.dto.ParserAttemptStatus;
import org.budgetanalyzer.transaction.service.dto.PreviewTransaction;

class CapOneCreditMonthlyExtractorTest {

  private static final float FONT_SIZE = 10f;
  private static final float LEADING = 14f;
  private static final float LEFT_MARGIN = 50f;
  private static final float TOP_START = 750f;

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
  void attempt_withValidCapitalOneCreditMonthlyPdf_matches() {
    var parserAttempt = attempt(pdfContent, "cap-one-credit-monthly-sample.pdf", null);

    assertThat(parserAttempt.status()).isEqualTo(ParserAttemptStatus.MATCHED);
  }

  @Test
  void attempt_withCsvFile_returnsNotApplicable() {
    var parserAttempt = attempt(pdfContent, "transactions.csv", null);

    assertThat(parserAttempt.status()).isEqualTo(ParserAttemptStatus.NOT_APPLICABLE);
  }

  @Test
  void attempt_withNonMatchingPdf_returnsNotApplicable() throws IOException {
    // Bank statement PDF should not match (different format)
    var bankPdf =
        Files.readAllBytes(
            Paths.get("src/test/resources/fixtures/cap-one-bank-monthly-sample.pdf"));

    var parserAttempt = attempt(bankPdf, "cap-one-bank-monthly-sample.pdf", null);

    assertThat(parserAttempt.status()).isEqualTo(ParserAttemptStatus.NOT_APPLICABLE);
  }

  @Test
  void attempt_withYearlySummaryPdf_returnsNotApplicable() throws IOException {
    // Year-end summary PDF should not match
    var yearlyPdf =
        Files.readAllBytes(
            Paths.get("src/test/resources/fixtures/cap-one-credit-yearly-summary-sample.pdf"));

    var parserAttempt = attempt(yearlyPdf, "cap-one-credit-yearly-summary-sample.pdf", null);

    assertThat(parserAttempt.status()).isEqualTo(ParserAttemptStatus.NOT_APPLICABLE);
  }

  @Test
  void getHandlerKey_returnsCorrectKey() {
    assertThat(extractor.getHandlerKey()).isEqualTo("capital-one-credit-monthly-statement");
  }

  @Test
  void extract_withSamplePdf_extractsTransactions() {
    var transactions = transactions(null);

    // Fixture contains 13 transactions (2 payments + 11 purchases including 1 refund)
    assertThat(transactions).hasSizeGreaterThan(10);
  }

  @Test
  void extract_withSplitColumnPdf_extractsTransactions() throws IOException {
    var transactions = transactions(splitColumnPdfContent(), "credit-card-001");

    assertThat(transactions)
        .extracting("date", "description", "amount", "type", "accountId")
        .containsExactly(
            tuple(
                LocalDate.of(2026, 5, 2),
                "CREDIT-CASH BACK REWARD",
                new BigDecimal("450.68"),
                TransactionType.CREDIT,
                "credit-card-001"),
            tuple(
                LocalDate.of(2026, 5, 3),
                "ONLINE PAYMENT THANK YOU",
                new BigDecimal("100.00"),
                TransactionType.CREDIT,
                "credit-card-001"),
            tuple(
                LocalDate.of(2026, 5, 4),
                "CORNER GROCERY ANYTOWN CA",
                new BigDecimal("84.12"),
                TransactionType.DEBIT,
                "credit-card-001"),
            tuple(
                LocalDate.of(2026, 5, 6),
                "AIRLINE TICKET PURCHASE",
                new BigDecimal("1200.00"),
                TransactionType.DEBIT,
                "credit-card-001"),
            tuple(
                LocalDate.of(2026, 5, 8),
                "FOREIGN MARKETPLACE",
                new BigDecimal("24.10"),
                TransactionType.DEBIT,
                "credit-card-001"),
            tuple(
                LocalDate.of(2026, 5, 10),
                "STREAMING SERVICE",
                new BigDecimal("15.99"),
                TransactionType.DEBIT,
                "credit-card-001"),
            tuple(
                LocalDate.of(2026, 5, 18),
                "FINAL MARKETPLACE",
                new BigDecimal("42.42"),
                TransactionType.DEBIT,
                "credit-card-001"));
  }

  @Test
  void extract_withSplitColumnPdf_ignoresForeignCurrencyAndTravelDetailLines() throws IOException {
    var transactions = transactions(splitColumnPdfContent(), null);

    assertThat(transactions)
        .extracting("description")
        .doesNotContain(
            "800.00 THB",
            "Exchange Rate 1 USD = 33.195000 THB",
            "TK#: 1234567890",
            "ORIG: IAD",
            "DEST: LAX",
            "PSGR: SAMPLE TRAVELER",
            "CARRIER: SAMPLE AIR");
  }

  @Test
  void extract_withTransactionTableButNoParsableRows_throwsPdfParsingError() throws IOException {
    var pdfContent =
        pdfWithPages(
            List.of(
                List.of(
                    "Capital One",
                    "Credit Card Statement",
                    "Statement Period: Apr 19, 2026 - May 19, 2026",
                    "31 days in Billing Cycle",
                    "Transactions",
                    "Trans Date",
                    "Post Date",
                    "Description",
                    "Amount",
                    "Unparseable transaction table content")));

    var parserAttempt = attempt(pdfContent, "cap-one-credit-monthly-sample.pdf", null);

    assertThat(parserAttempt.status()).isEqualTo(ParserAttemptStatus.FAILED);
    assertThat(parserAttempt.failure().getCode())
        .isEqualTo(BudgetAnalyzerError.PDF_PARSING_ERROR.name());
  }

  @Test
  void extract_withSamplePdf_setsCorrectBankAndCurrency() {
    var transactions = transactions(null);

    for (var previewTransaction : transactions) {
      assertThat(previewTransaction.bankName()).isEqualTo("Capital One");
      assertThat(previewTransaction.currencyIsoCode()).isEqualTo("USD");
    }
  }

  @Test
  void extract_withAccountId_setsAccountIdOnAllTransactions() {
    var accountId = "test-account-123";
    var transactions = transactions(accountId);

    for (var previewTransaction : transactions) {
      assertThat(previewTransaction.accountId()).isEqualTo(accountId);
    }
  }

  @Test
  void extract_withSamplePdf_parsesDateCorrectly() {
    var transactions = transactions(null);

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
    var transactions = transactions(null);

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
    var transactions = transactions(null);

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
    var transactions = transactions(null);

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
    var transactions = transactions(null);

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
    var transactions = transactions(null);

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
    var transactions = transactions(null);

    var credits = transactions.stream().filter(t -> t.type() == TransactionType.CREDIT).count();
    var debits = transactions.stream().filter(t -> t.type() == TransactionType.DEBIT).count();

    // Should have 3 credits (2 payments + 1 refund) and 10 debits (purchases)
    assertThat(credits).isGreaterThanOrEqualTo(2L);
    assertThat(debits).isGreaterThan(8L);
  }

  private org.budgetanalyzer.transaction.service.dto.ParserAttempt attempt(
      byte[] content, String filename, String accountId) {
    return extractor.attempt(parserRevision(), content, filename, accountId);
  }

  private List<PreviewTransaction> transactions(String accountId) {
    return transactions(pdfContent, accountId);
  }

  private List<PreviewTransaction> transactions(byte[] content, String accountId) {
    var parserAttempt = attempt(content, "cap-one-credit-monthly-sample.pdf", accountId);
    assertThat(parserAttempt.status()).isEqualTo(ParserAttemptStatus.MATCHED);
    return parserAttempt.transactions();
  }

  private ParserRevision parserRevision() {
    var statementFormat =
        StatementFormat.createSystemPdfFormat("Capital One Credit", "Capital One", "USD");
    return ParserRevision.createStaticHandler(
        statementFormat, 1, "capital-one-credit-monthly-statement");
  }

  private byte[] splitColumnPdfContent() throws IOException {
    return pdfWithPages(
        List.of(
            List.of(
                "Capital One",
                "Credit Card Statement",
                "Statement Period: Apr 19, 2026 - May 19, 2026",
                "31 days in Billing Cycle",
                "Payments, Credits and Adjustments",
                "Trans Date",
                "Post Date",
                "Description",
                "Amount",
                "May 2",
                "May 2",
                "CREDIT-CASH BACK REWARD",
                "- $450.68",
                "May 3",
                "May 3",
                "ONLINE PAYMENT THANK YOU",
                "$100.00",
                "Transactions",
                "Trans Date",
                "Post Date",
                "Description",
                "Amount",
                "May 4",
                "May 5",
                "CORNER GROCERY ANYTOWN CA",
                "$84.12",
                "May 6",
                "May 7",
                "AIRLINE TICKET PURCHASE",
                "$1,200.00",
                "TK#: 1234567890",
                "ORIG: IAD",
                "DEST: LAX",
                "PSGR: SAMPLE TRAVELER",
                "CARRIER: SAMPLE AIR",
                "May 8",
                "May 9",
                "FOREIGN MARKETPLACE",
                "$24.10",
                "800.00 THB",
                "Exchange Rate 1 USD = 33.195000 THB",
                "Additional Information on the next page"),
            List.of(
                "Capital One",
                "Credit Card Statement",
                "Transactions (Continued)",
                "Trans Date",
                "Post Date",
                "Description",
                "Amount",
                "May 10",
                "May 11",
                "STREAMING SERVICE",
                "$15.99",
                "May 18",
                "May 18",
                "FINAL MARKETPLACE",
                "Total Transactions",
                "$42.42",
                "Total Fees",
                "$0.00",
                "Total Interest",
                "$0.00")));
  }

  private byte[] pdfWithPages(List<List<String>> pages) throws IOException {
    try (var document = new PDDocument()) {
      var font = new PDType1Font(Standard14Fonts.FontName.HELVETICA);

      for (var pageLines : pages) {
        var page = new PDPage();
        document.addPage(page);

        try (var contentStream = new PDPageContentStream(document, page)) {
          contentStream.beginText();
          contentStream.setFont(font, FONT_SIZE);
          contentStream.setLeading(LEADING);
          contentStream.newLineAtOffset(LEFT_MARGIN, TOP_START);

          for (var line : pageLines) {
            contentStream.showText(line);
            contentStream.newLine();
          }

          contentStream.endText();
        }
      }

      var byteArrayOutputStream = new ByteArrayOutputStream();
      document.save(byteArrayOutputStream);
      return byteArrayOutputStream.toByteArray();
    }
  }
}
