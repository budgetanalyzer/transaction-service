package org.budgetanalyzer.transaction.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDate;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import org.budgetanalyzer.transaction.domain.Transaction;
import org.budgetanalyzer.transaction.domain.TransactionType;

@DataJpaTest
@Testcontainers
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class TransactionRepositoryIntegrationTest {

  @Container
  private static final PostgreSQLContainer<?> postgres =
      new PostgreSQLContainer<>("postgres:17-alpine")
          .withDatabaseName("testdb")
          .withUsername("test")
          .withPassword("test");

  @Autowired private TransactionRepository transactionRepository;

  @DynamicPropertySource
  static void configureProperties(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", postgres::getJdbcUrl);
    registry.add("spring.datasource.username", postgres::getUsername);
    registry.add("spring.datasource.password", postgres::getPassword);
    registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
  }

  // ==================== Create (Save) ====================

  @Test
  void save_newTransaction_persistsToDatabase() {
    // Given: a new transaction
    var transaction = createTransaction("Grocery Store", BigDecimal.valueOf(45.50));

    // When: save is called
    var saved = transactionRepository.save(transaction);

    // Then: transaction is persisted with generated ID
    assertThat(saved.getId()).isNotNull();
    assertThat(saved.getDescription()).isEqualTo("Grocery Store");
    assertThat(saved.getAmount()).isEqualByComparingTo(BigDecimal.valueOf(45.50));
    assertThat(saved.getType()).isEqualTo(TransactionType.DEBIT);
    assertThat(saved.getBankName()).isEqualTo("Test Bank");
    assertThat(saved.getCurrencyIsoCode()).isEqualTo("USD");
  }

  // ==================== Read (Find) ====================

  @Test
  void findById_existingTransaction_returnsTransaction() {
    // Given: a transaction exists
    var transaction =
        transactionRepository.save(createTransaction("Coffee Shop", BigDecimal.valueOf(4.50)));
    var id = transaction.getId();

    // When: findById is called
    var found = transactionRepository.findById(id);

    // Then: transaction is found
    assertThat(found).isPresent();
    assertThat(found.get().getId()).isEqualTo(id);
    assertThat(found.get().getDescription()).isEqualTo("Coffee Shop");
  }

  @Test
  void findByIdActive_existingNonDeletedTransaction_returnsTransaction() {
    // Given: a non-deleted transaction exists
    var transaction =
        transactionRepository.save(createTransaction("Restaurant", BigDecimal.valueOf(75.00)));
    var id = transaction.getId();

    // When: findByIdActive is called
    var found = transactionRepository.findByIdActive(id);

    // Then: transaction is found
    assertThat(found).isPresent();
    assertThat(found.get().getId()).isEqualTo(id);
    assertThat(found.get().getDescription()).isEqualTo("Restaurant");
  }

  @Test
  void findByIdActive_deletedTransaction_returnsEmpty() {
    // Given: a deleted transaction exists
    var transaction =
        transactionRepository.save(createTransaction("To Delete", BigDecimal.valueOf(100.00)));
    transaction.markDeleted("test-user");
    transactionRepository.save(transaction);
    var id = transaction.getId();

    // When: findByIdActive is called
    var found = transactionRepository.findByIdActive(id);

    // Then: transaction is not found (because it's deleted)
    assertThat(found).isEmpty();
  }

  // ==================== Update ====================

  @Test
  void save_updateExistingTransaction_persistsChanges() {
    // Given: an existing transaction
    var transaction =
        transactionRepository.save(createTransaction("Old Description", BigDecimal.valueOf(50.00)));
    var id = transaction.getId();

    // When: update and save
    transaction.setDescription("New Description");
    transaction.setAccountId("new-account-123");
    var updated = transactionRepository.save(transaction);

    // Then: changes are persisted
    assertThat(updated.getId()).isEqualTo(id);
    assertThat(updated.getDescription()).isEqualTo("New Description");
    assertThat(updated.getAccountId()).isEqualTo("new-account-123");

    // Verify by re-fetching from database
    var refetched = transactionRepository.findById(id);
    assertThat(refetched).isPresent();
    assertThat(refetched.get().getDescription()).isEqualTo("New Description");
    assertThat(refetched.get().getAccountId()).isEqualTo("new-account-123");
  }

  // ==================== Soft Delete ====================

  @Test
  void softDelete_marksTransactionAsDeleted() {
    // Given: a transaction exists
    var transaction =
        transactionRepository.save(createTransaction("To Soft Delete", BigDecimal.valueOf(25.00)));
    final var id = transaction.getId();

    // When: markDeleted and save
    transaction.markDeleted("admin-user");
    transactionRepository.save(transaction);

    // Then: transaction is marked as deleted
    assertThat(transaction.isDeleted()).isTrue();
    assertThat(transaction.getDeletedBy()).isEqualTo("admin-user");
    assertThat(transaction.getDeletedAt()).isNotNull();

    // And: findByIdActive returns empty
    var found = transactionRepository.findByIdActive(id);
    assertThat(found).isEmpty();

    // But: findById still returns the transaction (soft delete)
    var foundById = transactionRepository.findById(id);
    assertThat(foundById).isPresent();
    assertThat(foundById.get().isDeleted()).isTrue();
  }

  @Test
  void findAllActive_excludesSoftDeletedTransactions() {
    // Given: 3 transactions, one is deleted
    transactionRepository.save(createTransaction("Active 1", BigDecimal.valueOf(10.00)));
    transactionRepository.save(createTransaction("Active 2", BigDecimal.valueOf(20.00)));

    var deleted =
        transactionRepository.save(createTransaction("Deleted", BigDecimal.valueOf(30.00)));
    deleted.markDeleted("test-user");
    transactionRepository.save(deleted);

    // When: findAllActive is called
    var activeTransactions = transactionRepository.findAllActive();

    // Then: only non-deleted transactions are returned
    assertThat(activeTransactions).hasSize(2);
    assertThat(activeTransactions)
        .extracting(Transaction::getDescription)
        .containsExactlyInAnyOrder("Active 1", "Active 2");
    assertThat(activeTransactions)
        .extracting(Transaction::getDescription)
        .doesNotContain("Deleted");
  }

  // ==================== Helper Methods ====================

  private Transaction createTransaction(String description, BigDecimal amount) {
    var transaction = new Transaction();
    transaction.setAccountId("test-account");
    transaction.setBankName("Test Bank");
    transaction.setDate(LocalDate.now());
    transaction.setCurrencyIsoCode("USD");
    transaction.setAmount(amount);
    transaction.setType(TransactionType.DEBIT);
    transaction.setDescription(description);
    return transaction;
  }
}
