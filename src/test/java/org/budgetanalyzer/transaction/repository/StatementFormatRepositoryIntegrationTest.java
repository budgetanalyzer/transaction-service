package org.budgetanalyzer.transaction.repository;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import org.budgetanalyzer.transaction.domain.FormatType;
import org.budgetanalyzer.transaction.domain.StatementFormat;

@DataJpaTest
@Testcontainers
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class StatementFormatRepositoryIntegrationTest {

  @Container
  private static final PostgreSQLContainer<?> postgres =
      new PostgreSQLContainer<>("postgres:17-alpine")
          .withDatabaseName("testdb")
          .withUsername("test")
          .withPassword("test");

  @Autowired private StatementFormatRepository repository;

  @DynamicPropertySource
  static void configureProperties(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", postgres::getJdbcUrl);
    registry.add("spring.datasource.username", postgres::getUsername);
    registry.add("spring.datasource.password", postgres::getPassword);
    registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
  }

  /** Helper to clean up test-created formats without affecting seeded data. */
  private void deleteTestFormat(String formatKey) {
    repository.findByFormatKey(formatKey).ifPresent(repository::delete);
  }

  @Nested
  class Save {

    @BeforeEach
    void setUp() {
      deleteTestFormat("test-csv");
      deleteTestFormat("test-pdf");
    }

    @Test
    void savesCsvFormat() {
      var format = createCsvFormat("test-csv", "Test Bank");

      var saved = repository.save(format);

      assertThat(saved.getId()).isNotNull();
      assertThat(saved.getFormatKey()).isEqualTo("test-csv");
      assertThat(saved.getFormatType()).isEqualTo(FormatType.CSV);
      assertThat(saved.getBankName()).isEqualTo("Test Bank");
      assertThat(saved.getDefaultCurrencyIsoCode()).isEqualTo("USD");
      assertThat(saved.getDateHeader()).isEqualTo("Date");
      assertThat(saved.getDateFormat()).isEqualTo("MM/dd/uu");
      assertThat(saved.getDescriptionHeader()).isEqualTo("Description");
      assertThat(saved.getCreditHeader()).isEqualTo("Amount");
      assertThat(saved.isEnabled()).isTrue();
    }

    @Test
    void savesPdfFormat() {
      var format =
          StatementFormat.createPdfFormat("test-pdf", "Test Bank - PDF", "Test Bank", "USD");

      var saved = repository.save(format);

      assertThat(saved.getId()).isNotNull();
      assertThat(saved.getFormatKey()).isEqualTo("test-pdf");
      assertThat(saved.getFormatType()).isEqualTo(FormatType.PDF);
      assertThat(saved.getBankName()).isEqualTo("Test Bank");
      assertThat(saved.getDateHeader()).isNull();
    }
  }

  @Nested
  class FindByFormatKey {

    @BeforeEach
    void setUp() {
      deleteTestFormat("existing-format");
    }

    @Test
    void findsExistingFormat() {
      repository.save(createCsvFormat("existing-format", "Bank"));

      var result = repository.findByFormatKey("existing-format");

      assertThat(result).isPresent();
      assertThat(result.get().getFormatKey()).isEqualTo("existing-format");
    }

    @Test
    void returnsEmptyForNonExistentFormat() {
      var result = repository.findByFormatKey("non-existent");

      assertThat(result).isEmpty();
    }
  }

  @Nested
  class FindByFormatKeyAndEnabledTrue {

    @BeforeEach
    void setUp() {
      deleteTestFormat("enabled-format");
      deleteTestFormat("disabled-format");
    }

    @Test
    void findsEnabledFormat() {
      repository.save(createCsvFormat("enabled-format", "Bank"));

      var result = repository.findByFormatKeyAndEnabledTrue("enabled-format");

      assertThat(result).isPresent();
    }

    @Test
    void excludesDisabledFormat() {
      var format = createCsvFormat("disabled-format", "Bank");
      format.setEnabled(false);
      repository.save(format);

      var result = repository.findByFormatKeyAndEnabledTrue("disabled-format");

      assertThat(result).isEmpty();
    }
  }

  @Nested
  class FindByFormatTypeAndEnabledTrue {

    @BeforeEach
    void setUp() {
      deleteTestFormat("csv-1");
      deleteTestFormat("csv-2");
      deleteTestFormat("pdf-1");
      deleteTestFormat("enabled");
      deleteTestFormat("disabled");
    }

    @Test
    void findsCsvFormats() {
      repository.save(createCsvFormat("csv-1", "Bank 1"));
      repository.save(createCsvFormat("csv-2", "Bank 2"));
      repository.save(StatementFormat.createPdfFormat("pdf-1", "Bank 3 - PDF", "Bank 3", "USD"));

      var result = repository.findByFormatTypeAndEnabledTrue(FormatType.CSV);

      // Include seeded formats plus our test formats
      assertThat(result).extracting(StatementFormat::getFormatKey).contains("csv-1", "csv-2");
    }

    @Test
    void excludesDisabledFormats() {
      var enabled = createCsvFormat("enabled", "Bank 1");
      var disabled = createCsvFormat("disabled", "Bank 2");
      disabled.setEnabled(false);

      repository.save(enabled);
      repository.save(disabled);

      var result = repository.findByFormatTypeAndEnabledTrue(FormatType.CSV);

      assertThat(result).extracting(StatementFormat::getFormatKey).contains("enabled");
      assertThat(result).extracting(StatementFormat::getFormatKey).doesNotContain("disabled");
    }
  }

  @Nested
  class FindByEnabledTrue {

    @BeforeEach
    void setUp() {
      deleteTestFormat("find-enabled-csv-1");
      deleteTestFormat("find-enabled-pdf-1");
      deleteTestFormat("find-enabled-disabled");
    }

    @Test
    void findsAllEnabledFormats() {
      repository.save(createCsvFormat("find-enabled-csv-1", "Bank 1"));
      repository.save(
          StatementFormat.createPdfFormat("find-enabled-pdf-1", "Bank 2 - PDF", "Bank 2", "USD"));
      var disabled = createCsvFormat("find-enabled-disabled", "Bank 3");
      disabled.setEnabled(false);
      repository.save(disabled);

      var result = repository.findByEnabledTrue();

      assertThat(result)
          .extracting(StatementFormat::getFormatKey)
          .contains("find-enabled-csv-1", "find-enabled-pdf-1");
      assertThat(result)
          .extracting(StatementFormat::getFormatKey)
          .doesNotContain("find-enabled-disabled");
    }
  }

  @Nested
  class ExistsByFormatKey {

    @BeforeEach
    void setUp() {
      deleteTestFormat("existing");
    }

    @Test
    void returnsTrueForExistingFormat() {
      repository.save(createCsvFormat("existing", "Bank"));

      var result = repository.existsByFormatKey("existing");

      assertThat(result).isTrue();
    }

    @Test
    void returnsFalseForNonExistentFormat() {
      var result = repository.existsByFormatKey("non-existent");

      assertThat(result).isFalse();
    }
  }

  @Nested
  class Update {

    @BeforeEach
    void setUp() {
      deleteTestFormat("update-test");
    }

    @Test
    void updatesFormatFields() {
      var format = repository.save(createCsvFormat("update-test", "Old Bank"));

      format.setBankName("New Bank");
      format.setDefaultCurrencyIsoCode("EUR");
      repository.save(format);

      var updated = repository.findByFormatKey("update-test").orElseThrow();
      assertThat(updated.getBankName()).isEqualTo("New Bank");
      assertThat(updated.getDefaultCurrencyIsoCode()).isEqualTo("EUR");
    }
  }

  @Nested
  class AuditFields {

    @BeforeEach
    void setUp() {
      deleteTestFormat("audit-fields");
    }

    @Test
    void savesAuditFieldsForNewFormat() {
      var saved = repository.save(createCsvFormat("audit-fields", "Audit Bank"));

      assertThat(saved.getCreatedAt()).isNotNull();
      assertThat(saved.getUpdatedAt()).isNotNull();
      assertThat(saved.getCreatedBy()).isNull();
      assertThat(saved.getUpdatedBy()).isNull();
    }
  }

  @Nested
  class MigrationSeededFormats {

    @Test
    void seededFormatsMatchExpectedConfiguration() {
      var formats = repository.findByEnabledTrue();

      // After V9 migration: export CSV formats removed, keeping statement CSV and PDF formats
      assertThat(formats)
          .extracting(StatementFormat::getFormatKey)
          .containsExactlyInAnyOrder(
              "bkk-bank-statement-csv",
              "capital-one-credit-monthly-statement",
              "capital-one-credit-yearly-statement",
              "capital-one-bank-monthly-statement");
    }

    @Test
    void seededFormatsHaveSystemAuditValues() {
      var formats = repository.findByEnabledTrue();

      assertThat(formats).isNotEmpty();
      assertThat(formats).allSatisfy(format -> assertThat(format.getCreatedAt()).isNotNull());
      assertThat(formats).extracting(StatementFormat::getCreatedBy).containsOnly("SYSTEM");
      assertThat(formats).extracting(StatementFormat::getUpdatedBy).containsOnly("SYSTEM");
    }

    @Test
    void creditMonthlyStatementPdfFormatHasCorrectConfiguration() {
      var format = repository.findByFormatKey("capital-one-credit-monthly-statement");

      assertThat(format).isPresent();
      assertThat(format.get().getFormatType()).isEqualTo(FormatType.PDF);
      assertThat(format.get().getBankName()).isEqualTo("Capital One");
      assertThat(format.get().getDisplayName()).isEqualTo("Capital One Credit - Monthly Statement");
      assertThat(format.get().getDateHeader()).isNull();
      assertThat(format.get().getDescriptionHeader()).isNull();
    }

    @Test
    void creditYearlyStatementPdfFormatHasCorrectConfiguration() {
      var format = repository.findByFormatKey("capital-one-credit-yearly-statement");

      assertThat(format).isPresent();
      assertThat(format.get().getFormatType()).isEqualTo(FormatType.PDF);
      assertThat(format.get().getBankName()).isEqualTo("Capital One");
      assertThat(format.get().getDisplayName()).isEqualTo("Capital One Credit - Yearly Statement");
      assertThat(format.get().getDateHeader()).isNull();
      assertThat(format.get().getDescriptionHeader()).isNull();
    }

    @Test
    void bankMonthlyStatementPdfFormatHasCorrectConfiguration() {
      var format = repository.findByFormatKey("capital-one-bank-monthly-statement");

      assertThat(format).isPresent();
      assertThat(format.get().getFormatType()).isEqualTo(FormatType.PDF);
      assertThat(format.get().getBankName()).isEqualTo("Capital One");
      assertThat(format.get().getDisplayName()).isEqualTo("Capital One Bank - Monthly Statement");
      assertThat(format.get().getDateHeader()).isNull();
      assertThat(format.get().getDescriptionHeader()).isNull();
    }
  }

  private StatementFormat createCsvFormat(String formatKey, String bankName) {
    return StatementFormat.createCsvFormat(
        formatKey,
        bankName + " - Export",
        bankName,
        "USD",
        "Date",
        "MM/dd/uu",
        "Description",
        "Amount",
        "Amount",
        null,
        null);
  }
}
