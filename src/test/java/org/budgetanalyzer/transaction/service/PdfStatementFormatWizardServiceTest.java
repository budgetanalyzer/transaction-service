package org.budgetanalyzer.transaction.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.LocalDate;
import java.util.List;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.assertj.core.api.InstanceOfAssertFactories;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

import com.fasterxml.jackson.databind.ObjectMapper;

import org.budgetanalyzer.service.exception.BusinessException;
import org.budgetanalyzer.transaction.domain.FormatType;
import org.budgetanalyzer.transaction.domain.ParserRevision;
import org.budgetanalyzer.transaction.domain.ParserType;
import org.budgetanalyzer.transaction.domain.StatementFormat;
import org.budgetanalyzer.transaction.domain.StatementFormatScope;
import org.budgetanalyzer.transaction.domain.TransactionType;
import org.budgetanalyzer.transaction.repository.ParserRevisionRepository;
import org.budgetanalyzer.transaction.repository.StatementFormatRepository;
import org.budgetanalyzer.transaction.service.dto.PdfTextTableNegativeMeans;
import org.budgetanalyzer.transaction.service.dto.PdfTextTableParserConfig;
import org.budgetanalyzer.transaction.service.dto.PdfTextTableYearSource;
import org.budgetanalyzer.transaction.service.dto.PdfWizardAmountMode;
import org.budgetanalyzer.transaction.service.dto.PdfWizardColumnMapping;
import org.budgetanalyzer.transaction.service.dto.PdfWizardMappingPreviewCommand;
import org.budgetanalyzer.transaction.service.dto.PdfWizardSaveCommand;
import org.budgetanalyzer.transaction.service.extractor.pdf.PdfTextExtractionService;

class PdfStatementFormatWizardServiceTest {

  private static final float FONT_SIZE = 10F;
  private static final float DATE_X = 50F;
  private static final float DESCRIPTION_X = 130F;
  private static final float DEBIT_X = 340F;
  private static final float CREDIT_X = 430F;

  private final StatementFormatRepository statementFormatRepository =
      Mockito.mock(StatementFormatRepository.class);
  private final ParserRevisionRepository parserRevisionRepository =
      Mockito.mock(ParserRevisionRepository.class);
  private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
  private final PdfStatementFormatWizardService pdfStatementFormatWizardService =
      new PdfStatementFormatWizardService(
          new PdfTextExtractionService(),
          statementFormatRepository,
          parserRevisionRepository,
          objectMapper);

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
  void previewParsesConfirmedSignedAmountMapping() throws IOException {
    var result =
        pdfStatementFormatWizardService.preview(
            pdfWithRows(
                List.of(
                    List.of("Date", "Description", "Amount"),
                    List.of("01/02/2025", "Coffee Shop", "$4.50"),
                    List.of("01/03/2025", "Payment", "-$100.00"))),
            "statement.pdf",
            signedAmountPreviewCommand());

    assertThat(result.transactions()).hasSize(2);
    assertThat(result.transactions().getFirst().date()).isEqualTo(LocalDate.of(2025, 1, 2));
    assertThat(result.transactions().getFirst().description()).isEqualTo("Coffee Shop");
    assertThat(result.transactions().getFirst().amount()).isEqualByComparingTo("4.50");
    assertThat(result.transactions().getFirst().type()).isEqualTo(TransactionType.DEBIT);
    assertThat(result.transactions().get(1).type()).isEqualTo(TransactionType.CREDIT);
    assertThat(result.diagnostics()).isNotEmpty();
  }

  @Test
  void saveCreatesUserScopedPdfFormatAndParserRevision() throws Exception {
    when(statementFormatRepository.save(any(StatementFormat.class)))
        .thenAnswer(invocation -> invocation.getArgument(0));

    var result =
        pdfStatementFormatWizardService.save(
            pdfWithRows(
                List.of(
                    List.of("Date", "Description", "Amount"),
                    List.of("01/02/2025", "Coffee Shop", "$4.50"),
                    List.of("01/03/2025", "Payment", "-$100.00"))),
            "statement.pdf",
            signedAmountSaveCommand(),
            "usr_test123");

    var parserRevisionCaptor = ArgumentCaptor.forClass(ParserRevision.class);
    verify(parserRevisionRepository).save(parserRevisionCaptor.capture());
    var parserRevision = parserRevisionCaptor.getValue();
    var parserConfig =
        objectMapper.readValue(parserRevision.getParserConfig(), PdfTextTableParserConfig.class);

    assertThat(result.getDisplayName()).isEqualTo("Example PDF");
    assertThat(result.getFormatType()).isEqualTo(FormatType.PDF);
    assertThat(result.getScope()).isEqualTo(StatementFormatScope.USER);
    assertThat(result.getOwnerId()).isEqualTo("usr_test123");
    assertThat(result.getDefaultCurrencyIsoCode()).isEqualTo("USD");
    assertThat(parserRevision.getParserType()).isEqualTo(ParserType.PDF_TEXT_TABLE_CONFIG);
    assertThat(parserRevision.getHandlerKey()).isNull();
    assertThat(parserConfig.headerMustContain()).containsExactly("Date", "Description", "Amount");
    assertThat(parserConfig.amountHeader()).isEqualTo("Amount");
  }

  @Test
  void saveRejectsAmbiguousSignedAmountDirectionBeforePersisting() throws IOException {
    var command =
        new PdfWizardSaveCommand(
            "Example PDF",
            "Example Bank",
            "USD",
            List.of("Date", "Description", "Amount"),
            1,
            PdfTextTableYearSource.EXPLICIT_DATE,
            new PdfWizardColumnMapping(
                "Date",
                "MM/dd/uuuu",
                "Description",
                PdfWizardAmountMode.SIGNED_AMOUNT,
                "Amount",
                null,
                null,
                null,
                null));

    assertThatThrownBy(
            () ->
                pdfStatementFormatWizardService.save(
                    pdfWithRows(
                        List.of(
                            List.of("Date", "Description", "Amount"),
                            List.of("01/02/2025", "Coffee Shop", "$4.50"))),
                    "statement.pdf",
                    command,
                    "usr_test123"))
        .isInstanceOf(BusinessException.class)
        .satisfies(
            exception ->
                assertThat(((BusinessException) exception).getFieldErrors())
                    .extracting("field")
                    .contains("negativeMeans"));

    verify(statementFormatRepository, never()).save(any());
    verify(parserRevisionRepository, never()).save(any());
  }

  @Test
  void previewRejectsInvalidMappingWithFieldErrors() throws IOException {
    var command =
        new PdfWizardMappingPreviewCommand(
            "Example Bank",
            "USD",
            null,
            List.of("Date", "Description", "Amount"),
            1,
            PdfTextTableYearSource.EXPLICIT_DATE,
            new PdfWizardColumnMapping(
                null,
                "MM/dd/uuuu",
                "Description",
                PdfWizardAmountMode.SIGNED_AMOUNT,
                "Amount",
                null,
                null,
                null,
                PdfTextTableNegativeMeans.CREDIT));

    assertThat(
            org.assertj.core.api.Assertions.catchThrowable(
                () ->
                    pdfStatementFormatWizardService.preview(
                        pdfWithRows(
                            List.of(
                                List.of("Date", "Description", "Amount"),
                                List.of("01/02/2025", "Coffee Shop", "$4.50"))),
                        "statement.pdf",
                        command)))
        .asInstanceOf(InstanceOfAssertFactories.type(BusinessException.class))
        .extracting(BusinessException::getCode)
        .isEqualTo(BudgetAnalyzerError.PDF_WIZARD_VALIDATION_FAILED.name());
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

  private PdfWizardMappingPreviewCommand signedAmountPreviewCommand() {
    return new PdfWizardMappingPreviewCommand(
        "Example Bank",
        "USD",
        "checking",
        List.of("Date", "Description", "Amount"),
        1,
        PdfTextTableYearSource.EXPLICIT_DATE,
        new PdfWizardColumnMapping(
            "Date",
            "MM/dd/uuuu",
            "Description",
            PdfWizardAmountMode.SIGNED_AMOUNT,
            "Amount",
            null,
            null,
            null,
            PdfTextTableNegativeMeans.CREDIT));
  }

  private PdfWizardSaveCommand signedAmountSaveCommand() {
    return new PdfWizardSaveCommand(
        "Example PDF",
        "Example Bank",
        "usd",
        List.of("Date", "Description", "Amount"),
        1,
        PdfTextTableYearSource.EXPLICIT_DATE,
        new PdfWizardColumnMapping(
            "Date",
            "MM/dd/uuuu",
            "Description",
            PdfWizardAmountMode.SIGNED_AMOUNT,
            "Amount",
            null,
            null,
            null,
            PdfTextTableNegativeMeans.CREDIT));
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
