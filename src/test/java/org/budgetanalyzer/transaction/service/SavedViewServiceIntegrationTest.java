package org.budgetanalyzer.transaction.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import org.budgetanalyzer.service.exception.BusinessException;
import org.budgetanalyzer.service.exception.InvalidRequestException;
import org.budgetanalyzer.service.exception.ResourceNotFoundException;
import org.budgetanalyzer.service.security.test.TestClaimsSecurityConfig;
import org.budgetanalyzer.transaction.domain.Transaction;
import org.budgetanalyzer.transaction.domain.TransactionType;
import org.budgetanalyzer.transaction.repository.SavedViewRepository;
import org.budgetanalyzer.transaction.repository.SavedViewTransactionRepository;
import org.budgetanalyzer.transaction.repository.TransactionRepository;
import org.budgetanalyzer.transaction.service.dto.SavedViewCommand;
import org.budgetanalyzer.transaction.service.dto.SavedViewMembershipDelta;
import org.budgetanalyzer.transaction.service.dto.SavedViewPatch;

@SpringBootTest
@Testcontainers
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(TestClaimsSecurityConfig.class)
class SavedViewServiceIntegrationTest {

  private static final String USER_ID = "test-user";
  private static final String OTHER_USER_ID = "other-user";

  @Container
  private static final PostgreSQLContainer<?> POSTGRESQL_CONTAINER =
      new PostgreSQLContainer<>("postgres:17-alpine")
          .withDatabaseName("saved_view_test")
          .withUsername("test")
          .withPassword("test");

  @Autowired private SavedViewService savedViewService;
  @Autowired private TransactionService transactionService;
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
  void createCanonicalizesMembershipAndAllowsEmptyViews() {
    var firstTransaction = transactionRepository.save(transaction(USER_ID, "First"));
    var secondTransaction = transactionRepository.save(transaction(USER_ID, "Second"));

    var savedViewSummary =
        savedViewService.createView(
            USER_ID,
            new SavedViewCommand(
                "Static",
                List.of(
                    secondTransaction.getId(),
                    firstTransaction.getId(),
                    firstTransaction.getId())));
    var emptySavedViewSummary =
        savedViewService.createView(USER_ID, new SavedViewCommand("Empty", List.of()));

    assertThat(savedViewSummary.transactionCount()).isEqualTo(2);
    assertThat(savedViewService.getViewTransactions(savedViewSummary.savedView().getId(), USER_ID))
        .containsExactly(firstTransaction.getId(), secondTransaction.getId());
    assertThat(emptySavedViewSummary.transactionCount()).isZero();
  }

  @Test
  void createRejectsMissingDeletedAndForeignMembershipWithoutPartialWrites() {
    var ownedTransaction = transactionRepository.save(transaction(USER_ID, "Owned"));
    var foreignTransaction = transactionRepository.save(transaction(OTHER_USER_ID, "Foreign"));
    var deletedTransaction = transactionRepository.save(transaction(USER_ID, "Deleted"));
    deletedTransaction.markDeleted(USER_ID);
    transactionRepository.saveAndFlush(deletedTransaction);

    for (var unavailableTransactionId :
        List.of(foreignTransaction.getId(), deletedTransaction.getId(), Long.MAX_VALUE)) {
      assertThatThrownBy(
              () ->
                  savedViewService.createView(
                      USER_ID,
                      new SavedViewCommand(
                          "Rejected", List.of(ownedTransaction.getId(), unavailableTransactionId))))
          .isInstanceOf(BusinessException.class)
          .extracting(exception -> ((BusinessException) exception).getCode())
          .isEqualTo(BudgetAnalyzerError.SAVED_VIEW_MEMBERSHIP_STALE.name());
    }

    assertThat(savedViewRepository.findAll()).isEmpty();
    assertThat(savedViewTransactionRepository.findAll()).isEmpty();
  }

  @Test
  void membershipDeltaTouchesTimestampOnlyWhenPersistedMembershipChanges() {
    var firstTransaction = transactionRepository.save(transaction(USER_ID, "First"));
    var secondTransaction = transactionRepository.save(transaction(USER_ID, "Second"));
    var savedViewSummary =
        savedViewService.createView(
            USER_ID, new SavedViewCommand("Delta", List.of(firstTransaction.getId())));
    var viewId = savedViewSummary.savedView().getId();
    var oldTimestamp = Instant.parse("2020-01-01T00:00:00Z");
    jdbcTemplate.update(
        "UPDATE saved_view SET updated_at = ? WHERE id = ?", Timestamp.from(oldTimestamp), viewId);

    savedViewService.updateViewTransactions(
        viewId,
        USER_ID,
        new SavedViewMembershipDelta(
            List.of(secondTransaction.getId(), secondTransaction.getId()),
            List.of(firstTransaction.getId(), Long.MAX_VALUE)));

    assertThat(savedViewService.getViewTransactions(viewId, USER_ID))
        .containsExactly(secondTransaction.getId());
    assertThat(savedViewRepository.findById(viewId).orElseThrow().getUpdatedAt())
        .isAfter(oldTimestamp);

    setViewTimestamp(viewId, oldTimestamp);
    savedViewService.updateViewTransactions(
        viewId,
        USER_ID,
        new SavedViewMembershipDelta(List.of(secondTransaction.getId()), List.of(Long.MAX_VALUE)));

    assertThat(savedViewService.getView(viewId, USER_ID).transactionCount()).isEqualTo(1);
    assertViewTimestamp(viewId, oldTimestamp);
  }

  @Test
  void membershipDeltaRejectsOverlapBeforeChangingMembership() {
    var transaction = transactionRepository.save(transaction(USER_ID, "Member"));
    var savedViewSummary =
        savedViewService.createView(
            USER_ID, new SavedViewCommand("Protected", List.of(transaction.getId())));
    var viewId = savedViewSummary.savedView().getId();
    var oldTimestamp = Instant.parse("2020-01-01T00:00:00Z");
    setViewTimestamp(viewId, oldTimestamp);

    assertThatThrownBy(
            () ->
                savedViewService.updateViewTransactions(
                    viewId,
                    USER_ID,
                    new SavedViewMembershipDelta(
                        List.of(transaction.getId()), List.of(transaction.getId()))))
        .isInstanceOf(InvalidRequestException.class);

    assertThat(savedViewService.getViewTransactions(viewId, USER_ID))
        .containsExactly(transaction.getId());
    assertViewTimestamp(viewId, oldTimestamp);
  }

  @Test
  void membershipDeltaRejectsCompleteAddSetForEveryStaleReason() {
    var memberTransaction = transactionRepository.save(transaction(USER_ID, "Member"));
    final var validAddition = transactionRepository.save(transaction(USER_ID, "Valid addition"));
    final var foreignTransaction =
        transactionRepository.save(transaction(OTHER_USER_ID, "Foreign"));
    var deletedTransaction = transactionRepository.save(transaction(USER_ID, "Deleted"));
    deletedTransaction.markDeleted(USER_ID);
    transactionRepository.saveAndFlush(deletedTransaction);
    var savedViewSummary =
        savedViewService.createView(
            USER_ID, new SavedViewCommand("Protected", List.of(memberTransaction.getId())));
    var viewId = savedViewSummary.savedView().getId();
    var oldTimestamp = Instant.parse("2020-01-01T00:00:00Z");
    setViewTimestamp(viewId, oldTimestamp);

    for (var unavailableTransactionId :
        List.of(foreignTransaction.getId(), deletedTransaction.getId(), Long.MAX_VALUE)) {
      assertThatThrownBy(
              () ->
                  savedViewService.updateViewTransactions(
                      viewId,
                      USER_ID,
                      new SavedViewMembershipDelta(
                          List.of(validAddition.getId(), unavailableTransactionId),
                          List.of(memberTransaction.getId()))))
          .isInstanceOf(BusinessException.class)
          .extracting(exception -> ((BusinessException) exception).getCode())
          .isEqualTo(BudgetAnalyzerError.SAVED_VIEW_MEMBERSHIP_STALE.name());
    }

    assertThat(savedViewService.getViewTransactions(viewId, USER_ID))
        .containsExactly(memberTransaction.getId());
    assertViewTimestamp(viewId, oldTimestamp);
  }

  @Test
  void listUsesGroupedCountsAndOwnerScoping() {
    var firstTransaction = transactionRepository.save(transaction(USER_ID, "First"));
    var secondTransaction = transactionRepository.save(transaction(USER_ID, "Second"));
    savedViewService.createView(
        USER_ID,
        new SavedViewCommand("Two", List.of(firstTransaction.getId(), secondTransaction.getId())));
    savedViewService.createView(USER_ID, new SavedViewCommand("Zero", List.of()));
    savedViewService.createView(OTHER_USER_ID, new SavedViewCommand("Foreign", List.of()));

    assertThat(savedViewService.getViewsForUser(USER_ID))
        .extracting(summary -> summary.savedView().getName(), summary -> summary.transactionCount())
        .containsExactlyInAnyOrder(
            org.assertj.core.groups.Tuple.tuple("Two", 2L),
            org.assertj.core.groups.Tuple.tuple("Zero", 0L));
  }

  @Test
  void ownerChecksHideForeignViews() {
    var transaction = transactionRepository.save(transaction(OTHER_USER_ID, "Member"));
    var savedViewSummary =
        savedViewService.createView(
            OTHER_USER_ID, new SavedViewCommand("Foreign", List.of(transaction.getId())));
    var viewId = savedViewSummary.savedView().getId();

    assertThatThrownBy(() -> savedViewService.getView(viewId, USER_ID))
        .isInstanceOf(ResourceNotFoundException.class);
    assertThatThrownBy(
            () -> savedViewService.updateView(viewId, USER_ID, new SavedViewPatch("Nope")))
        .isInstanceOf(ResourceNotFoundException.class);
    assertThatThrownBy(() -> savedViewService.getViewTransactions(viewId, USER_ID))
        .isInstanceOf(ResourceNotFoundException.class);
    assertThatThrownBy(
            () ->
                savedViewService.updateViewTransactions(
                    viewId,
                    USER_ID,
                    new SavedViewMembershipDelta(List.of(), List.of(transaction.getId()))))
        .isInstanceOf(ResourceNotFoundException.class);

    assertThat(savedViewService.getViewTransactions(viewId, OTHER_USER_ID))
        .containsExactly(transaction.getId());
  }

  @Test
  void singleTransactionDeleteCleansEveryMembershipWithoutTouchingViews() {
    var deletedTransaction = transactionRepository.save(transaction(USER_ID, "Deleted"));
    var retainedTransaction = transactionRepository.save(transaction(USER_ID, "Retained"));
    var firstViewSummary =
        savedViewService.createView(
            USER_ID,
            new SavedViewCommand(
                "First view", List.of(deletedTransaction.getId(), retainedTransaction.getId())));
    var secondViewSummary =
        savedViewService.createView(
            USER_ID, new SavedViewCommand("Second view", List.of(deletedTransaction.getId())));
    var oldTimestamp = Instant.parse("2020-01-01T00:00:00Z");
    setViewTimestamp(firstViewSummary.savedView().getId(), oldTimestamp);
    setViewTimestamp(secondViewSummary.savedView().getId(), oldTimestamp);

    transactionService.deleteTransaction(deletedTransaction.getId(), USER_ID, false);

    assertThat(savedViewService.getViewTransactions(firstViewSummary.savedView().getId(), USER_ID))
        .containsExactly(retainedTransaction.getId());
    assertThat(savedViewService.getViewTransactions(secondViewSummary.savedView().getId(), USER_ID))
        .isEmpty();
    assertViewTimestamp(firstViewSummary.savedView().getId(), oldTimestamp);
    assertViewTimestamp(secondViewSummary.savedView().getId(), oldTimestamp);

    savedViewService.updateViewTransactions(
        secondViewSummary.savedView().getId(),
        USER_ID,
        new SavedViewMembershipDelta(List.of(), List.of(deletedTransaction.getId())));

    assertViewTimestamp(secondViewSummary.savedView().getId(), oldTimestamp);
  }

  @Test
  void bulkTransactionDeleteCleansMembershipsSetWiseWithoutTouchingViews() {
    var firstDeletedTransaction = transactionRepository.save(transaction(USER_ID, "First"));
    var secondDeletedTransaction = transactionRepository.save(transaction(USER_ID, "Second"));
    var retainedTransaction = transactionRepository.save(transaction(USER_ID, "Retained"));
    var firstViewSummary =
        savedViewService.createView(
            USER_ID,
            new SavedViewCommand(
                "First view",
                List.of(
                    firstDeletedTransaction.getId(),
                    secondDeletedTransaction.getId(),
                    retainedTransaction.getId())));
    var secondViewSummary =
        savedViewService.createView(
            USER_ID,
            new SavedViewCommand(
                "Second view",
                List.of(firstDeletedTransaction.getId(), secondDeletedTransaction.getId())));
    var oldTimestamp = Instant.parse("2020-01-01T00:00:00Z");
    setViewTimestamp(firstViewSummary.savedView().getId(), oldTimestamp);
    setViewTimestamp(secondViewSummary.savedView().getId(), oldTimestamp);

    var result =
        transactionService.bulkDeleteTransactions(
            List.of(secondDeletedTransaction.getId(), firstDeletedTransaction.getId()),
            USER_ID,
            false);

    assertThat(result.deletedCount()).isEqualTo(2);
    assertThat(savedViewService.getViewTransactions(firstViewSummary.savedView().getId(), USER_ID))
        .containsExactly(retainedTransaction.getId());
    assertThat(savedViewService.getViewTransactions(secondViewSummary.savedView().getId(), USER_ID))
        .isEmpty();
    assertViewTimestamp(firstViewSummary.savedView().getId(), oldTimestamp);
    assertViewTimestamp(secondViewSummary.savedView().getId(), oldTimestamp);
  }

  @Test
  void viewDeleteCascadesMemberships() {
    var transaction = transactionRepository.save(transaction(USER_ID, "Member"));
    var savedViewSummary =
        savedViewService.createView(
            USER_ID, new SavedViewCommand("Cascade", List.of(transaction.getId())));

    savedViewService.deleteView(savedViewSummary.savedView().getId(), USER_ID);

    assertThat(savedViewTransactionRepository.findAll()).isEmpty();
    assertThat(transactionRepository.findById(transaction.getId())).isPresent();
  }

  @Test
  void addVersusDeleteRaceNeverLeavesDeletedTransactionMembership() throws Exception {
    var transaction = transactionRepository.save(transaction(USER_ID, "Raced"));
    var savedViewSummary =
        savedViewService.createView(USER_ID, new SavedViewCommand("Race", List.of()));
    var viewId = savedViewSummary.savedView().getId();
    var startLatch = new CountDownLatch(1);

    try (var executorService = Executors.newFixedThreadPool(2)) {
      var addFuture =
          executorService.submit(
              () -> {
                startLatch.await();
                try {
                  savedViewService.updateViewTransactions(
                      viewId,
                      USER_ID,
                      new SavedViewMembershipDelta(List.of(transaction.getId()), List.of()));
                  return null;
                } catch (BusinessException businessException) {
                  return businessException;
                }
              });
      var deleteFuture =
          executorService.submit(
              () -> {
                startLatch.await();
                transactionService.deleteTransaction(transaction.getId(), USER_ID, false);
                return null;
              });
      startLatch.countDown();

      var addFailure = addFuture.get(30, TimeUnit.SECONDS);
      assertThat(deleteFuture.get(30, TimeUnit.SECONDS)).isNull();
      if (addFailure != null) {
        assertThat(addFailure.getCode())
            .isEqualTo(BudgetAnalyzerError.SAVED_VIEW_MEMBERSHIP_STALE.name());
      }
    }

    assertThat(transactionRepository.findByIdNotDeleted(transaction.getId())).isEmpty();
    assertThat(savedViewTransactionRepository.findTransactionIds(viewId)).isEmpty();
  }

  @Test
  void createSupportsTenThousandMembershipIds() {
    var transactions = new ArrayList<Transaction>(10_000);
    for (var index = 0; index < 10_000; index++) {
      transactions.add(transaction(USER_ID, "Transaction " + index));
    }
    var savedTransactions = transactionRepository.saveAll(transactions);
    var transactionIds = savedTransactions.stream().map(Transaction::getId).toList();

    var savedViewSummary =
        savedViewService.createView(USER_ID, new SavedViewCommand("Large", transactionIds));

    assertThat(savedViewSummary.transactionCount()).isEqualTo(10_000);
    assertThat(savedViewTransactionRepository.countByViewId(savedViewSummary.savedView().getId()))
        .isEqualTo(10_000);
  }

  private void setViewTimestamp(UUID viewId, Instant timestamp) {
    jdbcTemplate.update(
        "UPDATE saved_view SET updated_at = ? WHERE id = ?", Timestamp.from(timestamp), viewId);
  }

  private void assertViewTimestamp(UUID viewId, Instant expectedTimestamp) {
    assertThat(savedViewRepository.findById(viewId).orElseThrow().getUpdatedAt())
        .isEqualTo(expectedTimestamp);
  }

  private Transaction transaction(String ownerId, String description) {
    var transaction = new Transaction();
    transaction.setOwnerId(ownerId);
    transaction.setDescription(description);
    transaction.setAmount(new BigDecimal("4.50"));
    transaction.setDate(LocalDate.of(2024, 1, 15));
    transaction.setType(TransactionType.DEBIT);
    transaction.setBankName("Test Bank");
    transaction.setCurrencyIsoCode("USD");
    return transaction;
  }
}
