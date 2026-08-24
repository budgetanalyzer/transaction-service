package org.budgetanalyzer.transaction.api;

import java.math.BigDecimal;
import java.time.LocalDate;

import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;

import org.budgetanalyzer.service.security.test.TestClaimsSecurityConfig;
import org.budgetanalyzer.transaction.domain.ParserRevision;
import org.budgetanalyzer.transaction.domain.SavedView;
import org.budgetanalyzer.transaction.domain.StatementFormat;
import org.budgetanalyzer.transaction.domain.Transaction;
import org.budgetanalyzer.transaction.domain.TransactionType;
import org.budgetanalyzer.transaction.repository.FileImportRepository;
import org.budgetanalyzer.transaction.repository.ParserRevisionRepository;
import org.budgetanalyzer.transaction.repository.SavedViewRepository;
import org.budgetanalyzer.transaction.repository.SavedViewTransactionRepository;
import org.budgetanalyzer.transaction.repository.StatementFormatRepository;
import org.budgetanalyzer.transaction.repository.StatementFormatUserPreferenceRepository;
import org.budgetanalyzer.transaction.repository.TransactionRepository;

@SpringBootTest
@AutoConfigureMockMvc
@Import(TestClaimsSecurityConfig.class)
abstract class ControllerIntegrationTestSupport {

  protected static final String USER_ID = "usr_test123";
  protected static final String OTHER_USER_ID = "usr_other789";
  protected static final String ADMIN_USER_ID = "usr_admin456";

  private static final PostgreSQLContainer<?> POSTGRESQL_CONTAINER = startPostgresqlContainer();

  @Autowired protected MockMvc mockMvc;
  @Autowired protected TransactionRepository transactionRepository;
  @Autowired protected SavedViewRepository savedViewRepository;
  @Autowired protected SavedViewTransactionRepository savedViewTransactionRepository;
  @Autowired protected StatementFormatRepository statementFormatRepository;
  @Autowired protected ParserRevisionRepository parserRevisionRepository;

  @Autowired private FileImportRepository fileImportRepository;

  @Autowired
  private StatementFormatUserPreferenceRepository statementFormatUserPreferenceRepository;

  @DynamicPropertySource
  static void configureDatasource(DynamicPropertyRegistry dynamicPropertyRegistry) {
    dynamicPropertyRegistry.add("spring.datasource.url", POSTGRESQL_CONTAINER::getJdbcUrl);
    dynamicPropertyRegistry.add("spring.datasource.username", POSTGRESQL_CONTAINER::getUsername);
    dynamicPropertyRegistry.add("spring.datasource.password", POSTGRESQL_CONTAINER::getPassword);
    dynamicPropertyRegistry.add(
        "spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
  }

  @BeforeEach
  void resetPersistedTestState() {
    savedViewRepository.deleteAllInBatch();
    transactionRepository.deleteAllInBatch();
    fileImportRepository.deleteAllInBatch();
    statementFormatUserPreferenceRepository.deleteAllInBatch();
    parserRevisionRepository.deleteAllInBatch();
    statementFormatRepository.deleteAllInBatch();
  }

  protected Transaction persistTransaction(String ownerId, String description) {
    var transaction = new Transaction();
    transaction.setOwnerId(ownerId);
    transaction.setDescription(description);
    transaction.setAmount(new BigDecimal("4.50"));
    transaction.setDate(LocalDate.of(2024, 1, 15));
    transaction.setType(TransactionType.DEBIT);
    transaction.setBankName("Test Bank");
    transaction.setCurrencyIsoCode("USD");
    return transactionRepository.save(transaction);
  }

  protected SavedView persistSavedView(String userId) {
    var savedView = new SavedView();
    savedView.setUserId(userId);
    savedView.setName("Test View");
    return savedViewRepository.save(savedView);
  }

  protected ParserRevision persistCsvStatementFormat(String ownerId) {
    var statementFormat =
        statementFormatRepository.save(
            StatementFormat.createCsvFormat("Test CSV", "Test Bank", "USD", ownerId));
    var parserRevision =
        ParserRevision.createCsvColumnConfig(
            statementFormat,
            1,
            """
            {
              "dateHeader": "Date",
              "dateFormat": "uuuu-MM-dd",
              "descriptionHeader": "Description",
              "creditHeader": "Amount",
              "debitHeader": "Amount",
              "typeHeader": "Type",
              "categoryHeader": null
            }
            """);
    return parserRevisionRepository.save(parserRevision);
  }

  private static PostgreSQLContainer<?> startPostgresqlContainer() {
    var postgresqlContainer =
        new PostgreSQLContainer<>("postgres:17-alpine")
            .withDatabaseName("transaction_controller_integration")
            .withUsername("test")
            .withPassword("test");
    postgresqlContainer.start();
    return postgresqlContainer;
  }
}
