package org.budgetanalyzer.transaction.service.extractor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import org.budgetanalyzer.service.exception.BusinessException;
import org.budgetanalyzer.transaction.domain.FileImport;
import org.budgetanalyzer.transaction.domain.TransactionType;
import org.budgetanalyzer.transaction.service.BudgetAnalyzerError;

class BangkokBankStatementPdfExtractorTest {

  private static final float DATE_X = 50f;
  private static final float PARTICULARS_X = 120f;
  private static final float WITHDRAWAL_X = 330f;
  private static final float DEPOSIT_X = 430f;
  private static final float BALANCE_X = 520f;
  private static final float FONT_SIZE = 10f;
  private static final float LEADING = 14f;
  private static final Path SAMPLE_PDF_PATH =
      Path.of("src/test/resources/fixtures/bkk-bank-statement-pdf-sample.pdf");

  private BangkokBankStatementPdfExtractor extractor;

  @BeforeEach
  void setUp() {
    extractor = new BangkokBankStatementPdfExtractor();
  }

  @Test
  void canHandle_withBangkokBankStatementPdf_returnsTrue() throws IOException {
    var pdfContent =
        pdfWithLines(
            "Bangkok Bank",
            "Statement of Account",
            "Account No. 123-4-56789-0",
            "Date Particulars Withdrawal Deposit",
            "01/01/26 Coffee Shop 150.00");

    assertThat(extractor.canHandle(pdfContent, "bkk-bank-statement.pdf")).isTrue();
  }

  @Test
  void canHandle_withBangkokBankStatementPdfFixture_returnsTrue() throws IOException {
    var pdfContent = Files.readAllBytes(SAMPLE_PDF_PATH);

    assertThat(extractor.canHandle(pdfContent, "bkk-bank-statement-pdf-sample.pdf")).isTrue();
  }

  @Test
  void canHandle_withCsvFile_returnsFalse() throws IOException {
    var pdfContent =
        pdfWithLines("Bangkok Bank", "Statement of Account", "Date Particulars Withdrawal Deposit");

    assertThat(extractor.canHandle(pdfContent, "bkk-bank-statement.csv")).isFalse();
  }

  @Test
  void canHandle_withNullFilename_returnsFalse() throws IOException {
    var pdfContent =
        pdfWithLines("Bangkok Bank", "Statement of Account", "Date Particulars Withdrawal Deposit");

    assertThat(extractor.canHandle(pdfContent, null)).isFalse();
  }

  @Test
  void canHandle_withBangkokBankNonStatementPdf_returnsFalse() throws IOException {
    var pdfContent =
        pdfWithLines("Bangkok Bank", "Product Terms", "Date Particulars Withdrawal Deposit");

    assertThat(extractor.canHandle(pdfContent, "bangkok-bank-terms.pdf")).isFalse();
  }

  @Test
  void canHandle_withBangkokBankStatementMissingTable_returnsFalse() throws IOException {
    var pdfContent =
        pdfWithLines("Bangkok Bank", "Statement of Account", "Opening Balance 1,000.00");

    assertThat(extractor.canHandle(pdfContent, "bkk-bank-statement.pdf")).isFalse();
  }

  @Test
  void getFormatKey_returnsCorrectKey() {
    assertThat(extractor.getFormatKey()).isEqualTo("bkk-bank-statement-pdf");
  }

  @Test
  void extract_withMultiPageStatement_extractsTransactionsFromRepeatedTables() throws IOException {
    var pdfContent =
        bangkokBankPdfWithPages(
            List.of(
                List.of(
                    text("Opening Balance 1,000.00"),
                    withdrawal("01/01/26", "COFFEE SHOP", "150.00"),
                    text("Summary line that should be ignored"),
                    deposit("02/01/26", "SALARY TRANSFER", "5,000.25")),
                List.of(
                    text("Page 2 of 2"), withdrawal("03/01/26", "ATM WITHDRAWAL", "1,200.00"))));

    var transactions = extractor.extract(pdfContent, "checking-001");

    assertThat(transactions).hasSize(3);

    var coffeeTransaction = transactions.get(0);
    assertThat(coffeeTransaction.date()).isEqualTo(LocalDate.of(2026, 1, 1));
    assertThat(coffeeTransaction.description()).isEqualTo("COFFEE SHOP");
    assertThat(coffeeTransaction.amount()).isEqualByComparingTo(new BigDecimal("150.00"));
    assertThat(coffeeTransaction.type()).isEqualTo(TransactionType.DEBIT);
    assertThat(coffeeTransaction.bankName()).isEqualTo("Bangkok Bank");
    assertThat(coffeeTransaction.currencyIsoCode()).isEqualTo("THB");
    assertThat(coffeeTransaction.accountId()).isEqualTo("checking-001");

    var salaryTransaction = transactions.get(1);
    assertThat(salaryTransaction.date()).isEqualTo(LocalDate.of(2026, 1, 2));
    assertThat(salaryTransaction.description()).isEqualTo("SALARY TRANSFER");
    assertThat(salaryTransaction.amount()).isEqualByComparingTo(new BigDecimal("5000.25"));
    assertThat(salaryTransaction.type()).isEqualTo(TransactionType.CREDIT);

    var atmTransaction = transactions.get(2);
    assertThat(atmTransaction.date()).isEqualTo(LocalDate.of(2026, 1, 3));
    assertThat(atmTransaction.description()).isEqualTo("ATM WITHDRAWAL");
    assertThat(atmTransaction.amount()).isEqualByComparingTo(new BigDecimal("1200.00"));
    assertThat(atmTransaction.type()).isEqualTo(TransactionType.DEBIT);
  }

  @Test
  void extract_withTrailingBalanceColumn_ignoresBalanceAmounts() throws IOException {
    var pdfContent =
        bangkokBankPdfWithBalanceColumn(
            List.of(
                List.of(
                    withdrawalWithBalance("01/01/26", "COFFEE SHOP", "150.00", "850.00"),
                    depositWithBalance("02/01/26", "SALARY TRANSFER", "5,000.25", "5,850.25"))));

    var transactions = extractor.extract(pdfContent, "checking-001");

    assertThat(transactions)
        .extracting("date", "description", "amount", "type")
        .containsExactly(
            tuple(
                LocalDate.of(2026, 1, 1),
                "COFFEE SHOP",
                new BigDecimal("150.00"),
                TransactionType.DEBIT),
            tuple(
                LocalDate.of(2026, 1, 2),
                "SALARY TRANSFER",
                new BigDecimal("5000.25"),
                TransactionType.CREDIT));
  }

  @Test
  void extract_withBangkokBankStatementPdfFixture_extractsExpectedTransactions()
      throws IOException {
    var pdfContent = Files.readAllBytes(SAMPLE_PDF_PATH);

    var transactions = extractor.extract(pdfContent, "checking-001");

    assertThat(transactions)
        .extracting("date", "description", "amount", "type", "bankName", "currencyIsoCode")
        .containsExactly(
            tuple(
                LocalDate.of(2026, 1, 1),
                "COFFEE SHOP",
                new BigDecimal("150.00"),
                TransactionType.DEBIT,
                "Bangkok Bank",
                "THB"),
            tuple(
                LocalDate.of(2026, 1, 2),
                "SALARY TRANSFER",
                new BigDecimal("5000.25"),
                TransactionType.CREDIT,
                "Bangkok Bank",
                "THB"),
            tuple(
                LocalDate.of(2026, 1, 3),
                "ATM WITHDRAWAL",
                new BigDecimal("1200.00"),
                TransactionType.DEBIT,
                "Bangkok Bank",
                "THB"));
    assertThat(transactions).extracting("accountId").containsOnly("checking-001");
  }

  @Test
  void extract_ignoresTransactionShapedLinesBeforeFirstTableHeader() throws IOException {
    var pdfContent =
        bangkokBankPdfWithPages(
            List.of(
                List.of(
                    preTableText("01/01/26 This line is before the table 999.00"),
                    withdrawal("02/01/26", "VALID TABLE ROW", "150.00"))));

    var transactions = extractor.extract(pdfContent, "checking-001");

    assertThat(transactions)
        .singleElement()
        .satisfies(t -> assertThat(t.date()).isEqualTo(LocalDate.of(2026, 1, 2)));
  }

  @Test
  void extract_withBothAmountColumnsPopulated_throwsPdfParsingError() throws IOException {
    var pdfContent =
        bangkokBankPdfWithPages(
            List.of(List.of(bothAmounts("01/01/26", "AMBIGUOUS TRANSFER", "150.00", "25.00"))));

    assertThatThrownBy(() -> extractor.extract(pdfContent, "checking-001"))
        .isInstanceOf(BusinessException.class)
        .extracting("code")
        .isEqualTo(BudgetAnalyzerError.PDF_PARSING_ERROR.name());
  }

  @Test
  void extract_withTransactionRowMissingAmount_throwsPdfParsingError() throws IOException {
    var pdfContent =
        bangkokBankPdfWithPages(List.of(List.of(noAmount("01/01/26", "MISSING AMOUNT"))));

    assertThatThrownBy(() -> extractor.extract(pdfContent, "checking-001"))
        .isInstanceOf(BusinessException.class)
        .extracting("code")
        .isEqualTo(BudgetAnalyzerError.PDF_PARSING_ERROR.name());
  }

  @Test
  void extract_withMalformedTransactionDate_throwsPdfParsingError() throws IOException {
    var pdfContent =
        bangkokBankPdfWithPages(List.of(List.of(withdrawal("32/01/26", "BAD DATE", "150.00"))));

    assertThatThrownBy(() -> extractor.extract(pdfContent, "checking-001"))
        .isInstanceOf(BusinessException.class)
        .extracting("code")
        .isEqualTo(BudgetAnalyzerError.PDF_PARSING_ERROR.name());
  }

  @Test
  void extractEntities_mapsPreviewTransactionsAndLinksFileImport() throws IOException {
    var pdfContent =
        bangkokBankPdfWithPages(
            List.of(List.of(deposit("02/01/26", "SALARY TRANSFER", "5,000.25"))));
    var fileImport =
        FileImport.create(
            "abc123",
            "bkk-bank-statement.pdf",
            "bkk-bank-statement-pdf",
            "checking-001",
            100L,
            1,
            "user-001");

    var transactions = extractor.extractEntities(pdfContent, "checking-001", fileImport);

    assertThat(transactions)
        .singleElement()
        .satisfies(
            transaction -> {
              assertThat(transaction.getDate()).isEqualTo(LocalDate.of(2026, 1, 2));
              assertThat(transaction.getDescription()).isEqualTo("SALARY TRANSFER");
              assertThat(transaction.getAmount()).isEqualByComparingTo(new BigDecimal("5000.25"));
              assertThat(transaction.getType()).isEqualTo(TransactionType.CREDIT);
              assertThat(transaction.getBankName()).isEqualTo("Bangkok Bank");
              assertThat(transaction.getCurrencyIsoCode()).isEqualTo("THB");
              assertThat(transaction.getAccountId()).isEqualTo("checking-001");
              assertThat(transaction.getFileImport()).isSameAs(fileImport);
            });
  }

  private byte[] pdfWithLines(String... lines) throws IOException {
    try (PDDocument document = new PDDocument()) {
      var page = new PDPage();
      document.addPage(page);
      var font = new PDType1Font(Standard14Fonts.FontName.HELVETICA);

      try (var contentStream = new PDPageContentStream(document, page)) {
        contentStream.beginText();
        contentStream.setFont(font, 10f);
        contentStream.setLeading(14f);
        contentStream.newLineAtOffset(50f, 750f);

        for (var line : lines) {
          contentStream.showText(line);
          contentStream.newLine();
        }

        contentStream.endText();
      }

      var byteArrayOutputStream = new ByteArrayOutputStream();
      document.save(byteArrayOutputStream);
      return byteArrayOutputStream.toByteArray();
    }
  }

  private byte[] bangkokBankPdfWithPages(List<List<TableLine>> pages) throws IOException {
    return bangkokBankPdfWithPages(pages, false);
  }

  private byte[] bangkokBankPdfWithPages(List<List<TableLine>> pages, boolean includeBalanceColumn)
      throws IOException {
    try (PDDocument document = new PDDocument()) {
      var font = new PDType1Font(Standard14Fonts.FontName.HELVETICA);

      for (var pageLines : pages) {
        var page = new PDPage();
        document.addPage(page);

        try (var contentStream = new PDPageContentStream(document, page)) {
          var y = 750f;
          writeText(contentStream, font, "Bangkok Bank", DATE_X, y);
          y -= LEADING;
          writeText(contentStream, font, "Statement of Account", DATE_X, y);
          y -= LEADING * 2;

          for (var tableLine : pageLines) {
            if (tableLine.beforeTable()) {
              writeText(contentStream, font, tableLine.text(), DATE_X, y);
              y -= LEADING;
            }
          }

          writeText(contentStream, font, "Date", DATE_X, y);
          writeText(contentStream, font, "Particulars", PARTICULARS_X, y);
          writeText(contentStream, font, "Withdrawal", WITHDRAWAL_X, y);
          writeText(contentStream, font, "Deposit", DEPOSIT_X, y);
          if (includeBalanceColumn) {
            writeText(contentStream, font, "Balance", BALANCE_X, y);
          }
          y -= LEADING;

          for (var tableLine : pageLines) {
            if (tableLine.beforeTable()) {
              continue;
            }
            if (tableLine.rawText()) {
              writeText(contentStream, font, tableLine.text(), DATE_X, y);
            } else {
              writeText(contentStream, font, tableLine.date(), DATE_X, y);
              writeText(contentStream, font, tableLine.description(), PARTICULARS_X, y);
              if (tableLine.withdrawal() != null) {
                writeText(contentStream, font, tableLine.withdrawal(), WITHDRAWAL_X, y);
              }
              if (tableLine.deposit() != null) {
                writeText(contentStream, font, tableLine.deposit(), DEPOSIT_X, y);
              }
              if (includeBalanceColumn && tableLine.balance() != null) {
                writeText(contentStream, font, tableLine.balance(), BALANCE_X, y);
              }
            }
            y -= LEADING;
          }
        }
      }

      var byteArrayOutputStream = new ByteArrayOutputStream();
      document.save(byteArrayOutputStream);
      return byteArrayOutputStream.toByteArray();
    }
  }

  private byte[] bangkokBankPdfWithBalanceColumn(List<List<TableLine>> pages) throws IOException {
    return bangkokBankPdfWithPages(pages, true);
  }

  private void writeText(
      PDPageContentStream contentStream, PDType1Font font, String text, float x, float y)
      throws IOException {
    contentStream.beginText();
    contentStream.setFont(font, FONT_SIZE);
    contentStream.newLineAtOffset(x, y);
    contentStream.showText(text);
    contentStream.endText();
  }

  private TableLine preTableText(String text) {
    return new TableLine(null, null, null, null, null, text, true, true);
  }

  private TableLine text(String text) {
    return new TableLine(null, null, null, null, null, text, true, false);
  }

  private TableLine withdrawal(String date, String description, String amount) {
    return new TableLine(date, description, amount, null, null, null, false, false);
  }

  private TableLine deposit(String date, String description, String amount) {
    return new TableLine(date, description, null, amount, null, null, false, false);
  }

  private TableLine withdrawalWithBalance(
      String date, String description, String amount, String balance) {
    return new TableLine(date, description, amount, null, balance, null, false, false);
  }

  private TableLine depositWithBalance(
      String date, String description, String amount, String balance) {
    return new TableLine(date, description, null, amount, balance, null, false, false);
  }

  private TableLine bothAmounts(
      String date, String description, String withdrawal, String deposit) {
    return new TableLine(date, description, withdrawal, deposit, null, null, false, false);
  }

  private TableLine noAmount(String date, String description) {
    return new TableLine(date, description, null, null, null, null, false, false);
  }

  private record TableLine(
      String date,
      String description,
      String withdrawal,
      String deposit,
      String balance,
      String text,
      boolean rawText,
      boolean beforeTable) {}
}
