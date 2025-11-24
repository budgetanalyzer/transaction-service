package org.budgetanalyzer.transaction.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
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

import org.budgetanalyzer.transaction.domain.Transaction;
import org.budgetanalyzer.transaction.domain.TransactionType;
import org.budgetanalyzer.transaction.repository.TransactionRepository;

@ExtendWith(MockitoExtension.class)
class TransactionServiceBulkDeleteTest {

  @Mock private TransactionRepository transactionRepository;

  @InjectMocks private TransactionService transactionService;

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

    // Verify transactions were saved after being marked as deleted
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

    // Verify only existing transactions were saved
    verify(transactionRepository, times(2)).save(any(Transaction.class));
  }

  @Test
  void bulkDeleteTransactions_alreadyDeleted_treatedAsNotFound() {
    // Given: 2 transactions, but one is already deleted (not returned by findByIdActive)
    var transaction1 = createTransaction(1L, "DESC1", BigDecimal.valueOf(100));

    when(transactionRepository.findByIdActive(1L)).thenReturn(Optional.of(transaction1));
    when(transactionRepository.findByIdActive(2L)).thenReturn(Optional.empty()); // Already deleted

    var ids = List.of(1L, 2L);

    // When: bulk delete is called
    var result = transactionService.bulkDeleteTransactions(ids, "test-user");

    // Then: only non-deleted transaction is counted
    assertThat(result.deletedCount()).isEqualTo(1);
    assertThat(result.notFoundIds()).containsExactly(2L);

    // Verify only one transaction was saved
    verify(transactionRepository, times(1)).save(any(Transaction.class));
  }

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
