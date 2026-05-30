package org.budgetanalyzer.transaction.repository;

import static org.assertj.core.api.Assertions.assertThat;

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
import org.budgetanalyzer.transaction.domain.ParserType;
import org.budgetanalyzer.transaction.domain.StatementFormat;
import org.budgetanalyzer.transaction.domain.StatementFormatScope;

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

  @Autowired private StatementFormatRepository statementFormatRepository;
  @Autowired private ParserRevisionRepository parserRevisionRepository;

  @DynamicPropertySource
  static void configureProperties(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", postgres::getJdbcUrl);
    registry.add("spring.datasource.username", postgres::getUsername);
    registry.add("spring.datasource.password", postgres::getPassword);
    registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
  }

  @Nested
  class Save {

    @Test
    void savesUserScopedCsvFormat() {
      var statementFormat =
          StatementFormat.createCsvFormat("Test Bank - Export", "Test Bank", "USD", "usr_123");

      var saved = statementFormatRepository.save(statementFormat);

      assertThat(saved.getId()).isNotNull();
      assertThat(saved.getDisplayName()).isEqualTo("Test Bank - Export");
      assertThat(saved.getFormatType()).isEqualTo(FormatType.CSV);
      assertThat(saved.getBankName()).isEqualTo("Test Bank");
      assertThat(saved.getDefaultCurrencyIsoCode()).isEqualTo("USD");
      assertThat(saved.getScope()).isEqualTo(StatementFormatScope.USER);
      assertThat(saved.getOwnerId()).isEqualTo("usr_123");
      assertThat(saved.isEnabled()).isTrue();
    }

    @Test
    void savesSystemPdfFormat() {
      var statementFormat =
          StatementFormat.createSystemPdfFormat("Test Bank - PDF", "Test Bank", "USD");

      var saved = statementFormatRepository.save(statementFormat);

      assertThat(saved.getId()).isNotNull();
      assertThat(saved.getFormatType()).isEqualTo(FormatType.PDF);
      assertThat(saved.getScope()).isEqualTo(StatementFormatScope.SYSTEM);
      assertThat(saved.getOwnerId()).isNull();
    }
  }

  @Nested
  class Visibility {

    @Test
    void visibleFormatsIncludeSystemAndOwnedUserFormats() {
      var ownedFormat =
          statementFormatRepository.save(
              StatementFormat.createCsvFormat("Owned Format", "Owned Bank", "USD", "usr_owner"));
      var otherFormat =
          statementFormatRepository.save(
              StatementFormat.createCsvFormat("Other Format", "Other Bank", "USD", "usr_other"));

      var result = statementFormatRepository.findVisibleToUser("usr_owner");

      assertThat(result).contains(ownedFormat);
      assertThat(result).doesNotContain(otherFormat);
      assertThat(result)
          .extracting(StatementFormat::getScope)
          .contains(StatementFormatScope.SYSTEM);
    }

    @Test
    void enabledVisibleLookupExcludesDisabledFormats() {
      var disabledFormat =
          StatementFormat.createCsvFormat("Disabled Format", "Bank", "USD", "usr_owner");
      disabledFormat.setEnabled(false);
      var saved = statementFormatRepository.save(disabledFormat);

      var result = statementFormatRepository.findEnabledVisibleToUser(saved.getId(), "usr_owner");

      assertThat(result).isEmpty();
    }
  }

  @Nested
  class MigrationSeededFormats {

    @Test
    void seededFormatsAreSystemScopedAndVisible() {
      var formats = statementFormatRepository.findByEnabledTrue();

      assertThat(formats)
          .extracting(StatementFormat::getDisplayName)
          .containsExactlyInAnyOrder(
              "Bangkok Bank - Statement",
              "Bangkok Bank - Statement PDF",
              "Capital One Credit - Monthly Statement",
              "Capital One Credit - Yearly Statement",
              "Capital One Bank - Monthly Statement");
      assertThat(formats)
          .extracting(StatementFormat::getScope)
          .containsOnly(StatementFormatScope.SYSTEM);
      assertThat(formats).extracting(StatementFormat::getOwnerId).containsOnlyNulls();
    }

    @Test
    void seededCsvFormatHasParserRevisionConfiguration() {
      var csvFormats = statementFormatRepository.findByFormatTypeAndEnabledTrue(FormatType.CSV);

      assertThat(csvFormats)
          .extracting(StatementFormat::getDisplayName)
          .contains("Bangkok Bank - Statement");
      var parserRevisions =
          parserRevisionRepository.findByParserTypeAndEnabledTrue(ParserType.CSV_COLUMN_CONFIG);

      assertThat(parserRevisions).hasSize(1);
      assertThat(parserRevisions.getFirst().getParserConfig()).contains("\"dateHeader\":\"Date\"");
      assertThat(parserRevisions.getFirst().getStatementFormat().getDisplayName())
          .isEqualTo("Bangkok Bank - Statement");
    }

    @Test
    void seededPdfFormatsHaveStaticHandlerParserRevisions() {
      var parserRevisions =
          parserRevisionRepository.findByParserTypeAndEnabledTrue(ParserType.STATIC_HANDLER);

      assertThat(parserRevisions).hasSize(4);
      assertThat(parserRevisions)
          .extracting(parserRevision -> parserRevision.getStatementFormat().getDisplayName())
          .containsExactlyInAnyOrder(
              "Bangkok Bank - Statement PDF",
              "Capital One Credit - Monthly Statement",
              "Capital One Credit - Yearly Statement",
              "Capital One Bank - Monthly Statement");
      assertThat(parserRevisions).extracting("handlerKey").doesNotContainNull();
    }
  }
}
