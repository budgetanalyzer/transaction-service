package org.budgetanalyzer.transaction.service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.budgetanalyzer.transaction.repository.TransactionDuplicateCandidateCriteria;
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
        new HashMap<TransactionDuplicateCandidateKey, List<PreviewTransaction>>();
    var markedPreviewTransactions = new ArrayList<PreviewTransaction>(previewTransactions.size());

    for (var previewTransaction : previewTransactions) {
      var transactionCandidateKey = candidateKey(previewTransaction);
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

  Map<TransactionDuplicateCandidateKey, List<TransactionDuplicateCandidate>>
      findExistingCandidatesByKey(
          TransactionRepository transactionRepository,
          List<PreviewTransaction> previewTransactions,
          String userId) {
    if (previewTransactions.isEmpty()) {
      return Map.of();
    }

    var transactionCandidateKeys =
        previewTransactions.stream()
            .map(TransactionDuplicateMatcher::candidateKey)
            .collect(Collectors.toSet());
    var transactionCandidateCriteria =
        transactionCandidateKeys.stream()
            .map(TransactionDuplicateMatcher::toCandidateCriteria)
            .collect(Collectors.toSet());
    return transactionRepository
        .findDuplicateCandidates(transactionCandidateCriteria, userId)
        .stream()
        .collect(
            Collectors.groupingBy(
                transactionDuplicateCandidate ->
                    candidateKey(transactionDuplicateCandidate.getCandidateCriteria())));
  }

  boolean matchesExistingTransaction(
      PreviewTransaction previewTransaction,
      List<TransactionDuplicateCandidate> transactionDuplicateCandidates) {
    for (var transactionDuplicateCandidate : transactionDuplicateCandidates) {
      var transactionDescriptionMatchResult =
          transactionDescriptionMatcher.match(
              previewTransaction.description(),
              transactionDuplicateCandidate.getTransactionId(),
              transactionDuplicateCandidate.getDescription());
      if (transactionDescriptionMatchResult.matched()) {
        return true;
      }
    }
    return false;
  }

  boolean matchesSeenTransaction(
      PreviewTransaction previewTransaction, List<PreviewTransaction> seenTransactions) {
    for (var seenTransaction : seenTransactions) {
      var transactionDescriptionMatchResult =
          transactionDescriptionMatcher.match(
              previewTransaction.description(), null, seenTransaction.description());
      if (transactionDescriptionMatchResult.matched()) {
        return true;
      }
    }
    return false;
  }

  static TransactionDuplicateCandidateKey candidateKey(PreviewTransaction previewTransaction) {
    return TransactionDuplicateCandidateKey.from(previewTransaction);
  }

  private static TransactionDuplicateCandidateKey candidateKey(
      TransactionDuplicateCandidateCriteria transactionDuplicateCandidateCriteria) {
    return new TransactionDuplicateCandidateKey(
        transactionDuplicateCandidateCriteria.accountId(),
        transactionDuplicateCandidateCriteria.bankName(),
        transactionDuplicateCandidateCriteria.date(),
        transactionDuplicateCandidateCriteria.amount(),
        transactionDuplicateCandidateCriteria.type(),
        transactionDuplicateCandidateCriteria.currencyIsoCode());
  }

  private static TransactionDuplicateCandidateCriteria toCandidateCriteria(
      TransactionDuplicateCandidateKey transactionDuplicateCandidateKey) {
    return new TransactionDuplicateCandidateCriteria(
        transactionDuplicateCandidateKey.accountId(),
        transactionDuplicateCandidateKey.bankName(),
        transactionDuplicateCandidateKey.date(),
        transactionDuplicateCandidateKey.amount(),
        transactionDuplicateCandidateKey.type(),
        transactionDuplicateCandidateKey.currencyIsoCode());
  }
}
