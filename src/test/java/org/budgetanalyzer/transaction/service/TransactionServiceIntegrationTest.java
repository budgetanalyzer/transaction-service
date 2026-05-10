package org.budgetanalyzer.transaction.service;

import static org.assertj.core.api.Assertions.assertThat;

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

import org.budgetanalyzer.service.security.test.TestClaimsSecurityConfig;
import org.budgetanalyzer.transaction.domain.TransactionType;
import org.budgetanalyzer.transaction.repository.TransactionRepository;
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

  @DynamicPropertySource
  static void configureProperties(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", postgres::getJdbcUrl);
    registry.add("spring.datasource.username", postgres::getUsername);
    registry.add("spring.datasource.password", postgres::getPassword);
    registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
  }

  @BeforeEach
  void cleanDatabase() {
    transactionRepository.deleteAll();
  }

  @Test
  void batchImport_sameTransactionsSubmittedTwice_skipsSecondSubmission() {
    var transactions =
        List.of(
            previewTransaction(LocalDate.of(2025, 11, 18), "COFFEE SHOP", "9.97"),
            previewTransaction(LocalDate.of(2025, 11, 19), "GROCERY STORE", "42.30"));

    var firstResult = transactionService.batchImport(transactions, USER_ID);
    var secondResult = transactionService.batchImport(transactions, USER_ID);

    assertThat(firstResult.createdTransactions()).hasSize(2);
    assertThat(firstResult.duplicatesSkipped()).isZero();
    assertThat(secondResult.createdTransactions()).isEmpty();
    assertThat(secondResult.duplicatesSkipped()).isEqualTo(2);
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
}
