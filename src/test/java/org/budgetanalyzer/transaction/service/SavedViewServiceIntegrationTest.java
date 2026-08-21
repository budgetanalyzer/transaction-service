package org.budgetanalyzer.transaction.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

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

import org.budgetanalyzer.service.exception.ResourceNotFoundException;
import org.budgetanalyzer.service.security.test.TestClaimsSecurityConfig;
import org.budgetanalyzer.transaction.domain.Transaction;
import org.budgetanalyzer.transaction.domain.TransactionType;
import org.budgetanalyzer.transaction.domain.ViewCriteria;
import org.budgetanalyzer.transaction.repository.SavedViewRepository;
import org.budgetanalyzer.transaction.repository.TransactionRepository;
import org.budgetanalyzer.transaction.service.dto.SavedViewCommand;
import org.budgetanalyzer.transaction.service.dto.SavedViewPatch;

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

  @Autowired private TransactionService transactionService;

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
  void createUpdateReadAndDeletePersistOwnedViewState() {
    var initialCriteria =
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
    var created =
        savedViewService.createView(
            USER_ID, new SavedViewCommand("December", initialCriteria, false));

    var persisted = savedViewRepository.findById(created.getId()).orElseThrow();
    assertThat(persisted.getUserId()).isEqualTo(USER_ID);
    assertThat(persisted.getName()).isEqualTo("December");
    assertThat(persisted.getCriteria().dateFrom()).isEqualTo(LocalDate.of(2024, 12, 1));
    assertThat(persisted.isOpenEnded()).isFalse();
    assertThat(savedViewService.getView(created.getId(), USER_ID).getId())
        .isEqualTo(created.getId());
    assertThatThrownBy(() -> savedViewService.getView(created.getId(), "other-user"))
        .isInstanceOf(ResourceNotFoundException.class);

    var updatedCriteria =
        new ViewCriteria(
            LocalDate.of(2025, 1, 1),
            LocalDate.of(2025, 1, 31),
            null,
            null,
            null,
            null,
            null,
            TransactionType.CREDIT,
            null);
    var updated =
        savedViewService.updateView(
            created.getId(), USER_ID, new SavedViewPatch("January credits", updatedCriteria, true));

    assertThat(updated.getName()).isEqualTo("January credits");
    assertThat(updated.getCriteria().type()).isEqualTo(TransactionType.CREDIT);
    assertThat(updated.isOpenEnded()).isTrue();
    var persistedUpdate = savedViewRepository.findById(created.getId()).orElseThrow();
    assertThat(persistedUpdate.getName()).isEqualTo("January credits");
    assertThat(persistedUpdate.getCriteria().dateFrom()).isEqualTo(LocalDate.of(2025, 1, 1));

    savedViewService.deleteView(created.getId(), USER_ID);

    assertThat(savedViewRepository.findById(created.getId())).isEmpty();
  }

  @Test
  void saveViewAsRetainsOnlySourcePinsMatchingChangedTextFilter() {
    var sourceCriteria =
        new ViewCriteria(
            LocalDate.of(2025, 1, 1),
            LocalDate.of(2025, 1, 31),
            null,
            null,
            null,
            null,
            null,
            null,
            null);
    var retainedPin =
        transactionRepository.save(
            createTransaction(
                "Coffee pin outside criteria", LocalDate.of(2025, 2, 15), TransactionType.DEBIT));
    var filteredPin =
        transactionRepository.save(
            createTransaction(
                "Groceries pin outside criteria",
                LocalDate.of(2025, 2, 16),
                TransactionType.DEBIT));
    final var ordinaryMatchingTransaction =
        transactionRepository.save(
            createTransaction(
                "Ordinary coffee outside criteria",
                LocalDate.of(2025, 2, 17),
                TransactionType.DEBIT));
    var sourceView =
        savedViewService.createView(USER_ID, new SavedViewCommand("Source", sourceCriteria, false));
    var sourcePinnedIds = new HashSet<>(Set.of(retainedPin.getId(), filteredPin.getId()));
    sourceView.setPinnedIds(sourcePinnedIds);
    savedViewRepository.save(sourceView);
    var targetCriteria =
        new ViewCriteria(
            LocalDate.of(2025, 1, 1),
            LocalDate.of(2025, 1, 31),
            null,
            null,
            null,
            null,
            null,
            null,
            "coffee");
    var targetCommand = new SavedViewCommand("January coffee", targetCriteria, false);

    var targetView = savedViewService.saveViewAs(sourceView.getId(), USER_ID, targetCommand);
    var persistedTarget = savedViewRepository.findById(targetView.getId()).orElseThrow();

    assertThat(savedViewRepository.count()).isEqualTo(2);
    assertThat(targetView.getId()).isNotEqualTo(sourceView.getId());
    assertThat(targetView.getUserId()).isEqualTo(USER_ID);
    assertThat(targetView.getName()).isEqualTo(targetCommand.name());
    assertThat(targetView.getCriteria()).isEqualTo(targetCommand.criteria());
    assertThat(targetView.isOpenEnded()).isEqualTo(targetCommand.openEnded());
    assertThat(persistedTarget.getPinnedIds()).containsExactly(retainedPin.getId());
    assertThat(persistedTarget.getPinnedIds())
        .doesNotContain(filteredPin.getId(), ordinaryMatchingTransaction.getId());

    var targetMembership = savedViewService.getViewTransactions(persistedTarget.getId(), USER_ID);
    assertThat(targetMembership.pinned()).containsExactly(retainedPin.getId());
    assertThat(targetMembership.matched()).doesNotContain(ordinaryMatchingTransaction.getId());

    var persistedSource = savedViewRepository.findById(sourceView.getId()).orElseThrow();
    assertThat(persistedSource.getName()).isEqualTo("Source");
    assertThat(persistedSource.getCriteria()).isEqualTo(sourceCriteria);
    assertThat(persistedSource.isOpenEnded()).isFalse();
    assertThat(persistedSource.getPinnedIds()).containsExactlyInAnyOrderElementsOf(sourcePinnedIds);
  }

  @Test
  void saveViewAsPreservesActivePinsAndEveryStoredExclusionWhenCriteriaAreUnchanged() {
    var criteria =
        new ViewCriteria(null, null, Set.of("checking-12345"), null, null, null, null, null, null);
    var activePinInsideCriteria =
        transactionRepository.save(
            createTransaction(
                "Active pin inside criteria", LocalDate.of(2025, 1, 15), TransactionType.DEBIT));
    var activePinOutsideCriteria =
        transactionRepository.save(
            createTransaction(
                "Active pin outside criteria",
                LocalDate.of(2025, 1, 16),
                TransactionType.DEBIT,
                "savings-67890",
                "Capital One",
                "USD"));
    var deletedPin =
        transactionRepository.save(
            createTransaction("Deleted pin", LocalDate.of(2025, 1, 17), TransactionType.DEBIT));
    transactionService.deleteTransaction(deletedPin.getId(), USER_ID, false);
    var foreignPin =
        transactionRepository.save(
            createTransaction(
                "Foreign pin", LocalDate.of(2025, 1, 18), TransactionType.DEBIT, "other-user"));
    var exclusionInsideCriteria =
        transactionRepository.save(
            createTransaction(
                "Exclusion inside criteria", LocalDate.of(2025, 1, 19), TransactionType.DEBIT));
    var exclusionOutsideCriteria =
        transactionRepository.save(
            createTransaction(
                "Exclusion outside criteria",
                LocalDate.of(2025, 1, 20),
                TransactionType.DEBIT,
                "savings-67890",
                "Capital One",
                "USD"));
    var deletedExclusion =
        transactionRepository.save(
            createTransaction(
                "Deleted historical exclusion", LocalDate.of(2025, 1, 21), TransactionType.DEBIT));
    transactionService.deleteTransaction(deletedExclusion.getId(), USER_ID, false);
    var sourceView =
        savedViewService.createView(USER_ID, new SavedViewCommand("Source", criteria, false));
    var sourcePinnedIds =
        new HashSet<>(
            Set.of(
                activePinInsideCriteria.getId(),
                activePinOutsideCriteria.getId(),
                deletedPin.getId(),
                foreignPin.getId()));
    var sourceExcludedIds =
        new HashSet<>(
            Set.of(
                exclusionInsideCriteria.getId(),
                exclusionOutsideCriteria.getId(),
                deletedExclusion.getId(),
                Long.MAX_VALUE));
    sourceView.setPinnedIds(sourcePinnedIds);
    sourceView.setExcludedIds(sourceExcludedIds);
    savedViewRepository.save(sourceView);

    var savedTargetView =
        savedViewService.saveViewAs(
            sourceView.getId(), USER_ID, new SavedViewCommand("Copy", criteria, false));

    var persistedTarget = savedViewRepository.findById(savedTargetView.getId()).orElseThrow();
    assertThat(persistedTarget.getPinnedIds())
        .containsExactlyInAnyOrder(
            activePinInsideCriteria.getId(), activePinOutsideCriteria.getId());
    assertThat(persistedTarget.getPinnedIds())
        .doesNotContain(deletedPin.getId(), foreignPin.getId());
    assertThat(persistedTarget.getExcludedIds())
        .containsExactlyInAnyOrderElementsOf(sourceExcludedIds);

    var broadenedTarget =
        savedViewService.updateView(
            persistedTarget.getId(), USER_ID, new SavedViewPatch(null, ViewCriteria.empty(), null));
    var broadenedMembership = savedViewService.resolveView(broadenedTarget).membership();
    assertThat(broadenedMembership.excluded())
        .containsExactlyInAnyOrder(
            exclusionInsideCriteria.getId(), exclusionOutsideCriteria.getId());
    assertThat(broadenedMembership.matched())
        .doesNotContain(exclusionInsideCriteria.getId(), exclusionOutsideCriteria.getId());
    assertThat(broadenedMembership.pinned()).doesNotContain(exclusionOutsideCriteria.getId());

    var persistedSource = savedViewRepository.findById(sourceView.getId()).orElseThrow();
    assertThat(persistedSource.getPinnedIds()).containsExactlyInAnyOrderElementsOf(sourcePinnedIds);
    assertThat(persistedSource.getExcludedIds())
        .containsExactlyInAnyOrderElementsOf(sourceExcludedIds);
  }

  @Test
  void saveViewAsEnforcesChangedDateBoundsAndKeepsFutureMembershipDynamic() {
    var evaluationDate = LocalDate.now();
    var lowerBound = evaluationDate.minusDays(10);
    var beforeLowerBound =
        transactionRepository.save(
            createTransaction(
                "Before lower bound", lowerBound.minusDays(1), TransactionType.DEBIT));
    var withinBounds =
        transactionRepository.save(
            createTransaction(
                "Within changed bounds", lowerBound.plusDays(1), TransactionType.DEBIT));
    var afterOpenEndedUpperBound =
        transactionRepository.save(
            createTransaction(
                "After open-ended upper bound", evaluationDate.plusDays(2), TransactionType.DEBIT));
    var sourceView =
        savedViewService.createView(
            USER_ID, new SavedViewCommand("Source", ViewCriteria.empty(), false));
    sourceView.setPinnedIds(
        new HashSet<>(
            Set.of(
                beforeLowerBound.getId(), withinBounds.getId(), afterOpenEndedUpperBound.getId())));
    savedViewRepository.save(sourceView);
    var targetCriteria =
        new ViewCriteria(lowerBound, null, null, null, null, null, null, null, null);

    var savedTargetView =
        savedViewService.saveViewAs(
            sourceView.getId(),
            USER_ID,
            new SavedViewCommand("Open-ended copy", targetCriteria, true));

    var persistedTarget = savedViewRepository.findById(savedTargetView.getId()).orElseThrow();
    assertThat(persistedTarget.getPinnedIds()).containsExactly(withinBounds.getId());

    var laterMatchingTransaction =
        transactionRepository.save(
            createTransaction("Created after save-as", evaluationDate, TransactionType.DEBIT));
    var membership = savedViewService.getViewTransactions(persistedTarget.getId(), USER_ID);

    assertThat(membership.matched())
        .containsExactlyInAnyOrder(withinBounds.getId(), laterMatchingTransaction.getId());
    assertThat(membership.matched())
        .doesNotContain(beforeLowerBound.getId(), afterOpenEndedUpperBound.getId());
    assertThat(membership.pinned()).isEmpty();
    assertThat(savedViewRepository.findById(persistedTarget.getId()).orElseThrow().getPinnedIds())
        .containsExactly(withinBounds.getId());
  }

  @Test
  void saveViewAsRejectsMissingAndForeignSourcesWithoutCreatingTargets() {
    var foreignView =
        savedViewService.createView(
            "other-user", new SavedViewCommand("Foreign", ViewCriteria.empty(), false));
    var targetCommand = new SavedViewCommand("Copy", ViewCriteria.empty(), false);

    assertThatThrownBy(
            () -> savedViewService.saveViewAs(foreignView.getId(), USER_ID, targetCommand))
        .isInstanceOf(ResourceNotFoundException.class);
    assertThatThrownBy(() -> savedViewService.saveViewAs(UUID.randomUUID(), USER_ID, targetCommand))
        .isInstanceOf(ResourceNotFoundException.class);

    assertThat(savedViewRepository.count()).isOne();
  }

  @Test
  void pinAndExcludeAreMutuallyExclusiveAndPersisted() {
    var transaction =
        transactionRepository.save(
            createTransaction(
                "Pinned transaction", LocalDate.of(2024, 12, 15), TransactionType.DEBIT));
    var view =
        savedViewService.createView(
            USER_ID, new SavedViewCommand("Overrides", ViewCriteria.empty(), false));

    savedViewService.excludeTransaction(view.getId(), USER_ID, transaction.getId());
    var pinned = savedViewService.pinTransaction(view.getId(), USER_ID, transaction.getId());

    assertThat(pinned.getPinnedIds()).containsExactly(transaction.getId());
    assertThat(pinned.getExcludedIds()).isEmpty();

    var excluded = savedViewService.excludeTransaction(view.getId(), USER_ID, transaction.getId());

    assertThat(excluded.getExcludedIds()).containsExactly(transaction.getId());
    assertThat(excluded.getPinnedIds()).isEmpty();
    var persisted = savedViewRepository.findById(view.getId()).orElseThrow();
    assertThat(persisted.getExcludedIds()).containsExactly(transaction.getId());

    savedViewService.unexcludeTransaction(view.getId(), USER_ID, transaction.getId());
    savedViewService.pinTransaction(view.getId(), USER_ID, transaction.getId());
    savedViewService.unpinTransaction(view.getId(), USER_ID, transaction.getId());

    var cleared = savedViewRepository.findById(view.getId()).orElseThrow();
    assertThat(cleared.getPinnedIds()).isEmpty();
    assertThat(cleared.getExcludedIds()).isEmpty();
  }

  @Test
  void pinAndExcludeRejectMissingDeletedAndForeignTransactions() {
    var deletedTransaction =
        transactionRepository.save(
            createTransaction("Deleted", LocalDate.of(2024, 12, 15), TransactionType.DEBIT));
    transactionService.deleteTransaction(deletedTransaction.getId(), USER_ID, false);
    var foreignTransaction =
        transactionRepository.save(
            createTransaction(
                "Foreign", LocalDate.of(2024, 12, 15), TransactionType.DEBIT, "other-user"));
    var view =
        savedViewService.createView(
            USER_ID, new SavedViewCommand("Protected overrides", ViewCriteria.empty(), false));

    assertThatThrownBy(
            () ->
                savedViewService.pinTransaction(view.getId(), USER_ID, deletedTransaction.getId()))
        .isInstanceOf(ResourceNotFoundException.class);
    assertThatThrownBy(
            () ->
                savedViewService.excludeTransaction(
                    view.getId(), USER_ID, foreignTransaction.getId()))
        .isInstanceOf(ResourceNotFoundException.class);
    assertThatThrownBy(() -> savedViewService.pinTransaction(view.getId(), USER_ID, Long.MAX_VALUE))
        .isInstanceOf(ResourceNotFoundException.class);

    var persisted = savedViewRepository.findById(view.getId()).orElseThrow();
    assertThat(persisted.getPinnedIds()).isEmpty();
    assertThat(persisted.getExcludedIds()).isEmpty();
  }

  @Test
  void resolveViewScopesAllMembershipTypesToActiveOwnerTransactions() {
    var matchedPinned =
        transactionRepository.save(
            createTransaction("Matched pinned", LocalDate.of(2024, 12, 15), TransactionType.DEBIT));
    var matchedExcluded =
        transactionRepository.save(
            createTransaction(
                "Matched excluded", LocalDate.of(2024, 12, 16), TransactionType.DEBIT));
    var outsideRangePinned =
        transactionRepository.save(
            createTransaction(
                "Outside range pinned", LocalDate.of(2025, 1, 15), TransactionType.DEBIT));
    var deletedPinned =
        transactionRepository.save(
            createTransaction("Deleted pinned", LocalDate.of(2025, 1, 16), TransactionType.DEBIT));
    transactionService.deleteTransaction(deletedPinned.getId(), USER_ID, false);
    var foreignPinned =
        transactionRepository.save(
            createTransaction(
                "Foreign pinned", LocalDate.of(2025, 1, 17), TransactionType.DEBIT, "other-user"));
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
        savedViewService.createView(USER_ID, new SavedViewCommand("Membership", criteria, false));
    view.setPinnedIds(
        new HashSet<>(
            Set.of(
                matchedPinned.getId(),
                outsideRangePinned.getId(),
                deletedPinned.getId(),
                foreignPinned.getId())));
    view.setExcludedIds(new HashSet<>(Set.of(matchedExcluded.getId(), foreignPinned.getId())));
    savedViewRepository.save(view);

    var resolution = savedViewService.resolveView(view);

    assertThat(resolution.membership().matched()).containsExactly(matchedPinned.getId());
    assertThat(resolution.membership().pinned()).containsExactly(outsideRangePinned.getId());
    assertThat(resolution.membership().excluded()).containsExactly(matchedExcluded.getId());
    assertThat(resolution.activePinnedCount()).isEqualTo(2);
    assertThat(resolution.activeExcludedCount()).isEqualTo(1);
    assertThat(resolution.transactionCount()).isEqualTo(2);
    assertThat(savedViewService.countViewTransactions(view)).isEqualTo(2);
  }

  @Test
  void bulkPinPersistsUniqueOwnedIdsAndReportsUnavailableIdsInRequestOrder() {
    var firstTransaction =
        transactionRepository.save(
            createTransaction("First", LocalDate.of(2024, 12, 15), TransactionType.DEBIT));
    var secondTransaction =
        transactionRepository.save(
            createTransaction("Second", LocalDate.of(2024, 12, 16), TransactionType.DEBIT));
    var foreignTransaction =
        transactionRepository.save(
            createTransaction(
                "Foreign", LocalDate.of(2024, 12, 17), TransactionType.DEBIT, "other-user"));
    var view =
        savedViewService.createView(
            USER_ID, new SavedViewCommand("Bulk pins", ViewCriteria.empty(), false));
    savedViewService.bulkExcludeTransactions(
        view.getId(), USER_ID, List.of(firstTransaction.getId(), secondTransaction.getId()));

    var result =
        savedViewService.bulkPinTransactions(
            view.getId(),
            USER_ID,
            List.of(
                firstTransaction.getId(),
                firstTransaction.getId(),
                secondTransaction.getId(),
                foreignTransaction.getId(),
                Long.MAX_VALUE));

    assertThat(result.updatedCount()).isEqualTo(2);
    assertThat(result.notFoundIds()).containsExactly(foreignTransaction.getId(), Long.MAX_VALUE);
    var persisted = savedViewRepository.findById(view.getId()).orElseThrow();
    assertThat(persisted.getPinnedIds())
        .containsExactlyInAnyOrder(firstTransaction.getId(), secondTransaction.getId());
    assertThat(persisted.getExcludedIds()).isEmpty();
  }

  @Test
  void bulkExcludePersistsUniqueOwnedIdsAndReportsUnavailableIds() {
    var ownedTransaction =
        transactionRepository.save(
            createTransaction("Owned", LocalDate.of(2024, 12, 15), TransactionType.DEBIT));
    var foreignTransaction =
        transactionRepository.save(
            createTransaction(
                "Foreign", LocalDate.of(2024, 12, 16), TransactionType.DEBIT, "other-user"));
    var view =
        savedViewService.createView(
            USER_ID, new SavedViewCommand("Bulk exclusions", ViewCriteria.empty(), false));
    savedViewService.bulkPinTransactions(view.getId(), USER_ID, List.of(ownedTransaction.getId()));

    var result =
        savedViewService.bulkExcludeTransactions(
            view.getId(),
            USER_ID,
            List.of(
                ownedTransaction.getId(),
                ownedTransaction.getId(),
                foreignTransaction.getId(),
                Long.MAX_VALUE));

    assertThat(result.updatedCount()).isEqualTo(1);
    assertThat(result.notFoundIds()).containsExactly(foreignTransaction.getId(), Long.MAX_VALUE);
    var persisted = savedViewRepository.findById(view.getId()).orElseThrow();
    assertThat(persisted.getExcludedIds()).containsExactly(ownedTransaction.getId());
    assertThat(persisted.getPinnedIds()).isEmpty();
  }

  @Test
  void bulkUpdatesRejectMissingOrForeignView() {
    var foreignView =
        savedViewService.createView(
            "other-user", new SavedViewCommand("Foreign view", ViewCriteria.empty(), false));

    assertThatThrownBy(
            () ->
                savedViewService.bulkPinTransactions(
                    foreignView.getId(), USER_ID, List.of(Long.MAX_VALUE)))
        .isInstanceOf(ResourceNotFoundException.class);
    assertThatThrownBy(
            () ->
                savedViewService.bulkExcludeTransactions(
                    java.util.UUID.randomUUID(), USER_ID, List.of(Long.MAX_VALUE)))
        .isInstanceOf(ResourceNotFoundException.class);
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

  @Test
  void getViewTransactions_multipleBankNamesIncludeAllListedBanks() {
    final var capitalOneTransaction =
        transactionRepository.save(
            createTransaction(
                "Capital One transaction",
                LocalDate.of(2024, 12, 15),
                TransactionType.DEBIT,
                "checking-12345",
                "Capital One",
                "USD"));
    var bangkokBankTransaction =
        transactionRepository.save(
            createTransaction(
                "Bangkok Bank transaction",
                LocalDate.of(2024, 12, 16),
                TransactionType.DEBIT,
                "checking-12345",
                "Bangkok Bank",
                "THB"));
    transactionRepository.save(
        createTransaction(
            "Truist transaction",
            LocalDate.of(2024, 12, 17),
            TransactionType.DEBIT,
            "checking-12345",
            "Truist",
            "USD"));

    var criteria =
        new ViewCriteria(
            null, null, null, Set.of("capital", "bangkok"), null, null, null, null, null);
    var view = savedViewService.createView(USER_ID, new SavedViewCommand("Banks", criteria, false));

    var membership = savedViewService.getViewTransactions(view.getId(), USER_ID);

    assertThat(membership.matched())
        .containsExactly(capitalOneTransaction.getId(), bangkokBankTransaction.getId());
  }

  @Test
  void getViewTransactions_multipleAccountIdsIncludeAllListedAccounts() {
    var checkingTransaction =
        transactionRepository.save(
            createTransaction(
                "Checking transaction",
                LocalDate.of(2024, 12, 15),
                TransactionType.DEBIT,
                "checking-12345",
                "Capital One",
                "USD"));
    var savingsTransaction =
        transactionRepository.save(
            createTransaction(
                "Savings transaction",
                LocalDate.of(2024, 12, 16),
                TransactionType.DEBIT,
                "savings-67890",
                "Capital One",
                "USD"));
    transactionRepository.save(
        createTransaction(
            "Brokerage transaction",
            LocalDate.of(2024, 12, 17),
            TransactionType.DEBIT,
            "brokerage-11111",
            "Capital One",
            "USD"));

    var criteria =
        new ViewCriteria(
            null,
            null,
            Set.of("checking-12345", "savings-67890"),
            null,
            null,
            null,
            null,
            null,
            null);
    var view =
        savedViewService.createView(USER_ID, new SavedViewCommand("Accounts", criteria, false));

    var membership = savedViewService.getViewTransactions(view.getId(), USER_ID);

    assertThat(membership.matched())
        .containsExactly(checkingTransaction.getId(), savingsTransaction.getId());
  }

  @Test
  void getViewTransactions_multipleCurrencyIsoCodesIncludeAllListedCurrencies() {
    var dollarTransaction =
        transactionRepository.save(
            createTransaction(
                "Dollar transaction",
                LocalDate.of(2024, 12, 15),
                TransactionType.DEBIT,
                "checking-12345",
                "Capital One",
                "USD"));
    var bahtTransaction =
        transactionRepository.save(
            createTransaction(
                "Baht transaction",
                LocalDate.of(2024, 12, 16),
                TransactionType.DEBIT,
                "checking-12345",
                "Bangkok Bank",
                "THB"));
    transactionRepository.save(
        createTransaction(
            "Euro transaction",
            LocalDate.of(2024, 12, 17),
            TransactionType.DEBIT,
            "checking-12345",
            "Test Bank",
            "EUR"));

    var criteria =
        new ViewCriteria(null, null, null, null, Set.of("usd", "thb"), null, null, null, null);
    var view =
        savedViewService.createView(USER_ID, new SavedViewCommand("Currencies", criteria, false));

    var membership = savedViewService.getViewTransactions(view.getId(), USER_ID);

    assertThat(membership.matched())
        .containsExactly(dollarTransaction.getId(), bahtTransaction.getId());
  }

  @Test
  void getViewTransactions_searchTextMatchesDescriptionOnly() {
    var descriptionMatch =
        transactionRepository.save(
            createTransaction(
                "Coffee shop",
                LocalDate.of(2024, 12, 15),
                TransactionType.DEBIT,
                "checking-12345",
                "Neighborhood Bank",
                "USD"));
    transactionRepository.save(
        createTransaction(
            "Grocery store",
            LocalDate.of(2024, 12, 16),
            TransactionType.DEBIT,
            "checking-12345",
            "Capital One",
            "USD"));
    transactionRepository.save(
        createTransaction(
            "Fuel stop",
            LocalDate.of(2024, 12, 17),
            TransactionType.DEBIT,
            "checking-12345",
            "Bangkok Bank",
            "THB"));

    var criteria =
        new ViewCriteria(null, null, null, null, null, null, null, null, "coffee capital");
    var view =
        savedViewService.createView(USER_ID, new SavedViewCommand("Search text", criteria, false));

    var membership = savedViewService.getViewTransactions(view.getId(), USER_ID);

    assertThat(membership.matched()).containsExactly(descriptionMatch.getId());
  }

  @Test
  void getViewTransactions_blankAndEmptySetCriteriaEntriesAreIgnored() {
    var bankNames = new java.util.HashSet<String>();
    bankNames.add("capital");
    bankNames.add("");
    bankNames.add(" ");
    var criteria =
        new ViewCriteria(null, null, Set.of(), bankNames, Set.of(), null, null, null, null);
    var capitalOneTransaction =
        transactionRepository.save(
            createTransaction(
                "Capital One transaction",
                LocalDate.of(2024, 12, 15),
                TransactionType.DEBIT,
                "checking-12345",
                "Capital One",
                "USD"));
    transactionRepository.save(
        createTransaction(
            "Bangkok Bank transaction",
            LocalDate.of(2024, 12, 16),
            TransactionType.DEBIT,
            "checking-12345",
            "Bangkok Bank",
            "THB"));

    var view =
        savedViewService.createView(USER_ID, new SavedViewCommand("Blank values", criteria, false));

    var membership = savedViewService.getViewTransactions(view.getId(), USER_ID);

    assertThat(membership.matched()).containsExactly(capitalOneTransaction.getId());
  }

  @Test
  void bulkExcludeTransactions_persistsExcludedIdsAndAffectsMembership() {
    var includedTransaction =
        transactionRepository.save(
            createTransaction("Included", LocalDate.of(2024, 12, 15), TransactionType.DEBIT));
    var excludedMatchTransaction =
        transactionRepository.save(
            createTransaction("Excluded Match", LocalDate.of(2024, 12, 16), TransactionType.DEBIT));
    var excludedNonMatchTransaction =
        transactionRepository.save(
            createTransaction(
                "Excluded Non Match", LocalDate.of(2025, 1, 10), TransactionType.DEBIT));

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
        savedViewService.createView(
            USER_ID, new SavedViewCommand("December only", criteria, false));

    var result =
        savedViewService.bulkExcludeTransactions(
            view.getId(),
            USER_ID,
            List.of(excludedMatchTransaction.getId(), excludedNonMatchTransaction.getId()));

    assertThat(result.updatedCount()).isEqualTo(2);
    assertThat(result.notFoundIds()).isEmpty();

    var persistedView = savedViewRepository.findById(view.getId()).orElseThrow();
    assertThat(persistedView.getExcludedIds())
        .containsExactlyInAnyOrder(
            excludedMatchTransaction.getId(), excludedNonMatchTransaction.getId());

    var membership = savedViewService.getViewTransactions(view.getId(), USER_ID);
    assertThat(membership.matched()).containsExactly(includedTransaction.getId());
    assertThat(membership.excluded())
        .containsExactlyInAnyOrder(
            excludedMatchTransaction.getId(), excludedNonMatchTransaction.getId());
  }

  @Test
  void resolveView_deletedExclusionDoesNotBlockEquivalentReplacementTransaction() {
    var transactionDate = LocalDate.now();
    var originalTransaction =
        transactionRepository.save(
            createTransaction("Equivalent transaction", transactionDate, TransactionType.DEBIT));
    var criteria =
        new ViewCriteria(
            transactionDate.minusDays(1),
            null,
            null,
            null,
            null,
            null,
            null,
            TransactionType.DEBIT,
            null);
    var view =
        savedViewService.createView(
            USER_ID, new SavedViewCommand("Open-ended debits", criteria, true));
    var excludedView =
        savedViewService.excludeTransaction(view.getId(), USER_ID, originalTransaction.getId());

    var activeExclusionResolution = savedViewService.resolveView(excludedView);

    assertThat(activeExclusionResolution.membership().matched()).isEmpty();
    assertThat(activeExclusionResolution.membership().excluded())
        .containsExactly(originalTransaction.getId());
    assertThat(activeExclusionResolution.activeExcludedCount()).isEqualTo(1);
    assertThat(activeExclusionResolution.transactionCount()).isZero();

    transactionService.deleteTransaction(originalTransaction.getId(), USER_ID, false);
    var replacementTransaction =
        transactionRepository.save(
            createTransaction("Equivalent transaction", transactionDate, TransactionType.DEBIT));

    var replacementResolution = savedViewService.resolveView(excludedView);

    assertThat(replacementTransaction.getId()).isNotEqualTo(originalTransaction.getId());
    assertThat(replacementResolution.membership().matched())
        .containsExactly(replacementTransaction.getId());
    assertThat(replacementResolution.membership().excluded()).isEmpty();
    assertThat(replacementResolution.activeExcludedCount()).isZero();
    assertThat(replacementResolution.transactionCount()).isEqualTo(1);
    assertThat(savedViewRepository.findById(view.getId()).orElseThrow().getExcludedIds())
        .containsExactly(originalTransaction.getId());
  }

  private Transaction createTransaction(
      String description, LocalDate date, TransactionType transactionType) {
    return createTransaction(
        description, date, transactionType, "checking-12345", "Capital One", "USD");
  }

  private Transaction createTransaction(
      String description, LocalDate date, TransactionType transactionType, String ownerId) {
    var transaction =
        createTransaction(
            description, date, transactionType, "checking-12345", "Capital One", "USD");
    transaction.setOwnerId(ownerId);
    return transaction;
  }

  private Transaction createTransaction(
      String description,
      LocalDate date,
      TransactionType transactionType,
      String accountId,
      String bankName,
      String currencyIsoCode) {
    var transaction = new Transaction();
    transaction.setAccountId(accountId);
    transaction.setBankName(bankName);
    transaction.setDate(date);
    transaction.setCurrencyIsoCode(currencyIsoCode);
    transaction.setAmount(BigDecimal.TEN);
    transaction.setType(transactionType);
    transaction.setDescription(description);
    transaction.setOwnerId(USER_ID);
    return transaction;
  }
}
