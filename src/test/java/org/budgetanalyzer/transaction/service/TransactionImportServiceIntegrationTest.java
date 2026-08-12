package org.budgetanalyzer.transaction.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
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

import org.budgetanalyzer.service.exception.BusinessException;
import org.budgetanalyzer.service.security.test.TestClaimsSecurityConfig;
import org.budgetanalyzer.transaction.domain.StatementFormat;
import org.budgetanalyzer.transaction.repository.FileImportRepository;
import org.budgetanalyzer.transaction.repository.ParserRevisionRepository;
import org.budgetanalyzer.transaction.repository.StatementFormatRepository;
import org.budgetanalyzer.transaction.repository.TransactionRepository;
import org.budgetanalyzer.transaction.service.dto.BatchFileImportSource;
import org.budgetanalyzer.transaction.service.dto.BatchImportFile;
import org.budgetanalyzer.transaction.service.dto.PdfTextTableNegativeMeans;
import org.budgetanalyzer.transaction.service.dto.PdfTextTableYearSource;
import org.budgetanalyzer.transaction.service.dto.PdfWizardAmountMode;
import org.budgetanalyzer.transaction.service.dto.PdfWizardColumnMapping;
import org.budgetanalyzer.transaction.service.dto.PdfWizardSaveCommand;
import org.budgetanalyzer.transaction.service.dto.PreviewDuplicateReason;

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

  @Autowired private TransactionService transactionService;

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
  void previewFilesPreservesOrderAndMarksOnlyLaterFileDuplicates() {
    var statementFormat = bangkokStatementCsvFormat();
    var repeatedRows =
        """
        Date,Particulars,Withdrawal,Deposit
        10/01/25,COFFEE SHOP,4.50,
        10/01/25,COFFEE SHOP,4.50,
        """;
    var laterRows =
        """
        Date,Particulars,Withdrawal,Deposit
        10/01/25,COFFEE SHOP,4.50,
        """;
    var firstFile =
        new MockMultipartFile(
            "files", "first.csv", "text/csv", repeatedRows.getBytes(StandardCharsets.UTF_8));
    var laterFile =
        new MockMultipartFile(
            "files", "later.csv", "text/csv", laterRows.getBytes(StandardCharsets.UTF_8));

    var previewResult =
        transactionImportService.previewFiles(
            statementFormat.getId(), "checking-001", List.of(firstFile, laterFile), USER_ID);

    assertThat(previewResult.files())
        .extracting(fileResult -> fileResult.sourceFile())
        .containsExactly("first.csv", "later.csv");
    assertThat(previewResult.files().getFirst().transactions())
        .hasSize(2)
        .allSatisfy(
            transaction -> {
              assertThat(transaction.duplicate()).isFalse();
              assertThat(transaction.duplicateReason()).isNull();
            });
    assertThat(previewResult.files().get(1).transactions())
        .singleElement()
        .satisfies(
            transaction -> {
              assertThat(transaction.duplicate()).isTrue();
              assertThat(transaction.duplicateReason()).isEqualTo(PreviewDuplicateReason.IN_BATCH);
            });
    assertThat(transactionRepository.findAll()).isEmpty();
    assertThat(fileImportRepository.findAll()).isEmpty();
  }

  @Test
  void previewFilesFailsWholeRequestWhenLaterFileHasParserFailure() {
    var statementFormat = bangkokStatementCsvFormat();
    var validFile =
        new MockMultipartFile(
            "files",
            "valid-first.csv",
            "text/csv",
            "Date,Particulars,Withdrawal,Deposit\n10/01/25,COFFEE SHOP,4.50,"
                .getBytes(StandardCharsets.UTF_8));
    var invalidFile =
        new MockMultipartFile(
            "files",
            "invalid-later.csv",
            "text/csv",
            "Date,Particulars,Withdrawal,Deposit\nnot-a-date,GROCERIES,42.30,"
                .getBytes(StandardCharsets.UTF_8));

    assertThatThrownBy(
            () ->
                transactionImportService.previewFiles(
                    statementFormat.getId(),
                    "checking-001",
                    List.of(validFile, invalidFile),
                    USER_ID))
        .isInstanceOf(BusinessException.class)
        .satisfies(
            exception -> {
              var businessException = (BusinessException) exception;
              assertThat(businessException.getCode())
                  .isEqualTo(BudgetAnalyzerError.CSV_PARSING_ERROR.name());
            });
    assertThat(transactionRepository.findAll()).isEmpty();
    assertThat(fileImportRepository.findAll()).isEmpty();
  }

  @Test
  void previewFile_capitalOneMonthlyCreditPdfRecordsWinningParserRevisionInToken()
      throws IOException {
    var statementFormat =
        statementFormatRepository.findAll().stream()
            .filter(statementFormatCandidate -> statementFormatCandidate.isEnabled())
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
        transactionImportService.previewFiles(
            statementFormat.getId(), "capital-one-card", List.of(multipartFile), USER_ID);
    var previewFileResult = previewResult.files().getFirst();
    var previewImportToken =
        previewImportTokenService.verifyToken(previewFileResult.previewImportToken(), USER_ID);

    assertThat(previewFileResult.statementFormatId()).isEqualTo(statementFormat.getId());
    assertThat(previewFileResult.transactions()).hasSizeGreaterThan(10);
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
        transactionImportService.previewFiles(
            statementFormat.getId(), "checking-001", List.of(multipartFile), USER_ID);
    var previewFileResult = previewResult.files().getFirst();
    var previewImportToken =
        previewImportTokenService.verifyToken(previewFileResult.previewImportToken(), USER_ID);

    assertThat(previewFileResult.statementFormatId()).isEqualTo(statementFormat.getId());
    assertThat(previewFileResult.transactions()).hasSize(2);
    assertThat(previewFileResult.transactions().getFirst().description()).isEqualTo("Coffee Shop");
    assertThat(previewImportToken.parserRevisionId()).isEqualTo(parserRevision.getId());

    var batchImportResult =
        transactionService.batchImport(
            List.of(
                new BatchImportFile(
                    BatchFileImportSource.from(previewImportToken),
                    previewFileResult.transactions())),
            USER_ID);

    assertThat(batchImportResult.createdTransactions()).hasSize(2);
    assertThat(transactionRepository.findAll())
        .extracting("description")
        .containsExactlyInAnyOrder("Coffee Shop", "Payment");
    assertThat(transactionRepository.findAll())
        .allSatisfy(transaction -> assertThat(transaction.getFileImport()).isNotNull());
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

  private StatementFormat bangkokStatementCsvFormat() {
    return statementFormatRepository.findAll().stream()
        .filter(statementFormat -> statementFormat.isEnabled())
        .filter(
            statementFormat -> statementFormat.getDisplayName().equals("Bangkok Bank - Statement"))
        .findFirst()
        .orElseThrow();
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
