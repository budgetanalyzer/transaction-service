package org.budgetanalyzer.transaction.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.groups.Tuple.tuple;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import org.budgetanalyzer.service.security.test.TestClaimsSecurityConfig;
import org.budgetanalyzer.transaction.domain.SavedView;
import org.budgetanalyzer.transaction.domain.Transaction;
import org.budgetanalyzer.transaction.domain.TransactionType;

@SpringBootTest
@Testcontainers
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(TestClaimsSecurityConfig.class)
class SavedViewTransactionRepositoryIntegrationTest {

  private static final String USER_ID = "repository-test-user";
  private static final int LARGE_MEMBERSHIP_SIZE = 10_000;

  @Container
  private static final PostgreSQLContainer<?> POSTGRESQL_CONTAINER =
      new PostgreSQLContainer<>("postgres:17-alpine")
          .withDatabaseName("saved_view_repository_test")
          .withUsername("test")
          .withPassword("test");

  @Autowired private SavedViewRepository savedViewRepository;
  @Autowired private SavedViewTransactionRepository savedViewTransactionRepository;
  @Autowired private TransactionRepository transactionRepository;
  @Autowired private JdbcTemplate jdbcTemplate;

  @DynamicPropertySource
  static void configureProperties(DynamicPropertyRegistry dynamicPropertyRegistry) {
    dynamicPropertyRegistry.add("spring.datasource.url", POSTGRESQL_CONTAINER::getJdbcUrl);
    dynamicPropertyRegistry.add("spring.datasource.username", POSTGRESQL_CONTAINER::getUsername);
    dynamicPropertyRegistry.add("spring.datasource.password", POSTGRESQL_CONTAINER::getPassword);
    dynamicPropertyRegistry.add(
        "spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
  }

  @BeforeEach
  void cleanDatabase() {
    savedViewRepository.deleteAllInBatch();
    transactionRepository.deleteAllInBatch();
  }

  @Test
  @Transactional
  void readsDeterministicIdsDirectlyFromMembershipTable() {
    var firstTransaction = transactionRepository.save(transaction("First"));
    var secondTransaction = transactionRepository.save(transaction("Second"));
    var populatedView = savedViewRepository.saveAndFlush(savedView("Populated"));
    savedViewTransactionRepository.insertAll(
        populatedView.getId(),
        List.of(secondTransaction.getId(), firstTransaction.getId(), secondTransaction.getId()));

    jdbcTemplate.update(
        "UPDATE transaction SET deleted = true WHERE id = ?", secondTransaction.getId());

    assertThat(savedViewTransactionRepository.findTransactionIds(populatedView.getId()))
        .containsExactly(firstTransaction.getId(), secondTransaction.getId());
  }

  @Test
  @Transactional
  void returnsGroupedCountsDirectlyFromMembershipTable() {
    var firstTransaction = transactionRepository.save(transaction("First"));
    var secondTransaction = transactionRepository.save(transaction("Second"));
    var populatedView = savedViewRepository.saveAndFlush(savedView("Populated"));
    var secondView = savedViewRepository.saveAndFlush(savedView("Second view"));
    final var emptyView = savedViewRepository.saveAndFlush(savedView("Empty"));
    savedViewTransactionRepository.insertAll(
        populatedView.getId(), List.of(firstTransaction.getId(), secondTransaction.getId()));
    savedViewTransactionRepository.insertAll(
        secondView.getId(), List.of(secondTransaction.getId()));

    jdbcTemplate.update(
        "UPDATE transaction SET deleted = true WHERE id = ?", secondTransaction.getId());

    assertThat(
            savedViewTransactionRepository.countByViewIds(
                List.of(populatedView.getId(), secondView.getId(), emptyView.getId())))
        .extracting(
            SavedViewTransactionRepository.SavedViewMembershipCount::getViewId,
            SavedViewTransactionRepository.SavedViewMembershipCount::getTransactionCount)
        .containsExactlyInAnyOrder(tuple(populatedView.getId(), 2L), tuple(secondView.getId(), 1L));
  }

  @Test
  @Transactional
  void primaryKeyRejectsDuplicateMembershipRows() {
    var transaction = transactionRepository.save(transaction("Duplicate"));
    var savedView = savedViewRepository.saveAndFlush(savedView("Duplicate"));
    savedViewTransactionRepository.insertAll(savedView.getId(), List.of(transaction.getId()));

    assertThatThrownBy(
            () ->
                jdbcTemplate.update(
                    "INSERT INTO saved_view_transaction (view_id, transaction_id) VALUES (?, ?)",
                    savedView.getId(),
                    transaction.getId()))
        .isInstanceOf(DataIntegrityViolationException.class);
  }

  @Test
  @Transactional
  void batchInsertReportsOnlyPersistedMembershipChanges() {
    var transaction = transactionRepository.save(transaction("Idempotent"));
    var savedView = savedViewRepository.saveAndFlush(savedView("Idempotent"));

    assertThat(
            savedViewTransactionRepository.insertAll(
                savedView.getId(), List.of(transaction.getId(), transaction.getId())))
        .isOne();
    assertThat(
            savedViewTransactionRepository.insertAll(
                savedView.getId(), List.of(transaction.getId())))
        .isZero();
  }

  @Test
  @Transactional
  void viewDeleteCascadesMembershipRows() {
    var transaction = transactionRepository.save(transaction("Cascade"));
    var savedView = savedViewRepository.saveAndFlush(savedView("Cascade"));
    savedViewTransactionRepository.insertAll(savedView.getId(), List.of(transaction.getId()));

    jdbcTemplate.update("DELETE FROM saved_view WHERE id = ?", savedView.getId());

    assertThat(savedViewTransactionRepository.count()).isZero();
    assertThat(transactionRepository.findById(transaction.getId())).isPresent();
  }

  @Test
  @Transactional
  void transactionForeignKeyRestrictsHardDeleteWhileMembershipExists() {
    var transaction = transactionRepository.save(transaction("Restricted"));
    var savedView = savedViewRepository.saveAndFlush(savedView("Restricted"));
    savedViewTransactionRepository.insertAll(savedView.getId(), List.of(transaction.getId()));

    assertThatThrownBy(
            () -> jdbcTemplate.update("DELETE FROM transaction WHERE id = ?", transaction.getId()))
        .isInstanceOf(DataIntegrityViolationException.class);
  }

  @Test
  @Transactional
  void multiIdLocksReturnSortedUniqueTransactions() {
    var firstTransaction = transactionRepository.save(transaction("First"));
    var secondTransaction = transactionRepository.save(transaction("Second"));
    var thirdTransaction = transactionRepository.saveAndFlush(transaction("Third"));

    var lockedTransactions =
        transactionRepository.lockActiveByOwnerIdAndIdIn(
            USER_ID,
            List.of(
                thirdTransaction.getId(),
                firstTransaction.getId(),
                secondTransaction.getId(),
                firstTransaction.getId()));

    assertThat(lockedTransactions)
        .extracting(Transaction::getId)
        .containsExactly(
            firstTransaction.getId(), secondTransaction.getId(), thirdTransaction.getId());
  }

  @Test
  @Transactional
  void batchInsertAndIndexedCleanupSupportTenThousandMemberships() {
    var transactionIds = insertTransactions(LARGE_MEMBERSHIP_SIZE);
    var savedView = savedViewRepository.saveAndFlush(savedView("Large"));

    savedViewTransactionRepository.insertAll(savedView.getId(), transactionIds.reversed());
    jdbcTemplate.execute("ANALYZE saved_view_transaction");
    var cleanupPlan =
        String.join(
            "\n",
            jdbcTemplate.queryForList(
                "EXPLAIN (COSTS OFF) DELETE FROM saved_view_transaction "
                    + "WHERE transaction_id = ?",
                String.class,
                transactionIds.getFirst()));

    assertThat(savedViewTransactionRepository.findTransactionIds(savedView.getId()))
        .hasSize(LARGE_MEMBERSHIP_SIZE)
        .startsWith(transactionIds.getFirst())
        .endsWith(transactionIds.getLast());
    assertThat(cleanupPlan).contains("idx_saved_view_transaction_transaction_id");
    assertThat(
            savedViewTransactionRepository.deleteByTransactionIdIn(
                List.of(transactionIds.getLast(), transactionIds.getFirst())))
        .isEqualTo(2);
    assertThat(savedViewTransactionRepository.countByViewId(savedView.getId()))
        .isEqualTo(LARGE_MEMBERSHIP_SIZE - 2L);
  }

  private List<Long> insertTransactions(int count) {
    return jdbcTemplate
        .queryForList(
            """
            INSERT INTO transaction (
                bank_name,
                date,
                currency_iso_code,
                amount,
                type,
                description,
                created_at,
                deleted,
                owner_id
            )
            SELECT
                'Test Bank',
                DATE '2024-01-15',
                'USD',
                4.50,
                'DEBIT',
                'Repository transaction ' || sequence_number,
                CURRENT_TIMESTAMP,
                false,
                ?
            FROM generate_series(1, ?) AS sequence_number
            RETURNING id
            """,
            Long.class,
            USER_ID,
            count)
        .stream()
        .sorted()
        .toList();
  }

  private SavedView savedView(String name) {
    var savedView = new SavedView();
    savedView.setUserId(USER_ID);
    savedView.setName(name);
    return savedView;
  }

  private Transaction transaction(String description) {
    var transaction = new Transaction();
    transaction.setOwnerId(USER_ID);
    transaction.setDescription(description);
    transaction.setAmount(new BigDecimal("4.50"));
    transaction.setDate(LocalDate.of(2024, 1, 15));
    transaction.setType(TransactionType.DEBIT);
    transaction.setBankName("Test Bank");
    transaction.setCurrencyIsoCode("USD");
    return transaction;
  }
}
