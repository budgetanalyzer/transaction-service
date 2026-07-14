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

  List<PreviewTransaction> markDuplicates(
      TransactionRepository transactionRepository,
      List<PreviewTransaction> previewTransactions,
      String userId) {
    if (previewTransactions.isEmpty()) {
      return previewTransactions;
    }

    var existingCandidatesByKey =
        findExistingCandidatesByKey(transactionRepository, previewTransactions, userId);
    var seenTransactionsByCandidateKey =
        new HashMap<TransactionDuplicateIdentity, List<PreviewTransaction>>();
    var markedPreviewTransactions = new ArrayList<PreviewTransaction>(previewTransactions.size());

    for (var previewTransaction : previewTransactions) {
      var transactionCandidateKey = duplicateIdentity(previewTransaction);
      if (matchesExistingTransaction(
          previewTransaction,
          existingCandidatesByKey.getOrDefault(transactionCandidateKey, List.of()))) {
        markedPreviewTransactions.add(
            previewTransaction.withDuplicate(PreviewDuplicateReason.EXISTING_TRANSACTION));
      } else if (matchesSeenTransaction(
          previewTransaction,
          seenTransactionsByCandidateKey.getOrDefault(transactionCandidateKey, List.of()))) {
        markedPreviewTransactions.add(
            previewTransaction.withDuplicate(PreviewDuplicateReason.IN_BATCH));
      } else {
        markedPreviewTransactions.add(previewTransaction);
      }
      seenTransactionsByCandidateKey
          .computeIfAbsent(transactionCandidateKey, key -> new ArrayList<>())
          .add(previewTransaction);
    }

    return markedPreviewTransactions;
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
