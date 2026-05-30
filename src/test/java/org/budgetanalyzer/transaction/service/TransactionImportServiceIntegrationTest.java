package org.budgetanalyzer.transaction.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;

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

@SpringBootTest
@Testcontainers
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(TestClaimsSecurityConfig.class)
class TransactionImportServiceIntegrationTest {

  private static final String USER_ID = "test-user";

  @Container
  private static final PostgreSQLContainer<?> postgres =
      new PostgreSQLContainer<>("postgres:17-alpine")
          .withDatabaseName("testdb")
          .withUsername("test")
          .withPassword("test");

  @Autowired private TransactionImportService transactionImportService;

  @Autowired private PreviewImportTokenService previewImportTokenService;

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
}
