package org.budgetanalyzer.transaction.service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.budgetanalyzer.transaction.domain.TransactionDuplicateIdentity;
import org.budgetanalyzer.transaction.repository.TransactionRepository;
import org.budgetanalyzer.transaction.repository.TransactionRepository.TransactionDuplicateCandidate;
import org.budgetanalyzer.transaction.service.dto.PreviewDuplicateReason;
import org.budgetanalyzer.transaction.service.dto.PreviewTransaction;

/** Applies owner-scoped duplicate matching for preview and batch import flows. */
final class TransactionDuplicateMatcher {

  private final TransactionDescriptionMatcher transactionDescriptionMatcher =
      new TransactionDescriptionMatcher();

  List<List<PreviewTransaction>> markGroupedDuplicates(
      TransactionRepository transactionRepository,
      List<List<PreviewTransaction>> previewTransactionGroups,
      String userId) {
    var allPreviewTransactions = previewTransactionGroups.stream().flatMap(List::stream).toList();
    if (allPreviewTransactions.isEmpty()) {
      return previewTransactionGroups;
    }

    var existingCandidatesByKey =
        findExistingCandidatesByKey(transactionRepository, allPreviewTransactions, userId);
    var earlierFileTransactionsByCandidateKey =
        new HashMap<TransactionDuplicateIdentity, List<PreviewTransaction>>();
    var markedTransactionGroups =
        new ArrayList<List<PreviewTransaction>>(previewTransactionGroups.size());

    for (var previewTransactionGroup : previewTransactionGroups) {
      var markedPreviewTransactions =
          new ArrayList<PreviewTransaction>(previewTransactionGroup.size());
      for (var previewTransaction : previewTransactionGroup) {
        var transactionCandidateKey = duplicateIdentity(previewTransaction);
        if (matchesExistingTransaction(
            previewTransaction,
            existingCandidatesByKey.getOrDefault(transactionCandidateKey, List.of()))) {
          markedPreviewTransactions.add(
              previewTransaction.withDuplicate(PreviewDuplicateReason.EXISTING_TRANSACTION));
        } else if (matchesSeenTransaction(
            previewTransaction,
            earlierFileTransactionsByCandidateKey.getOrDefault(
                transactionCandidateKey, List.of()))) {
          markedPreviewTransactions.add(
              previewTransaction.withDuplicate(PreviewDuplicateReason.IN_BATCH));
        } else {
          markedPreviewTransactions.add(previewTransaction);
        }
      }

      markedTransactionGroups.add(List.copyOf(markedPreviewTransactions));
      addEarlierFileTransactions(earlierFileTransactionsByCandidateKey, previewTransactionGroup);
    }

    return List.copyOf(markedTransactionGroups);
  }

  private void addEarlierFileTransactions(
      Map<TransactionDuplicateIdentity, List<PreviewTransaction>>
          earlierFileTransactionsByCandidateKey,
      List<PreviewTransaction> previewTransactions) {
    for (var previewTransaction : previewTransactions) {
      var transactionCandidateKey = duplicateIdentity(previewTransaction);
      earlierFileTransactionsByCandidateKey
          .computeIfAbsent(transactionCandidateKey, key -> new ArrayList<>())
          .add(previewTransaction);
    }
  }

  Map<TransactionDuplicateIdentity, List<TransactionDuplicateCandidate>>
      findExistingCandidatesByKey(
          TransactionRepository transactionRepository,
          List<PreviewTransaction> previewTransactions,
          String userId) {
    var transactionDuplicateIdentities =
        previewTransactions.stream()
            .map(TransactionDuplicateMatcher::duplicateIdentity)
            .collect(Collectors.toSet());
    return transactionRepository
        .findDuplicateCandidates(transactionDuplicateIdentities, userId)
        .stream()
        .collect(Collectors.groupingBy(TransactionDuplicateCandidate::getDuplicateIdentity));
  }

  boolean matchesExistingTransaction(
      PreviewTransaction previewTransaction,
      List<TransactionDuplicateCandidate> transactionDuplicateCandidates) {
    for (var transactionDuplicateCandidate : transactionDuplicateCandidates) {
      if (transactionDescriptionMatcher.match(
          previewTransaction.description(), transactionDuplicateCandidate.getDescription())) {
        return true;
      }
    }
    return false;
  }

  boolean matchesSeenTransaction(
      PreviewTransaction previewTransaction, List<PreviewTransaction> seenTransactions) {
    for (var seenTransaction : seenTransactions) {
      if (transactionDescriptionMatcher.match(
          previewTransaction.description(), seenTransaction.description())) {
        return true;
      }
    }
    return false;
  }

  static TransactionDuplicateIdentity duplicateIdentity(PreviewTransaction previewTransaction) {
    return new TransactionDuplicateIdentity(
        previewTransaction.bankName(),
        previewTransaction.date(),
        previewTransaction.amount(),
        previewTransaction.type(),
        previewTransaction.currencyIsoCode());
  }
}
