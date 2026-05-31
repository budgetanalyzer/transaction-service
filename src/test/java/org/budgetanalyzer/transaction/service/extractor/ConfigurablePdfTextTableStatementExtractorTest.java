package org.budgetanalyzer.transaction.service.extractor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.List;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import org.budgetanalyzer.service.exception.BusinessException;
import org.budgetanalyzer.transaction.domain.FileImport;
import org.budgetanalyzer.transaction.domain.ParserRevision;
import org.budgetanalyzer.transaction.domain.StatementFormat;
import org.budgetanalyzer.transaction.domain.TransactionType;
import org.budgetanalyzer.transaction.service.dto.PdfTextTableFileType;
import org.budgetanalyzer.transaction.service.dto.PdfTextTableNegativeMeans;
import org.budgetanalyzer.transaction.service.dto.PdfTextTableParserConfig;
import org.budgetanalyzer.transaction.service.dto.PdfTextTableYearSource;
import org.budgetanalyzer.transaction.service.extractor.pdf.PdfTextExtractionService;

class ConfigurablePdfTextTableStatementExtractorTest {

  private static final float FONT_SIZE = 10F;
  private static final float DATE_X = 50F;
  private static final float DESCRIPTION_X = 130F;
  private static final float DEBIT_X = 340F;
  private static final float CREDIT_X = 430F;

  @Test
  void canHandleRequiresPdfFilenameAndMatchingTable() throws IOException {
    var extractor = extractor(signedAmountConfig(PdfTextTableYearSource.EXPLICIT_DATE));
    var pdfContent =
        pdfWithRows(
            List.of(
                List.of("Date", "Description", "Amount"),
                List.of("01/02/2025", "Coffee Shop", "$4.50")));

    assertThat(extractor.canHandle(pdfContent, "statement.pdf")).isTrue();
    assertThat(extractor.canHandle(pdfContent, "statement.csv")).isFalse();
    assertThat(extractor.canHandle(pdfContent, null)).isFalse();
  }

  @Test
  void extractParsesDebitCreditColumnsAndEntities() throws IOException {
    var extractor = extractor(debitCreditConfig());
    var pdfContent =
        pdfWithRows(
            List.of(
                List.of("Date", "Description", "Debit", "Credit"),
                List.of("01/02/2025", "Coffee Shop", "4.50", ""),
                List.of("01/03/2025", "Payroll", "", "100.00")));

    var transactions = extractor.extract(pdfContent, "checking");
    var fileImport =
        FileImport.create("hash", "statement.pdf", 42L, 101L, "checking", 100L, 2, "usr_test123");
    var entities = extractor.extractEntities(pdfContent, "checking", fileImport);

    assertThat(transactions).hasSize(2);
    assertThat(transactions.getFirst().type()).isEqualTo(TransactionType.DEBIT);
    assertThat(transactions.get(1).type()).isEqualTo(TransactionType.CREDIT);
    assertThat(entities).hasSize(2);
    assertThat(entities.getFirst().getDescription()).isEqualTo("Coffee Shop");
    assertThat(extractor.getHandlerKey()).contains("pdf-text-table");
  }

  @Test
  void extractUsesStatementPeriodYearForYearlessDates() throws IOException {
    var extractor = extractor(signedAmountConfig(PdfTextTableYearSource.STATEMENT_PERIOD));
    var pdfContent =
        pdfWithIntroAndRows(
            "Statement Period January 2025",
            List.of(
                List.of("Date", "Description", "Amount"),
                List.of("Jan 2", "Coffee Shop", "$4.50")));

    var transactions = extractor.extract(pdfContent, "checking");

    assertThat(transactions.getFirst().date()).hasYear(2025);
  }

  @Test
  void extractUsesTypeHeaderWhenPresent() throws IOException {
    var extractor = extractor(signedAmountWithTypeConfig());
    var pdfContent =
        pdfWithRows(
            List.of(
                List.of("Date", "Description", "Amount", "Type"),
                List.of("01/02/2025", "Coffee Shop", "4.50", "Debit"),
                List.of("01/03/2025", "Refund", "2.00", "Credit")));

    var transactions = extractor.extract(pdfContent, "checking");

    assertThat(transactions.getFirst().type()).isEqualTo(TransactionType.DEBIT);
    assertThat(transactions.get(1).type()).isEqualTo(TransactionType.CREDIT);
  }

  @Test
  void extractRejectsAmbiguousDebitCreditRows() throws IOException {
    var extractor = extractor(debitCreditConfig());
    var pdfContent =
        pdfWithRows(
            List.of(
                List.of("Date", "Description", "Debit", "Credit"),
                List.of("01/02/2025", "Coffee Shop", "4.50", "1.00")));

    assertThatThrownBy(() -> extractor.extract(pdfContent, "checking"))
        .isInstanceOf(BusinessException.class)
        .hasMessageContaining("exactly one debit or credit amount");
  }

  private ConfigurablePdfTextTableStatementExtractor extractor(
      PdfTextTableParserConfig pdfTextTableParserConfig) {
    var statementFormat =
        StatementFormat.createSystemPdfFormat("Example PDF", "Example Bank", "USD");
    ReflectionTestUtils.setField(statementFormat, "id", 42L);
    var parserRevision = ParserRevision.createPdfTextTableConfig(statementFormat, 1, "{}");
    ReflectionTestUtils.setField(parserRevision, "id", 101L);
    return new ConfigurablePdfTextTableStatementExtractor(
        statementFormat, parserRevision, pdfTextTableParserConfig, new PdfTextExtractionService());
  }

  private PdfTextTableParserConfig signedAmountConfig(PdfTextTableYearSource yearSource) {
    var dateFormat = yearSource == PdfTextTableYearSource.STATEMENT_PERIOD ? "MMM d" : "MM/dd/uuuu";
    return new PdfTextTableParserConfig(
        PdfTextTableFileType.TEXT_PDF,
        List.of("Date", "Description", "Amount"),
        1,
        "Date",
        dateFormat,
        "Description",
        "Amount",
        null,
        null,
        null,
        PdfTextTableNegativeMeans.CREDIT,
        yearSource);
  }

  private PdfTextTableParserConfig signedAmountWithTypeConfig() {
    return new PdfTextTableParserConfig(
        PdfTextTableFileType.TEXT_PDF,
        List.of("Date", "Description", "Amount"),
        1,
        "Date",
        "MM/dd/uuuu",
        "Description",
        "Amount",
        null,
        null,
        "Type",
        PdfTextTableNegativeMeans.CREDIT,
        PdfTextTableYearSource.EXPLICIT_DATE);
  }

  private PdfTextTableParserConfig debitCreditConfig() {
    return new PdfTextTableParserConfig(
        PdfTextTableFileType.TEXT_PDF,
        List.of("Date", "Description"),
        1,
        "Date",
        "MM/dd/uuuu",
        "Description",
        null,
        "Debit",
        "Credit",
        null,
        null,
        PdfTextTableYearSource.EXPLICIT_DATE);
  }

  private byte[] pdfWithRows(List<List<String>> rows) throws IOException {
    return pdfWithIntroAndRows(null, rows);
  }

  private byte[] pdfWithIntroAndRows(String intro, List<List<String>> rows) throws IOException {
    try (var document = new PDDocument()) {
      var page = new PDPage();
      document.addPage(page);
      var font = new PDType1Font(Standard14Fonts.FontName.HELVETICA);
      try (var contentStream = new PDPageContentStream(document, page)) {
        var y = 750F;
        if (intro != null) {
          writeText(contentStream, font, intro, DATE_X, y);
          y -= 40F;
        }
        for (var row : rows) {
          writeRow(contentStream, font, row, y);
          y -= 16F;
        }
      }
      var byteArrayOutputStream = new ByteArrayOutputStream();
      document.save(byteArrayOutputStream);
      return byteArrayOutputStream.toByteArray();
    }
  }

  private void writeRow(
      PDPageContentStream contentStream, PDType1Font font, List<String> row, float y)
      throws IOException {
    var positions = List.of(DATE_X, DESCRIPTION_X, DEBIT_X, CREDIT_X);
    for (var index = 0; index < row.size(); index++) {
      writeText(contentStream, font, row.get(index), positions.get(index), y);
    }
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
}
