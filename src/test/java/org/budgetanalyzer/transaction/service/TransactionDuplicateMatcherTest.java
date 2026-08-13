package org.budgetanalyzer.transaction.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import org.budgetanalyzer.transaction.domain.TransactionDuplicateIdentity;
import org.budgetanalyzer.transaction.domain.TransactionType;
import org.budgetanalyzer.transaction.repository.TransactionRepository;
import org.budgetanalyzer.transaction.repository.TransactionRepository.TransactionDuplicateCandidate;
import org.budgetanalyzer.transaction.service.dto.PreviewDuplicateReason;
import org.budgetanalyzer.transaction.service.dto.PreviewTransaction;

@ExtendWith(MockitoExtension.class)
class TransactionDuplicateMatcherTest {

  private static final String USER_ID = "user-123";

  @Mock private TransactionRepository transactionRepository;

  private final TransactionDuplicateMatcher transactionDuplicateMatcher =
      new TransactionDuplicateMatcher();

  @Test
  void shouldLeaveSameFileRepeatsUnmarkedAndMarkLaterFileAsDuplicate() {
    var firstTransaction = previewTransaction("Coffee Shop");
    var repeatedTransaction = previewTransaction("COFFEE-SHOP");
    var laterFileTransaction = previewTransaction("coffee shop");
    var duplicateIdentity = TransactionDuplicateMatcher.duplicateIdentity(firstTransaction);
    when(transactionRepository.findDuplicateCandidates(Set.of(duplicateIdentity), USER_ID))
        .thenReturn(List.of());

    var result =
        transactionDuplicateMatcher.markGroupedDuplicates(
            transactionRepository,
            List.of(List.of(firstTransaction, repeatedTransaction), List.of(laterFileTransaction)),
            USER_ID);

    assertThat(result.getFirst())
        .allSatisfy(
            transaction -> {
              assertThat(transaction.duplicate()).isFalse();
              assertThat(transaction.duplicateReason()).isNull();
            });
    assertThat(result.get(1).getFirst().duplicate()).isTrue();
    assertThat(result.get(1).getFirst().duplicateReason())
        .isEqualTo(PreviewDuplicateReason.IN_BATCH);
    verify(transactionRepository, times(1))
        .findDuplicateCandidates(Set.of(duplicateIdentity), USER_ID);
  }

  @Test
  void shouldPreferPersistedMatchOverEarlierFileMatch() {
    var firstTransaction = previewTransaction("Coffee Shop");
    var laterFileTransaction = previewTransaction("COFFEE SHOP");
    var duplicateIdentity = TransactionDuplicateMatcher.duplicateIdentity(firstTransaction);
    when(transactionRepository.findDuplicateCandidates(Set.of(duplicateIdentity), USER_ID))
        .thenReturn(List.of(duplicateCandidate(duplicateIdentity, "coffee-shop")));

    var result =
        transactionDuplicateMatcher.markGroupedDuplicates(
            transactionRepository,
            List.of(List.of(firstTransaction), List.of(laterFileTransaction)),
            USER_ID);

    assertThat(result.get(1).getFirst().duplicateReason())
        .isEqualTo(PreviewDuplicateReason.EXISTING_TRANSACTION);
  }

  @Test
  void shouldNotQueryRepositoryWhenAllFilesAreEmpty() {
    var result =
        transactionDuplicateMatcher.markGroupedDuplicates(
            transactionRepository, List.of(List.of(), List.of()), USER_ID);

    assertThat(result).containsExactly(List.of(), List.of());
  }

  private static PreviewTransaction previewTransaction(String description) {
    return new PreviewTransaction(
        LocalDate.of(2024, 1, 15),
        description,
        new BigDecimal("4.50"),
        TransactionType.DEBIT,
        null,
        "Test Bank",
        "USD",
        "checking");
  }

  private static TransactionDuplicateCandidate duplicateCandidate(
      TransactionDuplicateIdentity duplicateIdentity, String description) {
    return new TestTransactionDuplicateCandidate(duplicateIdentity, description);
  }

  private record TestTransactionDuplicateCandidate(
      TransactionDuplicateIdentity duplicateIdentity, String description)
      implements TransactionDuplicateCandidate {

    @Override
    public TransactionDuplicateIdentity getDuplicateIdentity() {
      return duplicateIdentity;
    }

    @Override
    public String getDescription() {
      return description;
    }
  }
}
