package org.budgetanalyzer.transaction.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import org.budgetanalyzer.service.exception.BusinessException;
import org.budgetanalyzer.service.security.test.TestClaimsSecurityConfig;
import org.budgetanalyzer.transaction.domain.TransactionType;
import org.budgetanalyzer.transaction.repository.FileImportRepository;
import org.budgetanalyzer.transaction.repository.TransactionRepository;
import org.budgetanalyzer.transaction.service.dto.BatchFileImportSource;
import org.budgetanalyzer.transaction.service.dto.PreviewTransaction;

@SpringBootTest
@Testcontainers
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(TestClaimsSecurityConfig.class)
class TransactionServiceIntegrationTest {

  private static final String USER_ID = "test-user";

  @Container
  private static final PostgreSQLContainer<?> postgres =
      new PostgreSQLContainer<>("postgres:17-alpine")
          .withDatabaseName("testdb")
          .withUsername("test")
          .withPassword("test");

  @Autowired private TransactionService transactionService;

  @Autowired private TransactionRepository transactionRepository;

  @Autowired private FileImportRepository fileImportRepository;

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
  void batchImport_sameTransactionsSubmittedTwice_rejectsSecondSubmission() {
    var transactions =
        List.of(
            previewTransaction(LocalDate.of(2025, 11, 18), "COFFEE SHOP", "9.97"),
            previewTransaction(LocalDate.of(2025, 11, 19), "GROCERY STORE", "42.30"));

    var fileImportSource = fileImportSource("statement-duplicates.csv");
    var firstResult = transactionService.batchImport(transactions, USER_ID, fileImportSource);

    assertThat(firstResult.createdTransactions()).hasSize(2);
    assertThat(firstResult.duplicatesSkipped()).isZero();
    assertThat(firstResult.duplicatesImported()).isZero();
    assertThatThrownBy(
            () -> transactionService.batchImport(transactions, USER_ID, fileImportSource))
        .isInstanceOf(BusinessException.class)
        .satisfies(
            exception -> {
              var businessException = (BusinessException) exception;
              assertThat(businessException.getCode())
                  .isEqualTo(BudgetAnalyzerError.BATCH_IMPORT_NO_TRANSACTIONS_CREATED.name());
            });
    assertThat(transactionRepository.findAll()).hasSize(2);
  }

  @Test
  void batchImport_duplicateSubmittedWithOverride_importsDuplicate() {
    var transaction = previewTransaction(LocalDate.of(2025, 11, 18), "COFFEE SHOP", "9.97");
    var duplicate =
        new PreviewTransaction(
            transaction.date(),
            transaction.description(),
            transaction.amount(),
            transaction.type(),
            transaction.category(),
            transaction.bankName(),
            transaction.currencyIsoCode(),
            transaction.accountId(),
            true);

    var fileImportSource = fileImportSource("statement-override.csv");
    transactionService.batchImport(List.of(transaction), USER_ID, fileImportSource);
    var result = transactionService.batchImport(List.of(duplicate), USER_ID, fileImportSource);

    assertThat(result.createdTransactions()).hasSize(1);
    assertThat(result.duplicatesSkipped()).isZero();
    assertThat(result.duplicatesImported()).isEqualTo(1);
    assertThat(transactionRepository.findAll()).hasSize(2);
  }

  private PreviewTransaction previewTransaction(LocalDate date, String description, String amount) {
    return new PreviewTransaction(
        date,
        description,
        new BigDecimal(amount),
        TransactionType.DEBIT,
        null,
        "Capital One",
        "USD",
        "capital-one-credit");
  }

  private BatchFileImportSource fileImportSource(String originalFilename) {
    return new BatchFileImportSource(
        "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
        originalFilename,
        "capital-one",
        "account-123",
        1024L);
  }
}
