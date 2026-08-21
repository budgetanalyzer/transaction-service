package org.budgetanalyzer.transaction.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
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

import org.budgetanalyzer.service.exception.BusinessException;
import org.budgetanalyzer.service.exception.ResourceNotFoundException;
import org.budgetanalyzer.service.security.test.TestClaimsSecurityConfig;
import org.budgetanalyzer.transaction.domain.FormatType;
import org.budgetanalyzer.transaction.domain.ParserType;
import org.budgetanalyzer.transaction.domain.StatementFormat;
import org.budgetanalyzer.transaction.domain.StatementFormatScope;
import org.budgetanalyzer.transaction.repository.ParserRevisionRepository;
import org.budgetanalyzer.transaction.repository.StatementFormatRepository;
import org.budgetanalyzer.transaction.repository.StatementFormatUserPreferenceRepository;
import org.budgetanalyzer.transaction.service.dto.CsvColumnParserConfig;
import org.budgetanalyzer.transaction.service.dto.CsvWizardAmountMode;
import org.budgetanalyzer.transaction.service.dto.CsvWizardColumnMapping;
import org.budgetanalyzer.transaction.service.dto.CsvWizardSaveCommand;
import org.budgetanalyzer.transaction.service.dto.PdfTextTableNegativeMeans;
import org.budgetanalyzer.transaction.service.dto.PdfTextTableParserConfig;
import org.budgetanalyzer.transaction.service.dto.PdfTextTableYearSource;
import org.budgetanalyzer.transaction.service.dto.PdfWizardAmountMode;
import org.budgetanalyzer.transaction.service.dto.PdfWizardColumnMapping;
import org.budgetanalyzer.transaction.service.dto.PdfWizardSaveCommand;
import org.budgetanalyzer.transaction.service.dto.StatementFormatCommand;
import org.budgetanalyzer.transaction.service.dto.StatementFormatPatch;

@SpringBootTest
@Testcontainers
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(TestClaimsSecurityConfig.class)
@Transactional
class StatementFormatServiceIntegrationTest {

  private static final String OWNER_ID = "usr_statement_format_owner";
  private static final String OTHER_OWNER_ID = "usr_statement_format_other";
  private static final float FONT_SIZE = 10F;

  @Container
  private static final PostgreSQLContainer<?> postgresqlContainer =
      new PostgreSQLContainer<>("postgres:17-alpine")
          .withDatabaseName("testdb")
          .withUsername("test")
          .withPassword("test");

  @Autowired private StatementFormatService statementFormatService;

  @Autowired private CsvStatementFormatWizardService csvStatementFormatWizardService;

  @Autowired private PdfStatementFormatWizardService pdfStatementFormatWizardService;

  @Autowired private StatementFormatRepository statementFormatRepository;

  @Autowired private ParserRevisionRepository parserRevisionRepository;

  @Autowired
  private StatementFormatUserPreferenceRepository statementFormatUserPreferenceRepository;

  @Autowired private ObjectMapper objectMapper;

  @DynamicPropertySource
  static void configureProperties(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", postgresqlContainer::getJdbcUrl);
    registry.add("spring.datasource.username", postgresqlContainer::getUsername);
    registry.add("spring.datasource.password", postgresqlContainer::getPassword);
    registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
  }

  @Test
  void listFormatsAppliesVisibilityAndPersistedHiddenPreference() {
    var ownedFormat =
        statementFormatRepository.save(
            StatementFormat.createCsvFormat("Owned Format", "Owned Bank", "USD", OWNER_ID));
    var otherFormat =
        statementFormatRepository.save(
            StatementFormat.createCsvFormat("Other Format", "Other Bank", "USD", OTHER_OWNER_ID));

    var visibleFormats = statementFormatService.listFormats(OWNER_ID, false, false);

    assertThat(visibleFormats)
        .extracting(item -> item.statementFormat().getId())
        .contains(ownedFormat.getId())
        .doesNotContain(otherFormat.getId());

    statementFormatService.hideFormat(ownedFormat.getId(), OWNER_ID);

    assertThat(statementFormatService.listFormats(OWNER_ID, false, false))
        .extracting(item -> item.statementFormat().getId())
        .doesNotContain(ownedFormat.getId());
    assertThat(statementFormatService.listFormats(OWNER_ID, false, true))
        .anySatisfy(
            item -> {
              assertThat(item.statementFormat().getId()).isEqualTo(ownedFormat.getId());
              assertThat(item.hidden()).isTrue();
            });
    var preference =
        statementFormatUserPreferenceRepository
            .findByStatementFormatIdAndUserId(ownedFormat.getId(), OWNER_ID)
            .orElseThrow();
    assertThat(preference.isHidden()).isTrue();

    statementFormatService.unhideFormat(ownedFormat.getId(), OWNER_ID);

    assertThat(
            statementFormatUserPreferenceRepository
                .findByStatementFormatIdAndUserId(ownedFormat.getId(), OWNER_ID)
                .orElseThrow()
                .isHidden())
        .isFalse();
  }

  @Test
  void getByIdEnforcesVisibilityUnlessReadAnyIsAllowed() {
    var otherFormat =
        statementFormatRepository.save(
            StatementFormat.createCsvFormat("Other Format", "Other Bank", "USD", OTHER_OWNER_ID));

    assertThatThrownBy(() -> statementFormatService.getById(otherFormat.getId(), OWNER_ID, false))
        .isInstanceOf(ResourceNotFoundException.class);

    assertThat(statementFormatService.getById(otherFormat.getId(), OWNER_ID, true).getId())
        .isEqualTo(otherFormat.getId());
  }

  @Test
  void createFormatPersistsUserScopeAndInitialCsvParserRevision() throws Exception {
    var command = csvFormatCommand("Created CSV", StatementFormatScope.USER, "usd");

    var created = statementFormatService.createFormat(command, OWNER_ID, false);

    var persisted = statementFormatRepository.findById(created.getId()).orElseThrow();
    assertThat(persisted.getFormatType()).isEqualTo(FormatType.CSV);
    assertThat(persisted.getScope()).isEqualTo(StatementFormatScope.USER);
    assertThat(persisted.getOwnerId()).isEqualTo(OWNER_ID);
    assertThat(persisted.getDefaultCurrencyIsoCode()).isEqualTo("USD");
    var parserRevision = parserRevision(created);
    assertThat(parserRevision.getRevisionNumber()).isEqualTo(1);
    assertThat(parserRevision.getParserType()).isEqualTo(ParserType.CSV_COLUMN_CONFIG);
    var parserConfig =
        objectMapper.readValue(parserRevision.getParserConfig(), CsvColumnParserConfig.class);
    assertThat(parserConfig.dateHeader()).isEqualTo("Date");
    assertThat(parserConfig.creditHeader()).isEqualTo("Amount");
    assertThat(parserConfig.debitHeader()).isEqualTo("Amount");
    assertThat(parserConfig.typeHeader()).isEqualTo("Type");
  }

  @Test
  void createFormatPersistsSystemScopeOnlyWhenWriteAnyIsAllowed() {
    var command = csvFormatCommand("System CSV", StatementFormatScope.SYSTEM, "usd");

    assertThatThrownBy(() -> statementFormatService.createFormat(command, OWNER_ID, false))
        .isInstanceOf(BusinessException.class);

    var created = statementFormatService.createFormat(command, OWNER_ID, true);

    assertThat(created.getScope()).isEqualTo(StatementFormatScope.SYSTEM);
    assertThat(created.getOwnerId()).isNull();
    assertThat(statementFormatRepository.findById(created.getId())).isPresent();
    assertThat(parserRevision(created).getParserType()).isEqualTo(ParserType.CSV_COLUMN_CONFIG);
  }

  @Test
  void createFormatValidationLeavesPersistenceUnchanged() {
    var formatCount = statementFormatRepository.count();
    var revisionCount = parserRevisionRepository.count();
    var invalidCommand = csvFormatCommand("Invalid CSV", StatementFormatScope.USER, "BAD");

    assertThatThrownBy(() -> statementFormatService.createFormat(invalidCommand, OWNER_ID, false))
        .isInstanceOfSatisfying(
            BusinessException.class,
            businessException ->
                assertThat(businessException.getFieldErrors())
                    .extracting("field")
                    .contains("defaultCurrencyIsoCode"));

    assertThat(statementFormatRepository.count()).isEqualTo(formatCount);
    assertThat(parserRevisionRepository.count()).isEqualTo(revisionCount);
  }

  @Test
  void updateFormatPersistsOwnedMetadataAndRejectsSystemFormatWithoutWriteAny() {
    var ownedFormat =
        statementFormatRepository.save(
            StatementFormat.createUserPdfFormat("Owned PDF", "Original Bank", "USD", OWNER_ID));
    var systemFormat =
        statementFormatRepository.save(
            StatementFormat.createSystemPdfFormat("System PDF", "System Bank", "USD"));

    var updated =
        statementFormatService.updateFormat(
            ownedFormat.getId(),
            new StatementFormatPatch("Updated PDF", "Updated Bank", "eur", false),
            OWNER_ID,
            false);

    assertThat(updated.getDisplayName()).isEqualTo("Updated PDF");
    assertThat(updated.getBankName()).isEqualTo("Updated Bank");
    assertThat(updated.getDefaultCurrencyIsoCode()).isEqualTo("EUR");
    assertThat(updated.isEnabled()).isFalse();
    assertThat(statementFormatRepository.findById(ownedFormat.getId()).orElseThrow().isEnabled())
        .isFalse();
    assertThatThrownBy(
            () ->
                statementFormatService.updateFormat(
                    systemFormat.getId(),
                    new StatementFormatPatch("Rejected", null, null, null),
                    OWNER_ID,
                    false))
        .isInstanceOf(ResourceNotFoundException.class);
  }

  @Test
  void csvWizardSavePersistsConfirmedMappingAsParserRevision() throws Exception {
    var command =
        new CsvWizardSaveCommand("Example CSV", "Example Bank", "usd", csvSingleAmountMapping());

    var saved =
        csvStatementFormatWizardService.save(
            """
            Transaction Date,Description,Amount,Type
            04/12/24,Coffee Shop,4.50,Debit
            """
                .getBytes(StandardCharsets.UTF_8),
            "sample.csv",
            command,
            OWNER_ID);

    assertThat(statementFormatRepository.findById(saved.getId())).isPresent();
    assertThat(saved.getFormatType()).isEqualTo(FormatType.CSV);
    assertThat(saved.getScope()).isEqualTo(StatementFormatScope.USER);
    assertThat(saved.getOwnerId()).isEqualTo(OWNER_ID);
    var parserRevision = parserRevision(saved);
    var parserConfig =
        objectMapper.readValue(parserRevision.getParserConfig(), CsvColumnParserConfig.class);
    assertThat(parserRevision.getParserType()).isEqualTo(ParserType.CSV_COLUMN_CONFIG);
    assertThat(parserConfig.creditHeader()).isEqualTo("Amount");
    assertThat(parserConfig.debitHeader()).isEqualTo("Amount");
    assertThat(parserConfig.typeHeader()).isEqualTo("Type");
  }

  @Test
  void pdfWizardSavePersistsConfirmedMappingAsParserRevision() throws Exception {
    var saved =
        pdfStatementFormatWizardService.save(
            pdfWithRows(
                List.of(
                    List.of("Date", "Description", "Amount"),
                    List.of("01/02/2025", "Coffee Shop", "$4.50"),
                    List.of("01/03/2025", "Payment", "-$100.00"))),
            "statement.pdf",
            pdfSaveCommand(),
            OWNER_ID);

    assertThat(statementFormatRepository.findById(saved.getId())).isPresent();
    assertThat(saved.getFormatType()).isEqualTo(FormatType.PDF);
    assertThat(saved.getScope()).isEqualTo(StatementFormatScope.USER);
    assertThat(saved.getOwnerId()).isEqualTo(OWNER_ID);
    assertThat(saved.getDefaultCurrencyIsoCode()).isEqualTo("USD");
    var parserRevision = parserRevision(saved);
    var parserConfig =
        objectMapper.readValue(parserRevision.getParserConfig(), PdfTextTableParserConfig.class);
    assertThat(parserRevision.getParserType()).isEqualTo(ParserType.PDF_TEXT_TABLE_CONFIG);
    assertThat(parserRevision.getHandlerKey()).isNull();
    assertThat(parserConfig.headerMustContain()).containsExactly("Date", "Description", "Amount");
    assertThat(parserConfig.amountHeader()).isEqualTo("Amount");
  }

  private StatementFormatCommand csvFormatCommand(
      String displayName, StatementFormatScope statementFormatScope, String currencyIsoCode) {
    return new StatementFormatCommand(
        displayName,
        FormatType.CSV,
        "Test Bank",
        currencyIsoCode,
        statementFormatScope,
        "Date",
        "MM/dd/uu",
        "Description",
        "Amount",
        "Amount",
        "Type",
        null);
  }

  private org.budgetanalyzer.transaction.domain.ParserRevision parserRevision(
      StatementFormat statementFormat) {
    return parserRevisionRepository
        .findByStatementFormatIdAndEnabledTrueOrderByPriorityDescRevisionNumberDesc(
            statementFormat.getId())
        .getFirst();
  }

  private CsvWizardColumnMapping csvSingleAmountMapping() {
    return new CsvWizardColumnMapping(
        "Transaction Date",
        "MM/dd/uu",
        "Description",
        CsvWizardAmountMode.SINGLE_AMOUNT_WITH_TYPE,
        "Amount",
        null,
        null,
        "Type",
        null);
  }

  private PdfWizardSaveCommand pdfSaveCommand() {
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

  private byte[] pdfWithRows(List<List<String>> rows) throws IOException {
    try (var document = new PDDocument()) {
      var page = new PDPage();
      document.addPage(page);
      var font = new PDType1Font(Standard14Fonts.FontName.HELVETICA);
      try (var contentStream = new PDPageContentStream(document, page)) {
        var y = 750F;
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
    var positions = List.of(50F, 130F, 430F);
    for (var index = 0; index < row.size(); index++) {
      contentStream.beginText();
      contentStream.setFont(font, FONT_SIZE);
      contentStream.newLineAtOffset(positions.get(index), y);
      contentStream.showText(row.get(index));
      contentStream.endText();
    }
  }
}
