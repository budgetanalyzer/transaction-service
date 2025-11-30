package org.budgetanalyzer.transaction.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import org.budgetanalyzer.service.exception.ResourceNotFoundException;
import org.budgetanalyzer.transaction.domain.Transaction;
import org.budgetanalyzer.transaction.domain.TransactionType;
import org.budgetanalyzer.transaction.repository.TransactionRepository;

@ExtendWith(MockitoExtension.class)
class TransactionServiceTest {

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
    var result = transactionService.createTransaction(transaction);

    // Then: transaction is saved and returned with ID
    assertThat(result).isNotNull();
    assertThat(result.getId()).isEqualTo(1L);
    assertThat(result.getDescription()).isEqualTo("Grocery Store");
    assertThat(result.getAmount()).isEqualByComparingTo(BigDecimal.valueOf(45.50));

    verify(transactionRepository, times(1)).save(transaction);
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
        transactionService.createTransactions(List.of(transaction1, transaction2, transaction3));

    // Then: all transactions are saved and returned
    assertThat(result).hasSize(3);
    assertThat(result.get(0).getId()).isEqualTo(1L);
    assertThat(result.get(1).getId()).isEqualTo(2L);
    assertThat(result.get(2).getId()).isEqualTo(3L);

    verify(transactionRepository, times(1)).saveAll(any());
  }

  // ==================== getTransaction ====================

  @Test
  void getTransaction_existingId_returnsTransaction() {
    // Given: a transaction exists
    var transaction = createTransaction(1L, "Coffee Shop", BigDecimal.valueOf(4.50));
    when(transactionRepository.findByIdActive(1L)).thenReturn(Optional.of(transaction));

    // When: get is called
    var result = transactionService.getTransaction(1L);

    // Then: transaction is returned
    assertThat(result).isNotNull();
    assertThat(result.getId()).isEqualTo(1L);
    assertThat(result.getDescription()).isEqualTo("Coffee Shop");

    verify(transactionRepository, times(1)).findByIdActive(1L);
  }

  @Test
  void getTransaction_nonExistentId_throwsResourceNotFoundException() {
    // Given: transaction does not exist
    when(transactionRepository.findByIdActive(9999L)).thenReturn(Optional.empty());

    // When/Then: get throws exception
    assertThatThrownBy(() -> transactionService.getTransaction(9999L))
        .isInstanceOf(ResourceNotFoundException.class)
        .hasMessageContaining("Transaction not found with id: 9999");

    verify(transactionRepository, times(1)).findByIdActive(9999L);
  }

  // ==================== updateTransaction ====================

  @Test
  void updateTransaction_updateDescription_updatesAndSaves() {
    // Given: a transaction exists
    var transaction = createTransaction(1L, "Old Description", BigDecimal.valueOf(100.00));
    when(transactionRepository.findByIdActive(1L)).thenReturn(Optional.of(transaction));
    when(transactionRepository.save(any(Transaction.class)))
        .thenAnswer(invocation -> invocation.getArgument(0));

    // When: update description is called
    var result = transactionService.updateTransaction(1L, "New Description", null);

    // Then: description is updated
    assertThat(result.getDescription()).isEqualTo("New Description");
    assertThat(result.getAccountId()).isEqualTo("test-account"); // unchanged

    verify(transactionRepository, times(1)).findByIdActive(1L);
    verify(transactionRepository, times(1)).save(transaction);
  }

  @Test
  void updateTransaction_updateAccountId_updatesAndSaves() {
    // Given: a transaction exists
    var transaction = createTransaction(1L, "Expense", BigDecimal.valueOf(50.00));
    when(transactionRepository.findByIdActive(1L)).thenReturn(Optional.of(transaction));
    when(transactionRepository.save(any(Transaction.class)))
        .thenAnswer(invocation -> invocation.getArgument(0));

    // When: update account ID is called
    var result = transactionService.updateTransaction(1L, null, "new-account-123");

    // Then: account ID is updated
    assertThat(result.getAccountId()).isEqualTo("new-account-123");
    assertThat(result.getDescription()).isEqualTo("Expense"); // unchanged

    verify(transactionRepository, times(1)).findByIdActive(1L);
    verify(transactionRepository, times(1)).save(transaction);
  }

  @Test
  void updateTransaction_updateBothFields_updatesAndSaves() {
    // Given: a transaction exists
    var transaction = createTransaction(1L, "Old", BigDecimal.valueOf(25.00));
    when(transactionRepository.findByIdActive(1L)).thenReturn(Optional.of(transaction));
    when(transactionRepository.save(any(Transaction.class)))
        .thenAnswer(invocation -> invocation.getArgument(0));

    // When: update both fields
    var result = transactionService.updateTransaction(1L, "New Description", "new-account");

    // Then: both fields are updated
    assertThat(result.getDescription()).isEqualTo("New Description");
    assertThat(result.getAccountId()).isEqualTo("new-account");

    verify(transactionRepository, times(1)).findByIdActive(1L);
    verify(transactionRepository, times(1)).save(transaction);
  }

  @Test
  void updateTransaction_nonExistentId_throwsResourceNotFoundException() {
    // Given: transaction does not exist
    when(transactionRepository.findByIdActive(9999L)).thenReturn(Optional.empty());

    // When/Then: update throws exception
    assertThatThrownBy(() -> transactionService.updateTransaction(9999L, "New", null))
        .isInstanceOf(ResourceNotFoundException.class)
        .hasMessageContaining("Transaction not found with id: 9999");

    verify(transactionRepository, times(1)).findByIdActive(9999L);
  }

  // ==================== deleteTransaction ====================

  @Test
  void deleteTransaction_existingTransaction_marksDeletedAndSaves() {
    // Given: a transaction exists
    var transaction = createTransaction(1L, "To Delete", BigDecimal.valueOf(100.00));
    when(transactionRepository.findByIdActive(1L)).thenReturn(Optional.of(transaction));

    // When: delete is called
    transactionService.deleteTransaction(1L, "admin-user");

    // Then: transaction is marked as deleted
    assertThat(transaction.isDeleted()).isTrue();
    assertThat(transaction.getDeletedBy()).isEqualTo("admin-user");
    assertThat(transaction.getDeletedAt()).isNotNull();

    verify(transactionRepository, times(1)).findByIdActive(1L);
    verify(transactionRepository, times(1))
        .save(
            argThat(
                t ->
                    t.isDeleted()
                        && t.getDeletedBy().equals("admin-user")
                        && t.getDeletedAt() != null));
  }

  @Test
  void deleteTransaction_nonExistentId_throwsResourceNotFoundException() {
    // Given: transaction does not exist
    when(transactionRepository.findByIdActive(9999L)).thenReturn(Optional.empty());

    // When/Then: delete throws exception
    assertThatThrownBy(() -> transactionService.deleteTransaction(9999L, "admin-user"))
        .isInstanceOf(ResourceNotFoundException.class)
        .hasMessageContaining("Transaction not found with id: 9999");

    verify(transactionRepository, times(1)).findByIdActive(9999L);
  }

  // ==================== bulkDeleteTransactions ====================

  @Test
  void bulkDeleteTransactions_allFound_deletesAllTransactions() {
    // Given: 3 transactions exist
    var transaction1 = createTransaction(1L, "DESC1", BigDecimal.valueOf(100));
    var transaction2 = createTransaction(2L, "DESC2", BigDecimal.valueOf(200));
    var transaction3 = createTransaction(3L, "DESC3", BigDecimal.valueOf(300));

    when(transactionRepository.findByIdActive(1L)).thenReturn(Optional.of(transaction1));
    when(transactionRepository.findByIdActive(2L)).thenReturn(Optional.of(transaction2));
    when(transactionRepository.findByIdActive(3L)).thenReturn(Optional.of(transaction3));

    var ids = List.of(1L, 2L, 3L);

    // When: bulk delete is called
    var result = transactionService.bulkDeleteTransactions(ids, "test-user");

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

    when(transactionRepository.findByIdActive(1L)).thenReturn(Optional.of(transaction1));
    when(transactionRepository.findByIdActive(2L)).thenReturn(Optional.of(transaction2));
    when(transactionRepository.findByIdActive(9999L)).thenReturn(Optional.empty());
    when(transactionRepository.findByIdActive(8888L)).thenReturn(Optional.empty());

    var ids = List.of(1L, 2L, 9999L, 8888L);

    // When: bulk delete is called
    var result = transactionService.bulkDeleteTransactions(ids, "test-user");

    // Then: only existing transactions are deleted
    assertThat(result.deletedCount()).isEqualTo(2);
    assertThat(result.notFoundIds()).containsExactlyInAnyOrder(9999L, 8888L);

    verify(transactionRepository, times(2)).save(any(Transaction.class));
  }

  @Test
  void bulkDeleteTransactions_alreadyDeleted_treatedAsNotFound() {
    // Given: 2 transactions, but one is already deleted
    var transaction1 = createTransaction(1L, "DESC1", BigDecimal.valueOf(100));

    when(transactionRepository.findByIdActive(1L)).thenReturn(Optional.of(transaction1));
    when(transactionRepository.findByIdActive(2L)).thenReturn(Optional.empty());

    var ids = List.of(1L, 2L);

    // When: bulk delete is called
    var result = transactionService.bulkDeleteTransactions(ids, "test-user");

    // Then: only non-deleted transaction is counted
    assertThat(result.deletedCount()).isEqualTo(1);
    assertThat(result.notFoundIds()).containsExactly(2L);

    verify(transactionRepository, times(1)).save(any(Transaction.class));
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
    return transaction;
  }
}
