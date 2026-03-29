package org.budgetanalyzer.transaction.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.jpa.domain.Specification;

import org.budgetanalyzer.service.exception.ResourceNotFoundException;
import org.budgetanalyzer.transaction.api.ViewCriteriaApi;
import org.budgetanalyzer.transaction.api.request.CreateSavedViewRequest;
import org.budgetanalyzer.transaction.api.request.UpdateSavedViewRequest;
import org.budgetanalyzer.transaction.domain.SavedView;
import org.budgetanalyzer.transaction.domain.Transaction;
import org.budgetanalyzer.transaction.domain.TransactionType;
import org.budgetanalyzer.transaction.repository.SavedViewRepository;
import org.budgetanalyzer.transaction.repository.TransactionRepository;

@ExtendWith(MockitoExtension.class)
class SavedViewServiceTest {

  private static final String USER_ID = "usr_test123";
  private static final UUID VIEW_ID = UUID.randomUUID();

  @Mock private SavedViewRepository savedViewRepository;
  @Mock private TransactionRepository transactionRepository;

  @InjectMocks private SavedViewService savedViewService;

  private SavedView testView;
  private Transaction testTransaction1;
  private Transaction testTransaction2;
  private Transaction testTransaction3;

  @BeforeEach
  void setUp() {
    testView = createSavedView(VIEW_ID, USER_ID, "Test View");
    testTransaction1 = createTransaction(1L, "Coffee Shop", LocalDate.of(2024, 12, 1));
    testTransaction2 = createTransaction(2L, "Grocery Store", LocalDate.of(2024, 12, 5));
    testTransaction3 = createTransaction(3L, "Gas Station", LocalDate.of(2024, 12, 10));
  }

  // ==================== createView ====================

  @Test
  void createView_validRequest_createsAndReturnsView() {
    var criteria =
        new ViewCriteriaApi(
            LocalDate.of(2024, 12, 1),
            LocalDate.of(2024, 12, 31),
            null,
            null,
            null,
            null,
            null,
            null);
    var request = new CreateSavedViewRequest("My View", criteria, false);

    when(savedViewRepository.save(any(SavedView.class)))
        .thenAnswer(invocation -> invocation.getArgument(0));

    var result = savedViewService.createView(USER_ID, request);

    assertThat(result.getName()).isEqualTo("My View");
    assertThat(result.getUserId()).isEqualTo(USER_ID);
    assertThat(result.getCriteria().startDate()).isEqualTo(LocalDate.of(2024, 12, 1));
    assertThat(result.isOpenEnded()).isFalse();

    verify(savedViewRepository, times(1)).save(any(SavedView.class));
  }

  // ==================== getView ====================

  @Test
  void getView_existingViewForUser_returnsView() {
    when(savedViewRepository.findByIdAndUserId(VIEW_ID, USER_ID)).thenReturn(Optional.of(testView));

    var result = savedViewService.getView(VIEW_ID, USER_ID);

    assertThat(result).isNotNull();
    assertThat(result.getId()).isEqualTo(VIEW_ID);
    assertThat(result.getUserId()).isEqualTo(USER_ID);
  }

  @Test
  void getView_nonExistentView_throwsResourceNotFoundException() {
    when(savedViewRepository.findByIdAndUserId(VIEW_ID, USER_ID)).thenReturn(Optional.empty());

    assertThatThrownBy(() -> savedViewService.getView(VIEW_ID, USER_ID))
        .isInstanceOf(ResourceNotFoundException.class)
        .hasMessageContaining("Saved view not found");
  }

  @Test
  void getView_viewBelongsToOtherUser_throwsResourceNotFoundException() {
    var otherUserId = "usr_other";
    when(savedViewRepository.findByIdAndUserId(VIEW_ID, otherUserId)).thenReturn(Optional.empty());

    assertThatThrownBy(() -> savedViewService.getView(VIEW_ID, otherUserId))
        .isInstanceOf(ResourceNotFoundException.class);
  }

  // ==================== updateView ====================

  @Test
  void updateView_updateName_updatesAndSaves() {
    when(savedViewRepository.findByIdAndUserId(VIEW_ID, USER_ID)).thenReturn(Optional.of(testView));
    when(savedViewRepository.save(any(SavedView.class)))
        .thenAnswer(invocation -> invocation.getArgument(0));

    var request = new UpdateSavedViewRequest("New Name", null, null);
    var result = savedViewService.updateView(VIEW_ID, USER_ID, request);

    assertThat(result.getName()).isEqualTo("New Name");
    verify(savedViewRepository, times(1)).save(testView);
  }

  // ==================== deleteView ====================

  @Test
  void deleteView_existingView_deletesView() {
    when(savedViewRepository.findByIdAndUserId(VIEW_ID, USER_ID)).thenReturn(Optional.of(testView));

    savedViewService.deleteView(VIEW_ID, USER_ID);

    verify(savedViewRepository, times(1)).delete(testView);
  }

  // ==================== getViewTransactions ====================

  @Test
  @SuppressWarnings("unchecked")
  void getViewTransactions_criteriaOnly_returnsMatchingTransactions() {
    testView.setCriteria(
        new org.budgetanalyzer.transaction.domain.ViewCriteria(
            LocalDate.of(2024, 12, 1),
            LocalDate.of(2024, 12, 31),
            null,
            null,
            null,
            null,
            null,
            null));

    when(savedViewRepository.findByIdAndUserId(VIEW_ID, USER_ID)).thenReturn(Optional.of(testView));
    when(transactionRepository.findAllActive(any(Specification.class)))
        .thenReturn(List.of(testTransaction1, testTransaction2));

    var result = savedViewService.getViewTransactions(VIEW_ID, USER_ID);

    assertThat(result.matched()).hasSize(2);
    assertThat(result.matched()).containsExactlyInAnyOrder(1L, 2L);
    assertThat(result.pinned()).isEmpty();
    assertThat(result.excluded()).isEmpty();
  }

  @Test
  @SuppressWarnings("unchecked")
  void getViewTransactions_pinnedTransaction_appearsEvenIfNotMatchingCriteria() {
    testView.setCriteria(
        new org.budgetanalyzer.transaction.domain.ViewCriteria(
            LocalDate.of(2024, 12, 1),
            LocalDate.of(2024, 12, 5),
            null,
            null,
            null,
            null,
            null,
            null));
    testView.setPinnedIds(Set.of(3L));

    when(savedViewRepository.findByIdAndUserId(VIEW_ID, USER_ID)).thenReturn(Optional.of(testView));
    when(transactionRepository.findAllActive(any(Specification.class)))
        .thenReturn(List.of(testTransaction1));
    when(transactionRepository.findByIdActive(3L)).thenReturn(Optional.of(testTransaction3));

    var result = savedViewService.getViewTransactions(VIEW_ID, USER_ID);

    assertThat(result.matched()).hasSize(1);
    assertThat(result.matched()).containsExactly(1L);
    assertThat(result.pinned()).hasSize(1);
    assertThat(result.pinned()).containsExactly(3L);
    assertThat(result.excluded()).isEmpty();
  }

  @Test
  @SuppressWarnings("unchecked")
  void getViewTransactions_excludedTransaction_hiddenEvenIfMatchesCriteria() {
    testView.setCriteria(
        new org.budgetanalyzer.transaction.domain.ViewCriteria(
            LocalDate.of(2024, 12, 1),
            LocalDate.of(2024, 12, 31),
            null,
            null,
            null,
            null,
            null,
            null));
    testView.setExcludedIds(Set.of(2L));

    when(savedViewRepository.findByIdAndUserId(VIEW_ID, USER_ID)).thenReturn(Optional.of(testView));
    when(transactionRepository.findAllActive(any(Specification.class)))
        .thenReturn(List.of(testTransaction1, testTransaction2, testTransaction3));
    when(transactionRepository.findByIdActive(2L)).thenReturn(Optional.of(testTransaction2));

    var result = savedViewService.getViewTransactions(VIEW_ID, USER_ID);

    assertThat(result.matched()).hasSize(2);
    assertThat(result.matched()).containsExactlyInAnyOrder(1L, 3L);
    assertThat(result.matched()).doesNotContain(2L);
    assertThat(result.pinned()).isEmpty();
    assertThat(result.excluded()).hasSize(1);
    assertThat(result.excluded()).containsExactly(2L);
  }

  @Test
  @SuppressWarnings("unchecked")
  void getViewTransactions_pinnedAndMatched_appearsInMatchedOnly() {
    testView.setCriteria(
        new org.budgetanalyzer.transaction.domain.ViewCriteria(
            LocalDate.of(2024, 12, 1),
            LocalDate.of(2024, 12, 31),
            null,
            null,
            null,
            null,
            null,
            null));
    testView.setPinnedIds(Set.of(1L, 2L));

    when(savedViewRepository.findByIdAndUserId(VIEW_ID, USER_ID)).thenReturn(Optional.of(testView));
    when(transactionRepository.findAllActive(any(Specification.class)))
        .thenReturn(List.of(testTransaction1, testTransaction2));
    when(transactionRepository.findByIdActive(1L)).thenReturn(Optional.of(testTransaction1));
    when(transactionRepository.findByIdActive(2L)).thenReturn(Optional.of(testTransaction2));

    var result = savedViewService.getViewTransactions(VIEW_ID, USER_ID);

    // Both transactions match criteria and are pinned, so they appear in matched only (no
    // duplication)
    assertThat(result.matched()).hasSize(2);
    assertThat(result.matched()).containsExactlyInAnyOrder(1L, 2L);
    assertThat(result.pinned()).isEmpty();
    assertThat(result.excluded()).isEmpty();
  }

  @Test
  @SuppressWarnings("unchecked")
  void getViewTransactions_emptyView_returnsEmptyLists() {
    testView.setCriteria(
        new org.budgetanalyzer.transaction.domain.ViewCriteria(
            LocalDate.of(2024, 12, 1),
            LocalDate.of(2024, 12, 31),
            null,
            null,
            null,
            null,
            null,
            null));

    when(savedViewRepository.findByIdAndUserId(VIEW_ID, USER_ID)).thenReturn(Optional.of(testView));
    when(transactionRepository.findAllActive(any(Specification.class))).thenReturn(List.of());

    var result = savedViewService.getViewTransactions(VIEW_ID, USER_ID);

    assertThat(result.matched()).isEmpty();
    assertThat(result.pinned()).isEmpty();
    assertThat(result.excluded()).isEmpty();
  }

  @Test
  @SuppressWarnings("unchecked")
  void getViewTransactions_softDeletedPinnedTransaction_notIncluded() {
    testView.setCriteria(
        new org.budgetanalyzer.transaction.domain.ViewCriteria(
            LocalDate.of(2024, 12, 1),
            LocalDate.of(2024, 12, 31),
            null,
            null,
            null,
            null,
            null,
            null));
    testView.setPinnedIds(Set.of(1L, 99L));

    when(savedViewRepository.findByIdAndUserId(VIEW_ID, USER_ID)).thenReturn(Optional.of(testView));
    when(transactionRepository.findAllActive(any(Specification.class)))
        .thenReturn(List.of(testTransaction1));
    when(transactionRepository.findByIdActive(1L)).thenReturn(Optional.of(testTransaction1));
    when(transactionRepository.findByIdActive(99L)).thenReturn(Optional.empty()); // Soft-deleted

    var result = savedViewService.getViewTransactions(VIEW_ID, USER_ID);

    // Transaction 1 is matched, transaction 99 is soft-deleted so not included
    assertThat(result.matched()).hasSize(1);
    assertThat(result.matched()).containsExactly(1L);
    assertThat(result.pinned()).isEmpty();
    assertThat(result.excluded()).isEmpty();
  }

  @Test
  @SuppressWarnings("unchecked")
  void getViewTransactions_softDeletedExcludedTransaction_notIncluded() {
    testView.setCriteria(
        new org.budgetanalyzer.transaction.domain.ViewCriteria(
            LocalDate.of(2024, 12, 1),
            LocalDate.of(2024, 12, 31),
            null,
            null,
            null,
            null,
            null,
            null));
    testView.setExcludedIds(Set.of(99L));

    when(savedViewRepository.findByIdAndUserId(VIEW_ID, USER_ID)).thenReturn(Optional.of(testView));
    when(transactionRepository.findAllActive(any(Specification.class)))
        .thenReturn(List.of(testTransaction1, testTransaction2));
    when(transactionRepository.findByIdActive(99L)).thenReturn(Optional.empty()); // Soft-deleted

    var result = savedViewService.getViewTransactions(VIEW_ID, USER_ID);

    // Both transactions are matched, excluded transaction 99 is soft-deleted so not in excluded
    // list
    assertThat(result.matched()).hasSize(2);
    assertThat(result.matched()).containsExactlyInAnyOrder(1L, 2L);
    assertThat(result.pinned()).isEmpty();
    assertThat(result.excluded()).isEmpty();
  }

  // ==================== owner scoping regression ====================

  @Test
  void criteriaToFilter_setsOwnerIdFromParameter() {
    var criteria =
        new org.budgetanalyzer.transaction.domain.ViewCriteria(
            LocalDate.of(2024, 12, 1),
            LocalDate.of(2024, 12, 31),
            null,
            null,
            null,
            null,
            null,
            null);

    var filter = savedViewService.criteriaToFilter(criteria, false, USER_ID);

    assertThat(filter.ownerId()).isEqualTo(USER_ID);
  }

  @Test
  @SuppressWarnings("unchecked")
  void getViewTransactions_excludesForeignOwnerTransactionsAcrossMembershipTypes() {
    testView.setCriteria(
        new org.budgetanalyzer.transaction.domain.ViewCriteria(
            LocalDate.of(2024, 12, 1),
            LocalDate.of(2024, 12, 31),
            null,
            null,
            null,
            null,
            null,
            null));
    testView.setPinnedIds(Set.of(3L, 4L, 5L));
    testView.setExcludedIds(Set.of(1L, 6L));

    var foreignMatchedTransaction =
        createTransaction(2L, "Foreign Grocery", LocalDate.of(2024, 12, 5), "usr_foreign");
    var ownedPinnedTransaction =
        createTransaction(4L, "Owned Pin", LocalDate.of(2024, 12, 15), USER_ID);
    var foreignPinnedTransaction =
        createTransaction(5L, "Foreign Pin", LocalDate.of(2024, 12, 18), "usr_foreign");
    var foreignExcludedTransaction =
        createTransaction(6L, "Foreign Excluded", LocalDate.of(2024, 12, 20), "usr_foreign");

    when(savedViewRepository.findByIdAndUserId(VIEW_ID, USER_ID)).thenReturn(Optional.of(testView));
    when(transactionRepository.findAllActive(any(Specification.class)))
        .thenReturn(List.of(testTransaction1, foreignMatchedTransaction, testTransaction3));
    when(transactionRepository.findByIdActive(1L)).thenReturn(Optional.of(testTransaction1));
    when(transactionRepository.findByIdActive(3L)).thenReturn(Optional.of(testTransaction3));
    when(transactionRepository.findByIdActive(4L)).thenReturn(Optional.of(ownedPinnedTransaction));
    when(transactionRepository.findByIdActive(5L))
        .thenReturn(Optional.of(foreignPinnedTransaction));
    when(transactionRepository.findByIdActive(6L))
        .thenReturn(Optional.of(foreignExcludedTransaction));

    var result = savedViewService.getViewTransactions(VIEW_ID, USER_ID);

    assertThat(result.matched()).containsExactly(3L);
    assertThat(result.pinned()).containsExactly(4L);
    assertThat(result.excluded()).containsExactly(1L);
  }

  @Test
  @SuppressWarnings("unchecked")
  void countViewTransactions_doesNotCountForeignOwnerTransactions() {
    testView.setCriteria(
        new org.budgetanalyzer.transaction.domain.ViewCriteria(
            LocalDate.of(2024, 12, 1),
            LocalDate.of(2024, 12, 31),
            null,
            null,
            null,
            null,
            null,
            null));
    testView.setPinnedIds(Set.of(4L, 5L));
    testView.setExcludedIds(Set.of(1L, 6L));

    var foreignMatchedTransaction =
        createTransaction(2L, "Foreign Grocery", LocalDate.of(2024, 12, 5), "usr_foreign");
    var ownedPinnedTransaction =
        createTransaction(4L, "Owned Pin", LocalDate.of(2024, 12, 15), USER_ID);
    var foreignPinnedTransaction =
        createTransaction(5L, "Foreign Pin", LocalDate.of(2024, 12, 18), "usr_foreign");
    var foreignExcludedTransaction =
        createTransaction(6L, "Foreign Excluded", LocalDate.of(2024, 12, 20), "usr_foreign");

    when(transactionRepository.findAllActive(any(Specification.class)))
        .thenReturn(List.of(testTransaction1, foreignMatchedTransaction, testTransaction3));
    when(transactionRepository.findByIdActive(1L)).thenReturn(Optional.of(testTransaction1));
    when(transactionRepository.findByIdActive(4L)).thenReturn(Optional.of(ownedPinnedTransaction));
    when(transactionRepository.findByIdActive(5L))
        .thenReturn(Optional.of(foreignPinnedTransaction));
    when(transactionRepository.findByIdActive(6L))
        .thenReturn(Optional.of(foreignExcludedTransaction));

    var count = savedViewService.countViewTransactions(testView);

    assertThat(count).isEqualTo(2);
  }

  // ==================== countViewTransactions ====================

  @Test
  @SuppressWarnings("unchecked")
  void countViewTransactions_withPinsAndExclusions_calculatesCorrectCount() {
    testView.setCriteria(
        new org.budgetanalyzer.transaction.domain.ViewCriteria(
            LocalDate.of(2024, 12, 1),
            LocalDate.of(2024, 12, 31),
            null,
            null,
            null,
            null,
            null,
            null));
    testView.setPinnedIds(Set.of(100L));
    testView.setExcludedIds(Set.of(2L));

    var pinnedTransaction = createTransaction(100L, "Pinned", LocalDate.of(2024, 12, 15));

    when(transactionRepository.findAllActive(any(Specification.class)))
        .thenReturn(List.of(testTransaction1, testTransaction2, testTransaction3));
    when(transactionRepository.findByIdActive(100L)).thenReturn(Optional.of(pinnedTransaction));
    when(transactionRepository.findByIdActive(2L)).thenReturn(Optional.of(testTransaction2));

    var count = savedViewService.countViewTransactions(testView);

    // 3 matching - 1 excluded + 1 pinned = 3
    assertThat(count).isEqualTo(3);
  }

  @Test
  @SuppressWarnings("unchecked")
  void countViewTransactions_softDeletedPinnedTransaction_notCounted() {
    testView.setCriteria(
        new org.budgetanalyzer.transaction.domain.ViewCriteria(
            LocalDate.of(2024, 12, 1),
            LocalDate.of(2024, 12, 31),
            null,
            null,
            null,
            null,
            null,
            null));
    testView.setPinnedIds(Set.of(99L));

    when(transactionRepository.findAllActive(any(Specification.class)))
        .thenReturn(List.of(testTransaction1, testTransaction2));
    when(transactionRepository.findByIdActive(99L)).thenReturn(Optional.empty()); // Soft-deleted

    var count = savedViewService.countViewTransactions(testView);

    // 2 matching + 0 pinned (soft-deleted) = 2
    assertThat(count).isEqualTo(2);
  }

  @Test
  @SuppressWarnings("unchecked")
  void countViewTransactions_softDeletedExcludedTransaction_stillCountsMatched() {
    testView.setCriteria(
        new org.budgetanalyzer.transaction.domain.ViewCriteria(
            LocalDate.of(2024, 12, 1),
            LocalDate.of(2024, 12, 31),
            null,
            null,
            null,
            null,
            null,
            null));
    testView.setExcludedIds(Set.of(99L));

    when(transactionRepository.findAllActive(any(Specification.class)))
        .thenReturn(List.of(testTransaction1, testTransaction2));
    when(transactionRepository.findByIdActive(99L)).thenReturn(Optional.empty()); // Soft-deleted

    var count = savedViewService.countViewTransactions(testView);

    // 2 matching - 0 excluded (soft-deleted, so doesn't affect count) = 2
    assertThat(count).isEqualTo(2);
  }

  // ==================== pinTransaction ====================

  @Test
  void pinTransaction_validTransaction_pinsToView() {
    when(savedViewRepository.findByIdAndUserId(VIEW_ID, USER_ID)).thenReturn(Optional.of(testView));
    when(transactionRepository.findByIdActive(1L)).thenReturn(Optional.of(testTransaction1));
    when(savedViewRepository.save(any(SavedView.class)))
        .thenAnswer(invocation -> invocation.getArgument(0));

    var result = savedViewService.pinTransaction(VIEW_ID, USER_ID, 1L);

    assertThat(result.getPinnedIds()).contains(1L);
  }

  @Test
  void pinTransaction_nonExistentTransaction_throwsResourceNotFoundException() {
    when(savedViewRepository.findByIdAndUserId(VIEW_ID, USER_ID)).thenReturn(Optional.of(testView));
    when(transactionRepository.findByIdActive(999L)).thenReturn(Optional.empty());

    assertThatThrownBy(() -> savedViewService.pinTransaction(VIEW_ID, USER_ID, 999L))
        .isInstanceOf(ResourceNotFoundException.class)
        .hasMessageContaining("Transaction not found");

    verify(savedViewRepository, never()).save(any());
  }

  @Test
  void pinTransaction_alsoRemovesFromExcluded() {
    testView.setExcludedIds(new HashSet<>(Set.of(1L)));
    when(savedViewRepository.findByIdAndUserId(VIEW_ID, USER_ID)).thenReturn(Optional.of(testView));
    when(transactionRepository.findByIdActive(1L)).thenReturn(Optional.of(testTransaction1));
    when(savedViewRepository.save(any(SavedView.class)))
        .thenAnswer(invocation -> invocation.getArgument(0));

    var result = savedViewService.pinTransaction(VIEW_ID, USER_ID, 1L);

    assertThat(result.getPinnedIds()).contains(1L);
    assertThat(result.getExcludedIds()).doesNotContain(1L);
  }

  // ==================== excludeTransaction ====================

  @Test
  void excludeTransaction_validTransaction_excludesFromView() {
    when(savedViewRepository.findByIdAndUserId(VIEW_ID, USER_ID)).thenReturn(Optional.of(testView));
    when(transactionRepository.findByIdActive(1L)).thenReturn(Optional.of(testTransaction1));
    when(savedViewRepository.save(any(SavedView.class)))
        .thenAnswer(invocation -> invocation.getArgument(0));

    var result = savedViewService.excludeTransaction(VIEW_ID, USER_ID, 1L);

    assertThat(result.getExcludedIds()).contains(1L);
  }

  @Test
  void excludeTransaction_alsoRemovesFromPinned() {
    testView.setPinnedIds(new HashSet<>(Set.of(1L)));
    when(savedViewRepository.findByIdAndUserId(VIEW_ID, USER_ID)).thenReturn(Optional.of(testView));
    when(transactionRepository.findByIdActive(1L)).thenReturn(Optional.of(testTransaction1));
    when(savedViewRepository.save(any(SavedView.class)))
        .thenAnswer(invocation -> invocation.getArgument(0));

    var result = savedViewService.excludeTransaction(VIEW_ID, USER_ID, 1L);

    assertThat(result.getExcludedIds()).contains(1L);
    assertThat(result.getPinnedIds()).doesNotContain(1L);
  }

  // ==================== Helper Methods ====================

  private SavedView createSavedView(UUID id, String userId, String name) {
    var view = new SavedView();
    view.setId(id);
    view.setUserId(userId);
    view.setName(name);
    view.setCriteria(org.budgetanalyzer.transaction.domain.ViewCriteria.empty());
    view.setOpenEnded(false);
    view.setPinnedIds(new HashSet<>());
    view.setExcludedIds(new HashSet<>());
    return view;
  }

  private Transaction createTransaction(Long id, String description, LocalDate date) {
    return createTransaction(id, description, date, USER_ID);
  }

  private Transaction createTransaction(
      Long id, String description, LocalDate date, String ownerId) {
    var transaction = new Transaction();
    transaction.setId(id);
    transaction.setAccountId("test-account");
    transaction.setBankName("Test Bank");
    transaction.setDate(date);
    transaction.setCurrencyIsoCode("USD");
    transaction.setAmount(BigDecimal.valueOf(100.00));
    transaction.setType(TransactionType.DEBIT);
    transaction.setDescription(description);
    transaction.setOwnerId(ownerId);
    try {
      var createdAtField = transaction.getClass().getSuperclass().getDeclaredField("createdAt");
      createdAtField.setAccessible(true);
      createdAtField.set(transaction, Instant.now());
      var updatedAtField = transaction.getClass().getSuperclass().getDeclaredField("updatedAt");
      updatedAtField.setAccessible(true);
      updatedAtField.set(transaction, Instant.now());
    } catch (Exception e) {
      // Ignore for testing
    }
    return transaction;
  }
}
