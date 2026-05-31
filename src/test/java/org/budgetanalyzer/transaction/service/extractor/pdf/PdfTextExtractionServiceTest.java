package org.budgetanalyzer.transaction.service.extractor.pdf;

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

import org.budgetanalyzer.service.exception.BusinessException;
import org.budgetanalyzer.transaction.service.BudgetAnalyzerError;

class PdfTextExtractionServiceTest {

  private static final float FONT_SIZE = 10F;
  private static final float DATE_X = 50F;
  private static final float DESCRIPTION_X = 130F;
  private static final float AMOUNT_X = 360F;

  private final PdfTextExtractionService pdfTextExtractionService = new PdfTextExtractionService();

  @Test
  void extract_withTextPdfNormalizesPagesLinesCellsAndTableCandidates() throws IOException {
    var pdfContent =
        pdfWithRows(
            List.of(
                List.of("Date", "Description", "Amount"),
                List.of("Jan 1", "Coffee Shop", "$4.50"),
                List.of("Jan 2", "Payroll", "-$100.00")));

    var pdfTextDocument = pdfTextExtractionService.extract(pdfContent, "statement.pdf");

    assertThat(pdfTextDocument.pages()).hasSize(1);
    assertThat(pdfTextDocument.pages().getFirst().lines()).hasSize(3);
    assertThat(pdfTextDocument.pages().getFirst().lines().getFirst().cells())
        .extracting(PdfTextCell::text)
        .containsExactly("Date", "Description", "Amount");
    assertThat(pdfTextDocument.tableCandidates()).hasSize(1);
    assertThat(pdfTextDocument.tableCandidates().getFirst().headerCells())
        .containsExactly("Date", "Description", "Amount");
    assertThat(pdfTextDocument.tableCandidates().getFirst().sampleRows())
        .containsExactly(
            List.of("Jan 1", "Coffee Shop", "$4.50"), List.of("Jan 2", "Payroll", "-$100.00"));
  }

  @Test
  void extract_withBlankPdfRejectsOcrDependentFile() throws IOException {
    assertThatThrownBy(() -> pdfTextExtractionService.extract(blankPdf(), "statement.pdf"))
        .isInstanceOf(BusinessException.class)
        .hasMessageContaining("Scanned or OCR-dependent PDFs are not supported")
        .extracting("code")
        .isEqualTo(BudgetAnalyzerError.PDF_PARSING_ERROR.name());
  }

  @Test
  void extract_withNonPdfFilenameRejectsFile() {
    assertThatThrownBy(() -> pdfTextExtractionService.extract(new byte[] {}, "statement.csv"))
        .isInstanceOf(BusinessException.class)
        .hasMessage("PDF text extraction requires a .pdf file.");
  }

  private byte[] pdfWithRows(List<List<String>> rows) throws IOException {
    try (var document = new PDDocument()) {
      var page = new PDPage();
      document.addPage(page);
      var font = new PDType1Font(Standard14Fonts.FontName.HELVETICA);
      try (var contentStream = new PDPageContentStream(document, page)) {
        var y = 750F;
        for (var row : rows) {
          writeText(contentStream, font, row.get(0), DATE_X, y);
          writeText(contentStream, font, row.get(1), DESCRIPTION_X, y);
          writeText(contentStream, font, row.get(2), AMOUNT_X, y);
          y -= 16F;
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
