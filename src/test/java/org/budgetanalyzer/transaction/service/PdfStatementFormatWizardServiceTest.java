package org.budgetanalyzer.transaction.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.List;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.junit.jupiter.api.Test;

import org.budgetanalyzer.transaction.service.dto.PdfTextTableNegativeMeans;
import org.budgetanalyzer.transaction.service.dto.PdfWizardAmountMode;
import org.budgetanalyzer.transaction.service.extractor.pdf.PdfTextExtractionService;

class PdfStatementFormatWizardServiceTest {

  private static final float FONT_SIZE = 10F;
  private static final float DATE_X = 50F;
  private static final float DESCRIPTION_X = 130F;
  private static final float DEBIT_X = 340F;
  private static final float CREDIT_X = 430F;

  private final PdfStatementFormatWizardService pdfStatementFormatWizardService =
      new PdfStatementFormatWizardService(new PdfTextExtractionService());

  @Test
  void analyzeRanksTransactionTableAndInfersSignedAmountMapping() throws IOException {
    var result =
        pdfStatementFormatWizardService.analyze(
            pdfWithRows(
                List.of(
                    List.of("Statement", "Balance", "Other"),
                    List.of("Total", "$100.00", "Summary")),
                List.of(
                    List.of("Date", "Description", "Amount"),
                    List.of("Jan 1", "Coffee Shop", "$4.50"),
                    List.of("Jan 2", "Payment", "-$100.00"),
                    List.of("Jan 3", "Grocery", "$25.20"))),
            "statement.pdf");

    assertThat(result.rejectionReasons()).isEmpty();
    assertThat(result.candidates()).hasSize(2);
    var topCandidate = result.candidates().getFirst();
    assertThat(topCandidate.headers()).containsExactly("Date", "Description", "Amount");
    assertThat(topCandidate.inferredMapping().dateHeader()).isEqualTo("Date");
    assertThat(topCandidate.inferredMapping().dateFormat()).isEqualTo("MMM d");
    assertThat(topCandidate.inferredMapping().descriptionHeader()).isEqualTo("Description");
    assertThat(topCandidate.inferredMapping().amountMode())
        .isEqualTo(PdfWizardAmountMode.SIGNED_AMOUNT);
    assertThat(topCandidate.inferredMapping().amountHeader()).isEqualTo("Amount");
    assertThat(topCandidate.inferredMapping().negativeMeans())
        .isEqualTo(PdfTextTableNegativeMeans.CREDIT);
    assertThat(topCandidate.confidence()).isGreaterThan(0.8);
  }

  @Test
  void analyzeInfersDebitCreditColumnPair() throws IOException {
    var result =
        pdfStatementFormatWizardService.analyze(
            pdfWithRows(
                List.of(
                    List.of("Date", "Particulars", "Withdrawal", "Deposit"),
                    List.of("15/11/24", "Coffee Shop", "150.00", ""),
                    List.of("14/11/24", "Transfer", "", "5000.00"))),
            "statement.pdf");

    var topCandidate = result.candidates().getFirst();
    assertThat(topCandidate.inferredMapping().amountMode())
        .isEqualTo(PdfWizardAmountMode.DEBIT_CREDIT_COLUMNS);
    assertThat(topCandidate.inferredMapping().debitHeader()).isEqualTo("Withdrawal");
    assertThat(topCandidate.inferredMapping().creditHeader()).isEqualTo("Deposit");
    assertThat(topCandidate.inferredMapping().dateFormat()).isEqualTo("dd/MM/uu");
    assertThat(topCandidate.rejectionReasons()).isEmpty();
  }

  @Test
  void analyzeReturnsRejectionReasonsForBlankPdf() throws IOException {
    var result = pdfStatementFormatWizardService.analyze(blankPdf(), "statement.pdf");

    assertThat(result.candidates()).isEmpty();
    assertThat(result.confidence()).isZero();
    assertThat(result.rejectionReasons())
        .contains(
            "PDF does not contain enough extractable text. Scanned or OCR-dependent PDFs are not "
                + "supported.");
  }

  @Test
  void analyzeReportsLowConfidenceWhenNoTransactionColumnsAreFound() throws IOException {
    var result =
        pdfStatementFormatWizardService.analyze(
            pdfWithRows(
                List.of(
                    List.of("Alpha", "Beta", "Gamma"),
                    List.of("one", "two", "three"),
                    List.of("four", "five", "six"))),
            "statement.pdf");

    assertThat(result.confidence()).isLessThan(0.55);
    assertThat(result.rejectionReasons())
        .contains("No confident transaction table was found in the PDF sample.");
    assertThat(result.candidates().getFirst().rejectionReasons())
        .contains("No confident date column was detected.");
  }

  @SafeVarargs
  private byte[] pdfWithRows(List<List<String>>... tables) throws IOException {
    try (var document = new PDDocument()) {
      var page = new PDPage();
      document.addPage(page);
      var font = new PDType1Font(Standard14Fonts.FontName.HELVETICA);
      try (var contentStream = new PDPageContentStream(document, page)) {
        var y = 750F;
        for (var table : tables) {
          for (var row : table) {
            writeRow(contentStream, font, row, y);
            y -= 16F;
          }
          y -= 24F;
        }
      }
      var byteArrayOutputStream = new ByteArrayOutputStream();
      document.save(byteArrayOutputStream);
      return byteArrayOutputStream.toByteArray();
    }
  }

  private byte[] blankPdf() throws IOException {
    try (var document = new PDDocument()) {
      document.addPage(new PDPage());
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
