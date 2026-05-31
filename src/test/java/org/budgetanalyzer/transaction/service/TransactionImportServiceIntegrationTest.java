package org.budgetanalyzer.transaction.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.List;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import org.budgetanalyzer.service.security.test.TestClaimsSecurityConfig;
import org.budgetanalyzer.transaction.repository.FileImportRepository;
import org.budgetanalyzer.transaction.repository.ParserRevisionRepository;
import org.budgetanalyzer.transaction.repository.StatementFormatRepository;
import org.budgetanalyzer.transaction.repository.TransactionRepository;
import org.budgetanalyzer.transaction.service.dto.PdfTextTableNegativeMeans;
import org.budgetanalyzer.transaction.service.dto.PdfTextTableYearSource;
import org.budgetanalyzer.transaction.service.dto.PdfWizardAmountMode;
import org.budgetanalyzer.transaction.service.dto.PdfWizardColumnMapping;
import org.budgetanalyzer.transaction.service.dto.PdfWizardSaveCommand;

@SpringBootTest
@Testcontainers
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(TestClaimsSecurityConfig.class)
class TransactionImportServiceIntegrationTest {

  private static final String USER_ID = "test-user";
  private static final float FONT_SIZE = 10F;
  private static final float DATE_X = 50F;
  private static final float DESCRIPTION_X = 130F;
  private static final float AMOUNT_X = 360F;

  @Container
  private static final PostgreSQLContainer<?> postgres =
      new PostgreSQLContainer<>("postgres:17-alpine")
          .withDatabaseName("testdb")
          .withUsername("test")
          .withPassword("test");

  @Autowired private TransactionImportService transactionImportService;

  @Autowired private PreviewImportTokenService previewImportTokenService;

  @Autowired private PdfStatementFormatWizardService pdfStatementFormatWizardService;

  @Autowired private TransactionRepository transactionRepository;

  @Autowired private FileImportRepository fileImportRepository;

  @Autowired private StatementFormatRepository statementFormatRepository;

  @Autowired private ParserRevisionRepository parserRevisionRepository;

  @DynamicPropertySource
  static void configureProperties(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", postgres::getJdbcUrl);
    registry.add("spring.datasource.username", postgres::getUsername);
    registry.add("spring.datasource.password", postgres::getPassword);
    registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
  }

  @BeforeEach
  void cleanDatabase() {
    transactionRepository.deleteAllInBatch();
    fileImportRepository.deleteAllInBatch();
  }

  @Test
  void previewFile_capitalOneMonthlyCreditPdfRecordsWinningParserRevisionInToken()
      throws IOException {
    var statementFormat =
        statementFormatRepository.findByEnabledTrue().stream()
            .filter(
                format -> format.getDisplayName().equals("Capital One Credit - Monthly Statement"))
            .findFirst()
            .orElseThrow();
    var parserRevision =
        parserRevisionRepository
            .findByStatementFormatIdAndEnabledTrueOrderByPriorityDescRevisionNumberDesc(
                statementFormat.getId())
            .getFirst();
    var multipartFile =
        new MockMultipartFile(
            "file",
            "cap-one-credit-monthly-sample.pdf",
            "application/pdf",
            Files.readAllBytes(
                Paths.get("src/test/resources/fixtures/cap-one-credit-monthly-sample.pdf")));

    var previewResult =
        transactionImportService.previewFile(
            statementFormat.getId(), "capital-one-card", multipartFile, USER_ID);
    var previewImportToken =
        previewImportTokenService.verifyToken(previewResult.previewImportToken(), USER_ID);

    assertThat(previewResult.statementFormatId()).isEqualTo(statementFormat.getId());
    assertThat(previewResult.transactions()).hasSizeGreaterThan(10);
    assertThat(previewImportToken.statementFormatId()).isEqualTo(statementFormat.getId());
    assertThat(previewImportToken.parserRevisionId()).isEqualTo(parserRevision.getId());
  }

  @Test
  void previewFile_savedPdfTextTableFormatUsesPersistedParserRevision() throws IOException {
    var pdfContent =
        pdfWithRows(
            List.of(
                List.of("Date", "Description", "Amount"),
                List.of("01/02/2025", "Coffee Shop", "$4.50"),
                List.of("01/03/2025", "Payment", "-$100.00")));
    var statementFormat =
        pdfStatementFormatWizardService.save(
            pdfContent, "example-statement.pdf", pdfSaveCommand(), USER_ID);
    var parserRevision =
        parserRevisionRepository
            .findByStatementFormatIdAndEnabledTrueOrderByPriorityDescRevisionNumberDesc(
                statementFormat.getId())
            .getFirst();
    var multipartFile =
        new MockMultipartFile("file", "example-statement.pdf", "application/pdf", pdfContent);

    var previewResult =
        transactionImportService.previewFile(
            statementFormat.getId(), "checking-001", multipartFile, USER_ID);
    var previewImportToken =
        previewImportTokenService.verifyToken(previewResult.previewImportToken(), USER_ID);

    assertThat(previewResult.statementFormatId()).isEqualTo(statementFormat.getId());
    assertThat(previewResult.transactions()).hasSize(2);
    assertThat(previewResult.transactions().getFirst().description()).isEqualTo("Coffee Shop");
    assertThat(previewImportToken.parserRevisionId()).isEqualTo(parserRevision.getId());
  }

  private PdfWizardSaveCommand pdfSaveCommand() {
    return new PdfWizardSaveCommand(
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
            PdfTextTableNegativeMeans.CREDIT));
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
