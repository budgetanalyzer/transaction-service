package org.budgetanalyzer.transaction.service.extractor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

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
import org.budgetanalyzer.transaction.domain.FormatType;
import org.budgetanalyzer.transaction.domain.ParserRevision;
import org.budgetanalyzer.transaction.domain.ParserType;
import org.budgetanalyzer.transaction.domain.StatementFormat;
import org.budgetanalyzer.transaction.domain.Transaction;
import org.budgetanalyzer.transaction.domain.TransactionType;
import org.budgetanalyzer.transaction.repository.ParserRevisionRepository;
import org.budgetanalyzer.transaction.repository.StatementFormatRepository;
import org.budgetanalyzer.transaction.service.dto.ParserAttemptStatus;
import org.budgetanalyzer.transaction.service.dto.PreviewTransaction;

@ExtendWith(MockitoExtension.class)
class StatementExtractorRegistryTest {

  @Mock private StatementFormatRepository formatRepository;
  @Mock private ParserRevisionRepository parserRevisionRepository;
  @Mock private CsvParser csvParser;
  @Mock private StatementExtractor staticPdfExtractor;

  private StatementExtractorRegistry registry;

  @BeforeEach
  void setUp() {
    when(staticPdfExtractor.getFormatKey()).thenReturn("capital-one-yearly");
    when(formatRepository.findByFormatTypeAndEnabledTrue(FormatType.CSV)).thenReturn(List.of());

    registry =
        new StatementExtractorRegistry(List.of(staticPdfExtractor), formatRepository, csvParser);
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
      var modernRegistry =
          new StatementExtractorRegistry(
              List.of(firstStatementExtractor, secondStatementExtractor),
              parserRevisionRepository,
              csvParser,
              new ObjectMapper().findAndRegisterModules());

      when(parserRevisionRepository.findByParserTypeAndEnabledTrue(ParserType.CSV_COLUMN_CONFIG))
          .thenReturn(List.of());
      when(parserRevisionRepository
              .findByStatementFormatIdAndEnabledTrueOrderByPriorityDescRevisionNumberDesc(42L))
          .thenReturn(List.of(firstParserRevision, secondParserRevision));
      modernRegistry.initialize();

      var parserAttempts =
          modernRegistry.attemptParse(
              statementFormat, "pdf".getBytes(), "statement.pdf", "account-123");

      assertThat(parserAttempts)
          .extracting("status")
          .containsExactly(ParserAttemptStatus.NOT_APPLICABLE, ParserAttemptStatus.MATCHED);
      assertThat(parserAttempts.get(1).transactions()).containsExactly(matchedTransaction);
    }
  }

  @Nested
  class FindByFormat {

    @Test
    void findsStaticExtractorByFormatKey() {
      var result = registry.findByFormat("capital-one-yearly");

      assertThat(result).isPresent();
      assertThat(result.get().getFormatKey()).isEqualTo("capital-one-yearly");
    }

    @Test
    void findsCsvExtractorFromCache() {
      var csvFormat = createCsvFormat("test-csv");
      when(formatRepository.findByFormatTypeAndEnabledTrue(FormatType.CSV))
          .thenReturn(List.of(csvFormat));
      registry.refreshCsvExtractors();

      var result = registry.findByFormat("test-csv");

      assertThat(result).isPresent();
      assertThat(result.get().getFormatKey()).isEqualTo("test-csv");
    }

    @Test
    void loadsCsvExtractorFromDatabaseOnCacheMiss() {
      var csvFormat = createCsvFormat("new-csv-format");
      when(formatRepository.findByFormatKeyAndEnabledTrue("new-csv-format"))
          .thenReturn(Optional.of(csvFormat));

      var result = registry.findByFormat("new-csv-format");

      assertThat(result).isPresent();
      assertThat(result.get().getFormatKey()).isEqualTo("new-csv-format");
    }

    @Test
    void returnsEmptyForUnknownFormat() {
      when(formatRepository.findByFormatKeyAndEnabledTrue("unknown-format"))
          .thenReturn(Optional.empty());

      var result = registry.findByFormat("unknown-format");

      assertThat(result).isEmpty();
    }

    @Test
    void prioritizesStaticExtractorsOverDatabase() {
      // Even if there's a database entry with the same key, static extractors take precedence
      var csvFormat = createCsvFormat("capital-one-yearly");
      when(formatRepository.findByFormatTypeAndEnabledTrue(FormatType.CSV))
          .thenReturn(List.of(csvFormat));
      registry.refreshCsvExtractors();

      var result = registry.findByFormat("capital-one-yearly");

      assertThat(result).isPresent();
      assertThat(result.get()).isSameAs(staticPdfExtractor);
    }

    @Test
    void doesNotLoadPdfFormatsAsCsvExtractors() {
      var pdfFormat = createPdfFormat("some-pdf-format");
      when(formatRepository.findByFormatKeyAndEnabledTrue("some-pdf-format"))
          .thenReturn(Optional.of(pdfFormat));

      var result = registry.findByFormat("some-pdf-format");

      assertThat(result).isEmpty();
    }
  }

  @Nested
  class GetAllExtractors {

    @Test
    void includesStaticExtractors() {
      var all = registry.getAllExtractors();

      assertThat(all).hasSize(1);
      assertThat(all).contains(staticPdfExtractor);
    }

    @Test
    void includesCachedCsvExtractors() {
      var csvFormat = createCsvFormat("cached-csv");
      when(formatRepository.findByFormatTypeAndEnabledTrue(FormatType.CSV))
          .thenReturn(List.of(csvFormat));
      registry.refreshCsvExtractors();

      var all = registry.getAllExtractors();

      assertThat(all).hasSize(2);
      assertThat(all.stream().map(StatementExtractor::getFormatKey))
          .containsExactlyInAnyOrder("capital-one-yearly", "cached-csv");
    }
  }

  @Nested
  class RefreshCsvExtractors {

    @Test
    void clearsAndReloadsCache() {
      var format1 = createCsvFormat("csv-format-1");
      when(formatRepository.findByFormatTypeAndEnabledTrue(FormatType.CSV))
          .thenReturn(List.of(format1));
      registry.refreshCsvExtractors();

      assertThat(registry.findByFormat("csv-format-1")).isPresent();

      var format2 = createCsvFormat("csv-format-2");
      when(formatRepository.findByFormatTypeAndEnabledTrue(FormatType.CSV))
          .thenReturn(List.of(format2));
      registry.refreshCsvExtractors();

      assertThat(registry.findByFormat("csv-format-1")).isEmpty();
      assertThat(registry.findByFormat("csv-format-2")).isPresent();
    }

    @Test
    void handlesEmptyDatabaseResult() {
      when(formatRepository.findByFormatTypeAndEnabledTrue(FormatType.CSV)).thenReturn(List.of());
      registry.refreshCsvExtractors();

      var all = registry.getAllExtractors();

      assertThat(all).hasSize(1);
      assertThat(all).contains(staticPdfExtractor);
    }
  }

  private StatementFormat createCsvFormat(String formatKey) {
    return StatementFormat.createCsvFormat(
        formatKey,
        "Test Bank - Export",
        "Test Bank",
        "USD",
        "Date",
        "MM/dd/uu",
        "Description",
        "Amount",
        "Amount",
        "Type",
        null);
  }

  private StatementFormat createPdfFormat(String formatKey) {
    return StatementFormat.createPdfFormat(formatKey, "Test Bank - PDF", "Test Bank", "USD");
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

    private final String formatKey;
    private final boolean canHandle;
    private final List<PreviewTransaction> transactions;

    TestStatementExtractor(
        String formatKey, boolean canHandle, List<PreviewTransaction> transactions) {
      this.formatKey = formatKey;
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
    public String getFormatKey() {
      return formatKey;
    }
  }
}
