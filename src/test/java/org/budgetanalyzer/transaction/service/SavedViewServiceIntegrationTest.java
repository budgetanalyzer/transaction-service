package org.budgetanalyzer.transaction.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDate;

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
import org.budgetanalyzer.transaction.domain.Transaction;
import org.budgetanalyzer.transaction.domain.TransactionType;
import org.budgetanalyzer.transaction.domain.ViewCriteria;
import org.budgetanalyzer.transaction.repository.SavedViewRepository;
import org.budgetanalyzer.transaction.repository.TransactionRepository;
import org.budgetanalyzer.transaction.service.dto.SavedViewCommand;

@SpringBootTest
@Testcontainers
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(TestClaimsSecurityConfig.class)
class SavedViewServiceIntegrationTest {

  private static final String USER_ID = "test-user";

  @Container
  private static final PostgreSQLContainer<?> postgres =
      new PostgreSQLContainer<>("postgres:17-alpine")
          .withDatabaseName("testdb")
          .withUsername("test")
          .withPassword("test");

  @Autowired private SavedViewService savedViewService;

  @Autowired private SavedViewRepository savedViewRepository;

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
    savedViewRepository.deleteAllInBatch();
    transactionRepository.deleteAllInBatch();
  }

  @Test
  void getViewTransactions_debitCriteriaExcludesCreditTransactions() {
    var debitTransaction =
        transactionRepository.save(
            createTransaction(
                "Debit transaction", LocalDate.of(2024, 12, 15), TransactionType.DEBIT));
    transactionRepository.save(
        createTransaction(
            "Credit transaction", LocalDate.of(2024, 12, 15), TransactionType.CREDIT));

    var criteria =
        new ViewCriteria(null, null, null, null, null, null, null, TransactionType.DEBIT, null);
    var view =
        savedViewService.createView(USER_ID, new SavedViewCommand("Debits", criteria, false));

    var membership = savedViewService.getViewTransactions(view.getId(), USER_ID);

    assertThat(membership.matched()).containsExactly(debitTransaction.getId());
  }

  @Test
  void getViewTransactions_mapsDateFromAndDateToIntoTransactionFiltering() {
    transactionRepository.save(
        createTransaction("Before range", LocalDate.of(2024, 11, 30), TransactionType.DEBIT));
    var inRangeTransaction =
        transactionRepository.save(
            createTransaction("In range", LocalDate.of(2024, 12, 15), TransactionType.DEBIT));
    transactionRepository.save(
        createTransaction("After range", LocalDate.of(2025, 1, 1), TransactionType.DEBIT));

    var criteria =
        new ViewCriteria(
            LocalDate.of(2024, 12, 1),
            LocalDate.of(2024, 12, 31),
            null,
            null,
            null,
            null,
            null,
            null,
            null);
    var view =
        savedViewService.createView(USER_ID, new SavedViewCommand("December", criteria, false));

    var membership = savedViewService.getViewTransactions(view.getId(), USER_ID);

    assertThat(membership.matched()).containsExactly(inRangeTransaction.getId());
  }

  private Transaction createTransaction(
      String description, LocalDate date, TransactionType transactionType) {
    var transaction = new Transaction();
    transaction.setAccountId("checking-12345");
    transaction.setBankName("Capital One");
    transaction.setDate(date);
    transaction.setCurrencyIsoCode("USD");
    transaction.setAmount(BigDecimal.TEN);
    transaction.setType(transactionType);
    transaction.setDescription(description);
    transaction.setOwnerId(USER_ID);
    return transaction;
  }
}
