package org.budgetanalyzer.transaction.service.extractor;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.fasterxml.jackson.databind.ObjectMapper;

import org.budgetanalyzer.service.security.test.TestClaimsSecurityConfig;
import org.budgetanalyzer.transaction.domain.ParserRevision;
import org.budgetanalyzer.transaction.domain.ParserType;
import org.budgetanalyzer.transaction.domain.StatementFormat;
import org.budgetanalyzer.transaction.domain.TransactionType;
import org.budgetanalyzer.transaction.repository.ParserRevisionRepository;
import org.budgetanalyzer.transaction.repository.StatementFormatRepository;
import org.budgetanalyzer.transaction.service.BudgetAnalyzerError;
import org.budgetanalyzer.transaction.service.dto.CsvColumnParserConfig;
import org.budgetanalyzer.transaction.service.dto.ParserAttemptStatus;
import org.budgetanalyzer.transaction.service.dto.PdfTextTableFileType;
import org.budgetanalyzer.transaction.service.dto.PdfTextTableNegativeMeans;
import org.budgetanalyzer.transaction.service.dto.PdfTextTableParserConfig;
import org.budgetanalyzer.transaction.service.dto.PdfTextTableYearSource;

@SpringBootTest
@Testcontainers
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(TestClaimsSecurityConfig.class)
@Transactional
class StatementExtractorRegistryIntegrationTest {

  private static final String OWNER_ID = "usr_extractor_registry_owner";
  private static final float FONT_SIZE = 10F;
  private static final float DATE_X = 50F;
  private static final float DESCRIPTION_X = 130F;
  private static final float AMOUNT_X = 360F;

  @Container
  private static final PostgreSQLContainer<?> postgresqlContainer =
      new PostgreSQLContainer<>("postgres:17-alpine")
          .withDatabaseName("testdb")
          .withUsername("test")
          .withPassword("test");

  @Autowired private StatementExtractorRegistry statementExtractorRegistry;

  @Autowired private StatementFormatRepository statementFormatRepository;

  @Autowired private ParserRevisionRepository parserRevisionRepository;

  @Autowired private ObjectMapper objectMapper;

  @DynamicPropertySource
  static void configureProperties(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", postgresqlContainer::getJdbcUrl);
    registry.add("spring.datasource.username", postgresqlContainer::getUsername);
    registry.add("spring.datasource.password", postgresqlContainer::getPassword);
    registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
  }

  @Test
  void attemptParseUsesPersistedEnabledRevisionOrderAndMatchesLaterCsvRevision() throws Exception {
    var statementFormat =
        statementFormatRepository.save(
            StatementFormat.createCsvFormat("Registry CSV", "Registry Bank", "USD", OWNER_ID));
    var matchingParserRevision =
        ParserRevision.createCsvColumnConfig(
            statementFormat, 1, objectMapper.writeValueAsString(csvConfig("Transaction Date")));
    var notApplicableParserRevision =
        ParserRevision.createCsvColumnConfig(
            statementFormat, 2, objectMapper.writeValueAsString(csvConfig("Posted Date")));
    var disabledParserRevision =
        ParserRevision.createCsvColumnConfig(
            statementFormat, 3, objectMapper.writeValueAsString(csvConfig("Transaction Date")));
    disabledParserRevision.setEnabled(false);
    parserRevisionRepository.saveAllAndFlush(
        List.of(matchingParserRevision, notApplicableParserRevision, disabledParserRevision));

    var parserAttempts =
        statementExtractorRegistry.attemptParse(
            statementFormat,
            csvContent("01/15/25,Grocery Store,52.34,Debit"),
            "statement.csv",
            "checking");

    assertThat(parserAttempts)
        .extracting(parserAttempt -> parserAttempt.parserRevision().getId())
        .containsExactly(notApplicableParserRevision.getId(), matchingParserRevision.getId());
    assertThat(parserAttempts)
        .extracting("status")
        .containsExactly(ParserAttemptStatus.NOT_APPLICABLE, ParserAttemptStatus.MATCHED);
    assertThat(parserAttempts.get(1).transactions())
        .singleElement()
        .satisfies(
            transaction -> {
              assertThat(transaction.date()).isEqualTo(LocalDate.of(2025, 1, 15));
              assertThat(transaction.description()).isEqualTo("Grocery Store");
              assertThat(transaction.amount()).isEqualByComparingTo(new BigDecimal("52.34"));
              assertThat(transaction.type()).isEqualTo(TransactionType.DEBIT);
              assertThat(transaction.bankName()).isEqualTo("Registry Bank");
              assertThat(transaction.currencyIsoCode()).isEqualTo("USD");
              assertThat(transaction.accountId()).isEqualTo("checking");
            });
  }

  @Test
  void attemptParseDistinguishesNotApplicableRevisionFromApplicableCsvFailure() throws Exception {
    var statementFormat =
        statementFormatRepository.save(
            StatementFormat.createCsvFormat(
                "Registry Failure CSV", "Registry Bank", "USD", OWNER_ID));
    var failedParserRevision =
        ParserRevision.createCsvColumnConfig(
            statementFormat, 1, objectMapper.writeValueAsString(csvConfig("Transaction Date")));
    var notApplicableParserRevision =
        ParserRevision.createCsvColumnConfig(
            statementFormat, 2, objectMapper.writeValueAsString(csvConfig("Posted Date")));
    parserRevisionRepository.saveAllAndFlush(
        List.of(failedParserRevision, notApplicableParserRevision));

    var parserAttempts =
        statementExtractorRegistry.attemptParse(
            statementFormat,
            csvContent("invalid-date,Grocery Store,52.34,Debit"),
            "statement.csv",
            null);

    assertThat(parserAttempts)
        .extracting("status")
        .containsExactly(ParserAttemptStatus.NOT_APPLICABLE, ParserAttemptStatus.FAILED);
    assertThat(parserAttempts.get(1).parserRevision().getId())
        .isEqualTo(failedParserRevision.getId());
    assertThat(parserAttempts.get(1).failure().getCode())
        .isEqualTo(BudgetAnalyzerError.CSV_PARSING_ERROR.name());
  }

  @Test
  void attemptParseSelectsPersistedPdfConfigAfterUnknownStaticHandler() throws Exception {
    var statementFormat =
        statementFormatRepository.save(
            StatementFormat.createSystemPdfFormat("Registry PDF", "Registry PDF Bank", "USD"));
    var pdfTextTableParserRevision =
        ParserRevision.createPdfTextTableConfig(
            statementFormat, 1, objectMapper.writeValueAsString(pdfConfig()));
    var unknownStaticParserRevision =
        ParserRevision.createStaticHandler(statementFormat, 2, "missing-handler");
    parserRevisionRepository.saveAllAndFlush(
        List.of(pdfTextTableParserRevision, unknownStaticParserRevision));

    var parserAttempts =
        statementExtractorRegistry.attemptParse(
            statementFormat,
            pdfWithRows(
                List.of(
                    List.of("Date", "Description", "Amount"),
                    List.of("01/02/2025", "Coffee Shop", "$4.50"))),
            "statement.pdf",
            "checking");

    assertThat(parserAttempts)
        .extracting("status")
        .containsExactly(ParserAttemptStatus.NOT_APPLICABLE, ParserAttemptStatus.MATCHED);
    assertThat(parserAttempts)
        .extracting(parserAttempt -> parserAttempt.parserRevision().getId())
        .containsExactly(unknownStaticParserRevision.getId(), pdfTextTableParserRevision.getId());
    assertThat(pdfTextTableParserRevision.getParserType())
        .isEqualTo(ParserType.PDF_TEXT_TABLE_CONFIG);
    assertThat(pdfTextTableParserRevision.getHandlerKey()).isNull();
    assertThat(parserAttempts.get(1).transactions())
        .singleElement()
        .satisfies(
            transaction -> {
              assertThat(transaction.description()).isEqualTo("Coffee Shop");
              assertThat(transaction.amount()).isEqualByComparingTo(new BigDecimal("4.50"));
              assertThat(transaction.type()).isEqualTo(TransactionType.DEBIT);
              assertThat(transaction.bankName()).isEqualTo("Registry PDF Bank");
              assertThat(transaction.accountId()).isEqualTo("checking");
            });
  }

  private CsvColumnParserConfig csvConfig(String dateHeader) {
    return new CsvColumnParserConfig(
        dateHeader,
        "MM/dd/uu",
        "Transaction Description",
        "Transaction Amount",
        "Transaction Amount",
        "Transaction Type",
        null);
  }

  private PdfTextTableParserConfig pdfConfig() {
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
        null,
        PdfTextTableNegativeMeans.CREDIT,
        PdfTextTableYearSource.EXPLICIT_DATE);
  }

  private byte[] csvContent(String row) {
    return ("Transaction Date,Transaction Description,Transaction Amount,Transaction Type\n"
            + row
            + "\n")
        .getBytes(StandardCharsets.UTF_8);
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
}
