package org.budgetanalyzer.transaction.service.extractor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import org.budgetanalyzer.core.csv.CsvParser;
import org.budgetanalyzer.transaction.domain.FormatType;
import org.budgetanalyzer.transaction.domain.StatementFormat;
import org.budgetanalyzer.transaction.repository.StatementFormatRepository;

@ExtendWith(MockitoExtension.class)
class StatementExtractorRegistryTest {

  @Mock private StatementFormatRepository formatRepository;
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
}
