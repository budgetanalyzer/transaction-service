package org.budgetanalyzer.transaction.service.extractor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import com.fasterxml.jackson.databind.ObjectMapper;

import org.budgetanalyzer.core.csv.CsvParser;
import org.budgetanalyzer.transaction.domain.FileImport;
import org.budgetanalyzer.transaction.domain.ParserRevision;
import org.budgetanalyzer.transaction.domain.ParserType;
import org.budgetanalyzer.transaction.domain.StatementFormat;
import org.budgetanalyzer.transaction.domain.Transaction;
import org.budgetanalyzer.transaction.domain.TransactionType;
import org.budgetanalyzer.transaction.repository.ParserRevisionRepository;
import org.budgetanalyzer.transaction.service.dto.ParserAttemptStatus;
import org.budgetanalyzer.transaction.service.dto.PdfTextTableFileType;
import org.budgetanalyzer.transaction.service.dto.PdfTextTableNegativeMeans;
import org.budgetanalyzer.transaction.service.dto.PdfTextTableParserConfig;
import org.budgetanalyzer.transaction.service.dto.PdfTextTableYearSource;
import org.budgetanalyzer.transaction.service.dto.PreviewTransaction;
import org.budgetanalyzer.transaction.service.extractor.pdf.PdfTextExtractionService;

@ExtendWith(MockitoExtension.class)
class StatementExtractorRegistryTest {

  private static final float FONT_SIZE = 10F;
  private static final float DATE_X = 50F;
  private static final float DESCRIPTION_X = 130F;
  private static final float AMOUNT_X = 360F;

  @Mock private ParserRevisionRepository parserRevisionRepository;
  @Mock private CsvParser csvParser;
  @Mock private StatementExtractor staticPdfExtractor;

  private StatementExtractorRegistry registry;

  @BeforeEach
  void setUp() {
    when(staticPdfExtractor.getHandlerKey()).thenReturn("capital-one-yearly");
    when(parserRevisionRepository.findByParserTypeAndEnabledTrue(ParserType.CSV_COLUMN_CONFIG))
        .thenReturn(List.of());

    registry =
        new StatementExtractorRegistry(
            List.of(staticPdfExtractor),
            parserRevisionRepository,
            csvParser,
            new ObjectMapper().findAndRegisterModules(),
            new PdfTextExtractionService());
    registry.initialize();
  }

  @Nested
  class AttemptParse {

    @Test
    void triesEveryActiveRevisionAndMatchesLaterApplicableRevision() {
      var statementFormat =
          StatementFormat.createSystemPdfFormat("Test Bank PDF", "Test Bank", "USD");
      ReflectionTestUtils.setField(statementFormat, "id", 42L);
      var firstParserRevision =
          ParserRevision.createStaticHandler(statementFormat, 1, "first-handler");
      var secondParserRevision =
          ParserRevision.createStaticHandler(statementFormat, 2, "second-handler");
      var matchedTransaction = previewTransaction("Coffee Shop");
      var firstStatementExtractor = new TestStatementExtractor("first-handler", false, List.of());
      var secondStatementExtractor =
          new TestStatementExtractor("second-handler", true, List.of(matchedTransaction));
      var parserRegistry =
          new StatementExtractorRegistry(
              List.of(firstStatementExtractor, secondStatementExtractor),
              parserRevisionRepository,
              csvParser,
              new ObjectMapper().findAndRegisterModules(),
              new PdfTextExtractionService());

      when(parserRevisionRepository
              .findByStatementFormatIdAndEnabledTrueOrderByPriorityDescRevisionNumberDesc(42L))
          .thenReturn(List.of(firstParserRevision, secondParserRevision));
      parserRegistry.initialize();

      var parserAttempts =
          parserRegistry.attemptParse(
              statementFormat, "pdf".getBytes(), "statement.pdf", "account-123");

      assertThat(parserAttempts)
          .extracting("status")
          .containsExactly(ParserAttemptStatus.NOT_APPLICABLE, ParserAttemptStatus.MATCHED);
      assertThat(parserAttempts.get(1).transactions()).containsExactly(matchedTransaction);
    }

    @Test
    void returnsNotApplicableWhenStaticHandlerKeyIsUnknown() {
      var statementFormat =
          StatementFormat.createSystemPdfFormat("Test Bank PDF", "Test Bank", "USD");
      ReflectionTestUtils.setField(statementFormat, "id", 42L);
      var parserRevision = ParserRevision.createStaticHandler(statementFormat, 1, "missing");
      when(parserRevisionRepository
              .findByStatementFormatIdAndEnabledTrueOrderByPriorityDescRevisionNumberDesc(42L))
          .thenReturn(List.of(parserRevision));

      var parserAttempts =
          registry.attemptParse(statementFormat, "pdf".getBytes(), "statement.pdf", "account-123");

      assertThat(parserAttempts).hasSize(1);
      assertThat(parserAttempts.getFirst().status()).isEqualTo(ParserAttemptStatus.NOT_APPLICABLE);
    }

    @Test
    void matchesPdfTextTableConfigWithoutUsingStaticHandlerKey() throws Exception {
      var statementFormat =
          StatementFormat.createSystemPdfFormat("Test Bank PDF", "Test Bank", "USD");
      ReflectionTestUtils.setField(statementFormat, "id", 42L);
      var parserConfig =
          new ObjectMapper()
              .writeValueAsString(
                  new PdfTextTableParserConfig(
                      PdfTextTableFileType.TEXT_PDF,
                      List.of("Date", "Description", "Amount"),
                      1,
                      "Date",
                      "MM/dd/uuuu",
                      "Description",
                      "Amount",
                      null,
                      null,
                      null,
                      PdfTextTableNegativeMeans.CREDIT,
                      PdfTextTableYearSource.STATEMENT_PERIOD));
      var parserRevision =
          ParserRevision.createPdfTextTableConfig(statementFormat, 1, parserConfig);
      ReflectionTestUtils.setField(parserRevision, "id", 101L);
      when(parserRevisionRepository
              .findByStatementFormatIdAndEnabledTrueOrderByPriorityDescRevisionNumberDesc(42L))
          .thenReturn(List.of(parserRevision));

      var parserAttempts =
          registry.attemptParse(
              statementFormat,
              pdfWithRows(
                  List.of(
                      List.of("Date", "Description", "Amount"),
                      List.of("01/02/2025", "Coffee Shop", "$4.50"))),
              "statement.pdf",
              "account-123");

      assertThat(parserRevision.getParserType()).isEqualTo(ParserType.PDF_TEXT_TABLE_CONFIG);
      assertThat(parserRevision.getHandlerKey()).isNull();
      assertThat(parserAttempts).hasSize(1);
      assertThat(parserAttempts.getFirst().status()).isEqualTo(ParserAttemptStatus.MATCHED);
      assertThat(parserAttempts.getFirst().transactions().getFirst().description())
          .isEqualTo("Coffee Shop");
    }
  }

  @Nested
  class GetAllExtractors {

    @Test
    void includesStaticExtractors() {
      var allStatementExtractors = registry.getAllExtractors();

      assertThat(allStatementExtractors).containsExactly(staticPdfExtractor);
    }

    @Test
    void includesCsvExtractorsCreatedFromParserRevisions() {
      var statementFormat =
          StatementFormat.createSystemCsvFormat("Test Bank CSV", "Test Bank", "USD");
      ReflectionTestUtils.setField(statementFormat, "id", 42L);
      var parserRevision =
          ParserRevision.createCsvColumnConfig(
              statementFormat,
              1,
              """
              {
                "dateHeader": "Date",
                "dateFormat": "MM/dd/uu",
                "descriptionHeader": "Description",
                "creditHeader": "Amount",
                "debitHeader": "Amount",
                "typeHeader": "Type",
                "categoryHeader": null
              }
              """);
      ReflectionTestUtils.setField(parserRevision, "id", 101L);
      when(parserRevisionRepository.findByParserTypeAndEnabledTrue(ParserType.CSV_COLUMN_CONFIG))
          .thenReturn(List.of(parserRevision));

      registry.refreshCsvExtractors();

      assertThat(registry.getAllExtractors()).hasSize(2);
      assertThat(registry.getAllExtractors().stream().map(StatementExtractor::getHandlerKey))
          .contains("capital-one-yearly", "statement-format-42-revision-101");
    }
  }

  private PreviewTransaction previewTransaction(String description) {
    return new PreviewTransaction(
        LocalDate.of(2024, 1, 15),
        description,
        new BigDecimal("4.50"),
        TransactionType.DEBIT,
        null,
        "Test Bank",
        "USD",
        "checking");
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

  private void writeText(
      PDPageContentStream contentStream, PDType1Font font, String text, float x, float y)
      throws IOException {
    contentStream.beginText();
    contentStream.setFont(font, FONT_SIZE);
    contentStream.newLineAtOffset(x, y);
    contentStream.showText(text);
    contentStream.endText();
  }

  private static class TestStatementExtractor implements StatementExtractor {

    private final String handlerKey;
    private final boolean canHandle;
    private final List<PreviewTransaction> transactions;

    TestStatementExtractor(
        String handlerKey, boolean canHandle, List<PreviewTransaction> transactions) {
      this.handlerKey = handlerKey;
      this.canHandle = canHandle;
      this.transactions = transactions;
    }

    @Override
    public boolean canHandle(byte[] fileContent, String filename) {
      return canHandle;
    }

    @Override
    public List<PreviewTransaction> extract(byte[] fileContent, String accountId) {
      return transactions;
    }

    @Override
    public List<Transaction> extractEntities(
        byte[] fileContent, String accountId, FileImport fileImport) {
      return List.of();
    }

    @Override
    public String getHandlerKey() {
      return handlerKey;
    }
  }
}
