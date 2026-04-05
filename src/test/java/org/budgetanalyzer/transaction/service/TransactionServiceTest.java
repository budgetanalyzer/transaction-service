package org.budgetanalyzer.transaction.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.RETURNS_MOCKS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Root;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.jpa.domain.Specification;

import org.budgetanalyzer.service.exception.ResourceNotFoundException;
import org.budgetanalyzer.transaction.api.response.PreviewTransaction;
import org.budgetanalyzer.transaction.domain.Transaction;
import org.budgetanalyzer.transaction.domain.TransactionType;
import org.budgetanalyzer.transaction.repository.TransactionRepository;

@ExtendWith(MockitoExtension.class)
class TransactionServiceTest {

  private static final String USER_ID = "user-123";
  private static final String OTHER_USER_ID = "user-456";
  private static final boolean IS_ADMIN = true;
  private static final boolean NOT_ADMIN = false;

  @Mock private TransactionRepository transactionRepository;

  @InjectMocks private TransactionService transactionService;

  // ==================== createTransaction ====================

  @Test
  void createTransaction_validTransaction_savesAndReturnsTransaction() {
    // Given: a valid transaction
    var transaction = createTransaction(null, "Grocery Store", BigDecimal.valueOf(45.50));

    var savedTransaction = createTransaction(1L, "Grocery Store", BigDecimal.valueOf(45.50));
    when(transactionRepository.save(transaction)).thenReturn(savedTransaction);

    // When: create is called
    var result = transactionService.createTransaction(transaction, USER_ID);

    // Then: transaction is saved and returned with ID
    assertThat(result).isNotNull();
    assertThat(result.getId()).isEqualTo(1L);
    assertThat(result.getDescription()).isEqualTo("Grocery Store");
    assertThat(result.getAmount()).isEqualByComparingTo(BigDecimal.valueOf(45.50));

    verify(transactionRepository, times(1)).save(transaction);
  }

  @Test
  void createTransaction_setsOwnerId() {
    // Given: a transaction without ownerId
    var transaction = createTransaction(null, "New Transaction", BigDecimal.valueOf(10.00));
    transaction.setOwnerId(null);

    when(transactionRepository.save(any(Transaction.class)))
        .thenAnswer(invocation -> invocation.getArgument(0));

    // When: create is called with userId
    var result = transactionService.createTransaction(transaction, USER_ID);

    // Then: ownerId is set to the userId
    assertThat(result.getOwnerId()).isEqualTo(USER_ID);
  }

  // ==================== createTransactions ====================

  @Test
  void createTransactions_multipleValidTransactions_savesAllAndReturnsAll() {
    // Given: multiple valid transactions
    var transaction1 = createTransaction(null, "Grocery", BigDecimal.valueOf(50.00));
    var transaction2 = createTransaction(null, "Gas Station", BigDecimal.valueOf(35.00));
    var transaction3 = createTransaction(null, "Restaurant", BigDecimal.valueOf(75.00));

    var savedTransactions =
        List.of(
            createTransaction(1L, "Grocery", BigDecimal.valueOf(50.00)),
            createTransaction(2L, "Gas Station", BigDecimal.valueOf(35.00)),
            createTransaction(3L, "Restaurant", BigDecimal.valueOf(75.00)));

    when(transactionRepository.saveAll(List.of(transaction1, transaction2, transaction3)))
        .thenReturn(savedTransactions);

    // When: bulk create is called
    var result =
        transactionService.createTransactions(
            List.of(transaction1, transaction2, transaction3), USER_ID);

    // Then: all transactions are saved and returned
    assertThat(result).hasSize(3);
    assertThat(result.get(0).getId()).isEqualTo(1L);
    assertThat(result.get(1).getId()).isEqualTo(2L);
    assertThat(result.get(2).getId()).isEqualTo(3L);

    // And: ownerId is set on each
    assertThat(transaction1.getOwnerId()).isEqualTo(USER_ID);
    assertThat(transaction2.getOwnerId()).isEqualTo(USER_ID);
    assertThat(transaction3.getOwnerId()).isEqualTo(USER_ID);

    verify(transactionRepository, times(1)).saveAll(any());
  }

  // ==================== getTransaction ====================

  @Test
  void getTransaction_ownerMatch_returnsTransaction() {
    // Given: a transaction owned by the user
    var transaction = createTransaction(1L, "Coffee Shop", BigDecimal.valueOf(4.50));
    when(transactionRepository.findByIdNotDeleted(1L)).thenReturn(Optional.of(transaction));

    // When: owner requests their transaction
    var result = transactionService.getTransaction(1L, USER_ID, NOT_ADMIN);

    // Then: transaction is returned
    assertThat(result).isNotNull();
    assertThat(result.getId()).isEqualTo(1L);
    assertThat(result.getDescription()).isEqualTo("Coffee Shop");

    verify(transactionRepository, times(1)).findByIdNotDeleted(1L);
  }

  @Test
  void getTransaction_ownerMismatch_throwsResourceNotFoundException() {
    // Given: a transaction owned by another user
    var transaction = createTransaction(1L, "Coffee Shop", BigDecimal.valueOf(4.50));
    when(transactionRepository.findByIdNotDeleted(1L)).thenReturn(Optional.of(transaction));

    // When/Then: non-owner gets 404
    assertThatThrownBy(() -> transactionService.getTransaction(1L, OTHER_USER_ID, NOT_ADMIN))
        .isInstanceOf(ResourceNotFoundException.class)
        .hasMessageContaining("Transaction not found with id: 1");
  }

  @Test
  void getTransaction_admin_bypassesOwnerCheck() {
    // Given: a transaction owned by another user
    var transaction = createTransaction(1L, "Coffee Shop", BigDecimal.valueOf(4.50));
    when(transactionRepository.findByIdNotDeleted(1L)).thenReturn(Optional.of(transaction));

    // When: admin requests any transaction
    var result = transactionService.getTransaction(1L, OTHER_USER_ID, IS_ADMIN);

    // Then: admin can access it
    assertThat(result).isNotNull();
    assertThat(result.getId()).isEqualTo(1L);
  }

  @Test
  void getTransaction_nonExistentId_throwsResourceNotFoundException() {
    // Given: transaction does not exist
    when(transactionRepository.findByIdNotDeleted(9999L)).thenReturn(Optional.empty());

    // When/Then: get throws exception
    assertThatThrownBy(() -> transactionService.getTransaction(9999L, USER_ID, NOT_ADMIN))
        .isInstanceOf(ResourceNotFoundException.class)
        .hasMessageContaining("Transaction not found with id: 9999");

    verify(transactionRepository, times(1)).findByIdNotDeleted(9999L);
  }

  // ==================== updateTransaction ====================

  @Test
  void updateTransaction_updateDescription_updatesAndSaves() {
    // Given: a transaction exists owned by user
    var transaction = createTransaction(1L, "Old Description", BigDecimal.valueOf(100.00));
    when(transactionRepository.findByIdNotDeleted(1L)).thenReturn(Optional.of(transaction));
    when(transactionRepository.save(any(Transaction.class)))
        .thenAnswer(invocation -> invocation.getArgument(0));

    // When: update description is called
    var result =
        transactionService.updateTransaction(1L, USER_ID, NOT_ADMIN, "New Description", null);

    // Then: description is updated
    assertThat(result.getDescription()).isEqualTo("New Description");
    assertThat(result.getAccountId()).isEqualTo("test-account"); // unchanged

    verify(transactionRepository, times(1)).findByIdNotDeleted(1L);
    verify(transactionRepository, times(1)).save(transaction);
  }

  @Test
  void updateTransaction_updateAccountId_updatesAndSaves() {
    // Given: a transaction exists owned by user
    var transaction = createTransaction(1L, "Expense", BigDecimal.valueOf(50.00));
    when(transactionRepository.findByIdNotDeleted(1L)).thenReturn(Optional.of(transaction));
    when(transactionRepository.save(any(Transaction.class)))
        .thenAnswer(invocation -> invocation.getArgument(0));

    // When: update account ID is called
    var result =
        transactionService.updateTransaction(1L, USER_ID, NOT_ADMIN, null, "new-account-123");

    // Then: account ID is updated
    assertThat(result.getAccountId()).isEqualTo("new-account-123");
    assertThat(result.getDescription()).isEqualTo("Expense"); // unchanged

    verify(transactionRepository, times(1)).findByIdNotDeleted(1L);
    verify(transactionRepository, times(1)).save(transaction);
  }

  @Test
  void updateTransaction_updateBothFields_updatesAndSaves() {
    // Given: a transaction exists owned by user
    var transaction = createTransaction(1L, "Old", BigDecimal.valueOf(25.00));
    when(transactionRepository.findByIdNotDeleted(1L)).thenReturn(Optional.of(transaction));
    when(transactionRepository.save(any(Transaction.class)))
        .thenAnswer(invocation -> invocation.getArgument(0));

    // When: update both fields
    var result =
        transactionService.updateTransaction(
            1L, USER_ID, NOT_ADMIN, "New Description", "new-account");

    // Then: both fields are updated
    assertThat(result.getDescription()).isEqualTo("New Description");
    assertThat(result.getAccountId()).isEqualTo("new-account");

    verify(transactionRepository, times(1)).findByIdNotDeleted(1L);
    verify(transactionRepository, times(1)).save(transaction);
  }

  @Test
  void updateTransaction_ownerMismatch_throwsResourceNotFoundException() {
    // Given: a transaction owned by another user
    var transaction = createTransaction(1L, "Expense", BigDecimal.valueOf(50.00));
    when(transactionRepository.findByIdNotDeleted(1L)).thenReturn(Optional.of(transaction));

    // When/Then: non-owner gets 404
    assertThatThrownBy(
            () -> transactionService.updateTransaction(1L, OTHER_USER_ID, NOT_ADMIN, "New", null))
        .isInstanceOf(ResourceNotFoundException.class)
        .hasMessageContaining("Transaction not found with id: 1");

    verify(transactionRepository, never()).save(any());
  }

  @Test
  void updateTransaction_nonExistentId_throwsResourceNotFoundException() {
    // Given: transaction does not exist
    when(transactionRepository.findByIdNotDeleted(9999L)).thenReturn(Optional.empty());

    // When/Then: update throws exception
    assertThatThrownBy(
            () -> transactionService.updateTransaction(9999L, USER_ID, NOT_ADMIN, "New", null))
        .isInstanceOf(ResourceNotFoundException.class)
        .hasMessageContaining("Transaction not found with id: 9999");

    verify(transactionRepository, times(1)).findByIdNotDeleted(9999L);
  }

  // ==================== deleteTransaction ====================

  @Test
  void deleteTransaction_existingTransaction_marksDeletedAndSaves() {
    // Given: a transaction exists owned by user
    var transaction = createTransaction(1L, "To Delete", BigDecimal.valueOf(100.00));
    when(transactionRepository.findByIdNotDeleted(1L)).thenReturn(Optional.of(transaction));

    // When: delete is called
    transactionService.deleteTransaction(1L, USER_ID, NOT_ADMIN);

    // Then: transaction is marked as deleted
    assertThat(transaction.isDeleted()).isTrue();
    assertThat(transaction.getDeletedBy()).isEqualTo(USER_ID);
    assertThat(transaction.getDeletedAt()).isNotNull();

    verify(transactionRepository, times(1)).findByIdNotDeleted(1L);
    verify(transactionRepository, times(1))
        .save(
            argThat(
                t ->
                    t.isDeleted() && t.getDeletedBy().equals(USER_ID) && t.getDeletedAt() != null));
  }

  @Test
  void deleteTransaction_ownerMismatch_throwsResourceNotFoundException() {
    // Given: a transaction owned by another user
    var transaction = createTransaction(1L, "To Delete", BigDecimal.valueOf(100.00));
    when(transactionRepository.findByIdNotDeleted(1L)).thenReturn(Optional.of(transaction));

    // When/Then: non-owner gets 404
    assertThatThrownBy(() -> transactionService.deleteTransaction(1L, OTHER_USER_ID, NOT_ADMIN))
        .isInstanceOf(ResourceNotFoundException.class)
        .hasMessageContaining("Transaction not found with id: 1");

    verify(transactionRepository, never()).save(any());
  }

  @Test
  void deleteTransaction_nonExistentId_throwsResourceNotFoundException() {
    // Given: transaction does not exist
    when(transactionRepository.findByIdNotDeleted(9999L)).thenReturn(Optional.empty());

    // When/Then: delete throws exception
    assertThatThrownBy(() -> transactionService.deleteTransaction(9999L, USER_ID, NOT_ADMIN))
        .isInstanceOf(ResourceNotFoundException.class)
        .hasMessageContaining("Transaction not found with id: 9999");

    verify(transactionRepository, times(1)).findByIdNotDeleted(9999L);
  }

  // ==================== bulkDeleteTransactions ====================

  @Test
  void bulkDeleteTransactions_allFound_deletesAllTransactions() {
    // Given: 3 transactions exist owned by user
    var transaction1 = createTransaction(1L, "DESC1", BigDecimal.valueOf(100));
    var transaction2 = createTransaction(2L, "DESC2", BigDecimal.valueOf(200));
    var transaction3 = createTransaction(3L, "DESC3", BigDecimal.valueOf(300));

    when(transactionRepository.findByIdNotDeleted(1L)).thenReturn(Optional.of(transaction1));
    when(transactionRepository.findByIdNotDeleted(2L)).thenReturn(Optional.of(transaction2));
    when(transactionRepository.findByIdNotDeleted(3L)).thenReturn(Optional.of(transaction3));

    var ids = List.of(1L, 2L, 3L);

    // When: bulk delete is called
    var result = transactionService.bulkDeleteTransactions(ids, USER_ID, NOT_ADMIN);

    // Then: all transactions are marked as deleted
    assertThat(result.deletedCount()).isEqualTo(3);
    assertThat(result.notFoundIds()).isEmpty();

    verify(transactionRepository, times(3)).save(any(Transaction.class));
    assertThat(transaction1.isDeleted()).isTrue();
    assertThat(transaction2.isDeleted()).isTrue();
    assertThat(transaction3.isDeleted()).isTrue();
  }

  @Test
  void bulkDeleteTransactions_someNotFound_returnsPartialSuccess() {
    // Given: 2 transactions exist, 2 don't
    var transaction1 = createTransaction(1L, "DESC1", BigDecimal.valueOf(100));
    var transaction2 = createTransaction(2L, "DESC2", BigDecimal.valueOf(200));

    when(transactionRepository.findByIdNotDeleted(1L)).thenReturn(Optional.of(transaction1));
    when(transactionRepository.findByIdNotDeleted(2L)).thenReturn(Optional.of(transaction2));
    when(transactionRepository.findByIdNotDeleted(9999L)).thenReturn(Optional.empty());
    when(transactionRepository.findByIdNotDeleted(8888L)).thenReturn(Optional.empty());

    var ids = List.of(1L, 2L, 9999L, 8888L);

    // When: bulk delete is called
    var result = transactionService.bulkDeleteTransactions(ids, USER_ID, NOT_ADMIN);

    // Then: only existing transactions are deleted
    assertThat(result.deletedCount()).isEqualTo(2);
    assertThat(result.notFoundIds()).containsExactlyInAnyOrder(9999L, 8888L);

    verify(transactionRepository, times(2)).save(any(Transaction.class));
  }

  @Test
  void bulkDeleteTransactions_alreadyDeleted_treatedAsNotFound() {
    // Given: 2 transactions, but one is already deleted
    var transaction1 = createTransaction(1L, "DESC1", BigDecimal.valueOf(100));

    when(transactionRepository.findByIdNotDeleted(1L)).thenReturn(Optional.of(transaction1));
    when(transactionRepository.findByIdNotDeleted(2L)).thenReturn(Optional.empty());

    var ids = List.of(1L, 2L);

    // When: bulk delete is called
    var result = transactionService.bulkDeleteTransactions(ids, USER_ID, NOT_ADMIN);

    // Then: only non-deleted transaction is counted
    assertThat(result.deletedCount()).isEqualTo(1);
    assertThat(result.notFoundIds()).containsExactly(2L);

    verify(transactionRepository, times(1)).save(any(Transaction.class));
  }

  @Test
  void bulkDelete_mixedOwnership_nonAdmin_treatsOthersAsNotFound() {
    // Given: 2 transactions, one owned by user, one by another
    var ownedTransaction = createTransaction(1L, "Mine", BigDecimal.valueOf(100));
    var otherTransaction = createTransaction(2L, "Theirs", BigDecimal.valueOf(200));
    otherTransaction.setOwnerId(OTHER_USER_ID);

    when(transactionRepository.findByIdNotDeleted(1L)).thenReturn(Optional.of(ownedTransaction));
    when(transactionRepository.findByIdNotDeleted(2L)).thenReturn(Optional.of(otherTransaction));

    var ids = List.of(1L, 2L);

    // When: non-admin bulk deletes
    var result = transactionService.bulkDeleteTransactions(ids, USER_ID, NOT_ADMIN);

    // Then: only owned transaction is deleted, other is "not found"
    assertThat(result.deletedCount()).isEqualTo(1);
    assertThat(result.notFoundIds()).containsExactly(2L);

    verify(transactionRepository, times(1)).save(any(Transaction.class));
  }

  // ==================== getTransactions ====================

  @SuppressWarnings("unchecked")
  @Test
  void getTransactions_filtersByOwner() {
    // Given: repository returns results
    when(transactionRepository.findAllNotDeleted(any(Specification.class))).thenReturn(List.of());

    // When: user retrieves their transactions
    transactionService.getTransactions(USER_ID);

    // Then: the specification includes an ownerId equality predicate
    @SuppressWarnings("rawtypes")
    ArgumentCaptor<Specification> specCaptor = ArgumentCaptor.forClass(Specification.class);
    verify(transactionRepository).findAllNotDeleted(specCaptor.capture());

    var capturedSpec = specCaptor.getValue();

    Root<Transaction> root = mock(Root.class, RETURNS_DEEP_STUBS);
    CriteriaQuery<?> cq = mock(CriteriaQuery.class);
    CriteriaBuilder cb = mock(CriteriaBuilder.class, RETURNS_MOCKS);

    capturedSpec.toPredicate(root, cq, cb);

    // Verify the owner filter was applied (ownerId = USER_ID)
    verify(root).get("ownerId");
    verify(cb).equal(root.get("ownerId"), USER_ID);
  }

  // ==================== search (admin, paged) ====================

  @SuppressWarnings("unchecked")
  @Test
  void search_delegatesToFindAllActiveWithSpecAndPageable() {
    // Given: repository returns a page
    var pageable = PageRequest.of(0, 20);
    when(transactionRepository.findAllNotDeleted(any(Specification.class), eq(pageable)))
        .thenReturn(Page.empty());

    // When: search is called
    var result = transactionService.search(emptyFilter(), pageable);

    // Then: delegates to findAllNotDeleted with spec and pageable
    assertThat(result).isEmpty();
    verify(transactionRepository).findAllNotDeleted(any(Specification.class), eq(pageable));
  }

  @SuppressWarnings("unchecked")
  @Test
  void search_specIncludesFilterPredicates() {
    // Given: a filter with ownerId set
    var filter =
        new org.budgetanalyzer.transaction.api.request.TransactionFilter(
            null,
            "owner-abc",
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null);
    var pageable = PageRequest.of(0, 10);
    when(transactionRepository.findAllNotDeleted(any(Specification.class), eq(pageable)))
        .thenReturn(Page.empty());

    // When: search is called with the filter
    transactionService.search(filter, pageable);

    // Then: the spec includes the ownerId predicate from the filter
    @SuppressWarnings("rawtypes")
    ArgumentCaptor<Specification> specCaptor = ArgumentCaptor.forClass(Specification.class);
    verify(transactionRepository).findAllNotDeleted(specCaptor.capture(), eq(pageable));

    var capturedSpec = specCaptor.getValue();

    Root<Transaction> root = mock(Root.class, RETURNS_DEEP_STUBS);
    CriteriaQuery<?> cq = mock(CriteriaQuery.class);
    CriteriaBuilder cb = mock(CriteriaBuilder.class, RETURNS_MOCKS);

    capturedSpec.toPredicate(root, cq, cb);

    verify(root).get("ownerId");
    verify(cb).equal(root.get("ownerId"), "owner-abc");
  }

  // ==================== countNotDeletedForUser / countNotDeleted ====================

  @SuppressWarnings("unchecked")
  @Test
  void countNotDeletedForUser_filtersByOwner() {
    // Given: repository returns a count
    when(transactionRepository.countNotDeleted(any(Specification.class))).thenReturn(5L);

    // When: user-scoped count executes
    var result = transactionService.countNotDeletedForUser(emptyFilter(), USER_ID);

    // Then: the specification includes an ownerId equality predicate
    assertThat(result).isEqualTo(5L);

    @SuppressWarnings("rawtypes")
    ArgumentCaptor<Specification> specCaptor = ArgumentCaptor.forClass(Specification.class);
    verify(transactionRepository).countNotDeleted(specCaptor.capture());

    var capturedSpec = specCaptor.getValue();

    Root<Transaction> root = mock(Root.class, RETURNS_DEEP_STUBS);
    CriteriaQuery<?> cq = mock(CriteriaQuery.class);
    CriteriaBuilder cb = mock(CriteriaBuilder.class, RETURNS_MOCKS);

    capturedSpec.toPredicate(root, cq, cb);

    verify(root).get("ownerId");
    verify(cb).equal(root.get("ownerId"), USER_ID);
  }

  @SuppressWarnings("unchecked")
  @Test
  void countNotDeleted_admin_noOwnerFilter() {
    // Given: repository returns a count
    when(transactionRepository.countNotDeleted(any(Specification.class))).thenReturn(10L);

    // When: admin-wide count executes
    var result = transactionService.countNotDeleted(emptyFilter());

    // Then: the specification does NOT include an ownerId predicate
    assertThat(result).isEqualTo(10L);

    @SuppressWarnings("rawtypes")
    ArgumentCaptor<Specification> specCaptor = ArgumentCaptor.forClass(Specification.class);
    verify(transactionRepository).countNotDeleted(specCaptor.capture());

    var capturedSpec = specCaptor.getValue();

    Root<Transaction> root = mock(Root.class, RETURNS_DEEP_STUBS);
    CriteriaQuery<?> cq = mock(CriteriaQuery.class);
    CriteriaBuilder cb = mock(CriteriaBuilder.class, RETURNS_MOCKS);

    capturedSpec.toPredicate(root, cq, cb);

    verify(root, never()).get("ownerId");
  }

  // ==================== batchImport ====================

  @Test
  void batchImport_noDuplicates_createsAllTransactions() {
    // Given: two valid transactions with no existing duplicates
    var dto1 =
        new PreviewTransaction(
            LocalDate.of(2024, 1, 15),
            "Transaction 1",
            BigDecimal.valueOf(100.00),
            TransactionType.DEBIT,
            null,
            "Test Bank",
            "USD",
            "account-123");
    var dto2 =
        new PreviewTransaction(
            LocalDate.of(2024, 1, 16),
            "Transaction 2",
            BigDecimal.valueOf(200.00),
            TransactionType.CREDIT,
            null,
            "Test Bank",
            "USD",
            "account-123");

    when(transactionRepository.findExistingDuplicateKeys(any(), any())).thenReturn(Set.of());
    when(transactionRepository.saveAll(any()))
        .thenAnswer(
            invocation -> {
              List<Transaction> transactions = invocation.getArgument(0);
              for (int i = 0; i < transactions.size(); i++) {
                transactions.get(i).setId((long) (i + 1));
              }
              return transactions;
            });

    // When: batch import is called
    var result = transactionService.batchImport(List.of(dto1, dto2), USER_ID);

    // Then: both transactions are created
    assertThat(result.createdTransactions()).hasSize(2);
    assertThat(result.duplicatesSkipped()).isEqualTo(0);
    verify(transactionRepository, times(1)).saveAll(any());
  }

  @Test
  void batchImport_setsOwnerIdOnAllCreated() {
    // Given: valid transactions
    var dto =
        new PreviewTransaction(
            LocalDate.of(2024, 1, 15),
            "Transaction 1",
            BigDecimal.valueOf(100.00),
            TransactionType.DEBIT,
            null,
            "Test Bank",
            "USD",
            null);

    when(transactionRepository.findExistingDuplicateKeys(any(), any())).thenReturn(Set.of());
    when(transactionRepository.saveAll(any()))
        .thenAnswer(
            invocation -> {
              List<Transaction> transactions = invocation.getArgument(0);
              transactions.get(0).setId(1L);
              return transactions;
            });

    // When: batch import is called
    var result = transactionService.batchImport(List.of(dto), USER_ID);

    // Then: ownerId is set on created transaction
    assertThat(result.createdTransactions()).hasSize(1);
    assertThat(result.createdTransactions().get(0).getOwnerId()).isEqualTo(USER_ID);
  }

  @Test
  void batchImport_withExistingDuplicates_skipsMatchingTransactions() {
    // Given: one transaction already exists in database
    var dto1 =
        new PreviewTransaction(
            LocalDate.of(2024, 1, 15),
            "Existing Transaction",
            BigDecimal.valueOf(100.00),
            TransactionType.DEBIT,
            null,
            "Test Bank",
            "USD",
            null);
    var dto2 =
        new PreviewTransaction(
            LocalDate.of(2024, 1, 16),
            "New Transaction",
            BigDecimal.valueOf(200.00),
            TransactionType.CREDIT,
            null,
            "Test Bank",
            "USD",
            null);

    // Simulate that dto1's key already exists
    var existingKey = "2024-01-15|100.00|Existing Transaction";
    when(transactionRepository.findExistingDuplicateKeys(any(), any()))
        .thenReturn(Set.of(existingKey));
    when(transactionRepository.saveAll(any()))
        .thenAnswer(
            invocation -> {
              List<Transaction> transactions = invocation.getArgument(0);
              for (int i = 0; i < transactions.size(); i++) {
                transactions.get(i).setId((long) (i + 1));
              }
              return transactions;
            });

    // When: batch import is called
    var result = transactionService.batchImport(List.of(dto1, dto2), USER_ID);

    // Then: only new transaction is created, duplicate is skipped
    assertThat(result.createdTransactions()).hasSize(1);
    assertThat(result.duplicatesSkipped()).isEqualTo(1);
    assertThat(result.createdTransactions().get(0).getDescription()).isEqualTo("New Transaction");
  }

  @Test
  void batchImport_withIntraBatchDuplicates_skipsSecondOccurrence() {
    // Given: two identical transactions in the same batch
    var dto1 =
        new PreviewTransaction(
            LocalDate.of(2024, 1, 15),
            "Same Transaction",
            BigDecimal.valueOf(100.00),
            TransactionType.DEBIT,
            null,
            "Test Bank",
            "USD",
            null);
    var dto2 =
        new PreviewTransaction(
            LocalDate.of(2024, 1, 15),
            "Same Transaction",
            BigDecimal.valueOf(100.00),
            TransactionType.DEBIT,
            null,
            "Test Bank",
            "USD",
            null);

    when(transactionRepository.findExistingDuplicateKeys(any(), any())).thenReturn(Set.of());
    when(transactionRepository.saveAll(any()))
        .thenAnswer(
            invocation -> {
              List<Transaction> transactions = invocation.getArgument(0);
              for (int i = 0; i < transactions.size(); i++) {
                transactions.get(i).setId((long) (i + 1));
              }
              return transactions;
            });

    // When: batch import is called
    var result = transactionService.batchImport(List.of(dto1, dto2), USER_ID);

    // Then: only first transaction is created, second is skipped as intra-batch duplicate
    assertThat(result.createdTransactions()).hasSize(1);
    assertThat(result.duplicatesSkipped()).isEqualTo(1);
  }

  @Test
  void batchImport_emptyList_returnsEmptyResult() {
    // Given: empty list
    when(transactionRepository.findExistingDuplicateKeys(any(), any())).thenReturn(Set.of());
    when(transactionRepository.saveAll(any())).thenReturn(List.of());

    // When: batch import is called with empty list
    var result = transactionService.batchImport(List.of(), USER_ID);

    // Then: returns empty result
    assertThat(result.createdTransactions()).isEmpty();
    assertThat(result.duplicatesSkipped()).isEqualTo(0);
  }

  @Test
  void batchImport_dateBeforeYear2000_throwsBatchValidationException() {
    // Given: transaction with date before year 2000
    var dto =
        new PreviewTransaction(
            LocalDate.of(1999, 12, 31),
            "Old Transaction",
            BigDecimal.valueOf(100.00),
            TransactionType.DEBIT,
            null,
            "Test Bank",
            "USD",
            null);

    // When/Then: batch import throws BatchValidationException
    assertThatThrownBy(() -> transactionService.batchImport(List.of(dto), USER_ID))
        .isInstanceOf(BatchValidationException.class)
        .satisfies(
            ex -> {
              var bve = (BatchValidationException) ex;
              assertThat(bve.getFieldErrors()).hasSize(1);
              assertThat(bve.getFieldErrors().get(0).getIndex()).isEqualTo(0);
              assertThat(bve.getFieldErrors().get(0).getField()).isEqualTo("date");
              assertThat(bve.getFieldErrors().get(0).getMessage()).contains("before year 2000");
            });
  }

  @Test
  void batchImport_dateTooFarInFuture_throwsBatchValidationException() {
    // Given: transaction with date more than 1 day in the future
    var dto =
        new PreviewTransaction(
            LocalDate.now().plusDays(5),
            "Future Transaction",
            BigDecimal.valueOf(100.00),
            TransactionType.DEBIT,
            null,
            "Test Bank",
            "USD",
            null);

    // When/Then: batch import throws BatchValidationException
    assertThatThrownBy(() -> transactionService.batchImport(List.of(dto), USER_ID))
        .isInstanceOf(BatchValidationException.class)
        .satisfies(
            ex -> {
              var bve = (BatchValidationException) ex;
              assertThat(bve.getFieldErrors()).hasSize(1);
              assertThat(bve.getFieldErrors().get(0).getIndex()).isEqualTo(0);
              assertThat(bve.getFieldErrors().get(0).getField()).isEqualTo("date");
              assertThat(bve.getFieldErrors().get(0).getMessage()).contains("in the future");
            });
  }

  @Test
  void batchImport_duplicateDetectionIsPerOwner() {
    // Given: a transaction
    var dto =
        new PreviewTransaction(
            LocalDate.of(2024, 1, 15),
            "Transaction 1",
            BigDecimal.valueOf(100.00),
            TransactionType.DEBIT,
            null,
            "Test Bank",
            "USD",
            null);

    when(transactionRepository.findExistingDuplicateKeys(any(), any())).thenReturn(Set.of());
    when(transactionRepository.saveAll(any()))
        .thenAnswer(
            invocation -> {
              List<Transaction> transactions = invocation.getArgument(0);
              transactions.get(0).setId(1L);
              return transactions;
            });

    // When: batch import is called
    transactionService.batchImport(List.of(dto), USER_ID);

    // Then: duplicate detection is called with the owner's userId
    verify(transactionRepository).findExistingDuplicateKeys(any(), eq(USER_ID));
  }

  @Test
  void batchImport_multipleValidationErrors_aggregatesAllErrors() {
    // Given: batch with multiple validation failures
    var dto1 =
        new PreviewTransaction(
            LocalDate.of(1999, 1, 1),
            "Old Transaction",
            BigDecimal.valueOf(100.00),
            TransactionType.DEBIT,
            null,
            "Test Bank",
            "USD",
            null);
    var dto2 =
        new PreviewTransaction(
            LocalDate.now().plusDays(10),
            "Future Transaction",
            BigDecimal.valueOf(200.00),
            TransactionType.CREDIT,
            null,
            "Test Bank",
            "USD",
            null);

    // When/Then: batch import throws with all errors aggregated
    assertThatThrownBy(() -> transactionService.batchImport(List.of(dto1, dto2), USER_ID))
        .isInstanceOf(BatchValidationException.class)
        .satisfies(
            ex -> {
              var bve = (BatchValidationException) ex;
              assertThat(bve.getFieldErrors()).hasSize(2);
              assertThat(bve.getFieldErrors().get(0).getIndex()).isEqualTo(0);
              assertThat(bve.getFieldErrors().get(1).getIndex()).isEqualTo(1);
            });
  }

  // ==================== Helper Methods ====================

  private Transaction createTransaction(Long id, String description, BigDecimal amount) {
    var transaction = new Transaction();
    transaction.setId(id);
    transaction.setAccountId("test-account");
    transaction.setBankName("Test Bank");
    transaction.setDate(LocalDate.now());
    transaction.setCurrencyIsoCode("USD");
    transaction.setAmount(amount);
    transaction.setType(TransactionType.DEBIT);
    transaction.setDescription(description);
    transaction.setOwnerId(USER_ID);
    return transaction;
  }

  private org.budgetanalyzer.transaction.api.request.TransactionFilter emptyFilter() {
    return org.budgetanalyzer.transaction.api.request.TransactionFilter.empty();
  }
}
