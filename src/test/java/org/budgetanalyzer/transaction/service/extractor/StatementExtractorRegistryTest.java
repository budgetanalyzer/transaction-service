package org.budgetanalyzer.transaction.service.extractor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

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
import org.budgetanalyzer.transaction.service.dto.PreviewTransaction;

@ExtendWith(MockitoExtension.class)
class StatementExtractorRegistryTest {

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
            new ObjectMapper().findAndRegisterModules());
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
              new ObjectMapper().findAndRegisterModules());

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
