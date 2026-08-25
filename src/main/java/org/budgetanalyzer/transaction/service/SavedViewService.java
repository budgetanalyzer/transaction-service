package org.budgetanalyzer.transaction.service;

import java.time.Instant;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import org.budgetanalyzer.service.exception.BusinessException;
import org.budgetanalyzer.service.exception.InvalidRequestException;
import org.budgetanalyzer.service.exception.ResourceNotFoundException;
import org.budgetanalyzer.transaction.domain.SavedView;
import org.budgetanalyzer.transaction.repository.SavedViewRepository;
import org.budgetanalyzer.transaction.repository.SavedViewTransactionRepository;
import org.budgetanalyzer.transaction.repository.TransactionRepository;
import org.budgetanalyzer.transaction.service.dto.SavedViewCommand;
import org.budgetanalyzer.transaction.service.dto.SavedViewMembershipDelta;
import org.budgetanalyzer.transaction.service.dto.SavedViewPatch;
import org.budgetanalyzer.transaction.service.dto.SavedViewSummary;

/** Service for managing user-owned static saved views. */
@Service
public class SavedViewService {

  private static final Logger log = LoggerFactory.getLogger(SavedViewService.class);

  private final SavedViewRepository savedViewRepository;
  private final SavedViewTransactionRepository savedViewTransactionRepository;
  private final TransactionRepository transactionRepository;

  /** Constructs the static saved-view service. */
  public SavedViewService(
      SavedViewRepository savedViewRepository,
      SavedViewTransactionRepository savedViewTransactionRepository,
      TransactionRepository transactionRepository) {
    this.savedViewRepository = savedViewRepository;
    this.savedViewTransactionRepository = savedViewTransactionRepository;
    this.transactionRepository = transactionRepository;
  }

  /** Creates a saved view after atomically validating its complete membership. */
  @Transactional
  public SavedViewSummary createView(String userId, SavedViewCommand command) {
    var transactionIds = canonicalIds(command.transactionIds());
    lockAndValidateAdditions(userId, transactionIds);

    var savedView = new SavedView();
    savedView.setUserId(userId);
    savedView.setName(command.name());
    savedView = savedViewRepository.saveAndFlush(savedView);
    savedViewTransactionRepository.insertAll(savedView.getId(), transactionIds);
    log.info(
        "Created saved view {} with {} transaction memberships",
        savedView.getId(),
        transactionIds.size());

    return new SavedViewSummary(savedView, transactionIds.size());
  }

  /** Returns all saved views for an owner with grouped membership counts. */
  @Transactional(readOnly = true)
  public List<SavedViewSummary> getViewsForUser(String userId) {
    var savedViews = savedViewRepository.findByUserIdOrderByCreatedAtDesc(userId);
    if (savedViews.isEmpty()) {
      return List.of();
    }

    var viewIds = savedViews.stream().map(SavedView::getId).toList();
    var countsByViewId = new HashMap<UUID, Long>();
    for (var count : savedViewTransactionRepository.countByViewIds(viewIds)) {
      countsByViewId.put(count.getViewId(), count.getTransactionCount());
    }

    return savedViews.stream()
        .map(
            savedView ->
                new SavedViewSummary(savedView, countsByViewId.getOrDefault(savedView.getId(), 0L)))
        .toList();
  }

  /** Returns one owner-scoped saved view with its active membership count. */
  @Transactional(readOnly = true)
  public SavedViewSummary getView(UUID viewId, String userId) {
    var savedView = getOwnedView(viewId, userId);
    return new SavedViewSummary(
        savedView, savedViewTransactionRepository.countByViewId(savedView.getId()));
  }

  /** Updates only the user-facing name of an owner-scoped saved view. */
  @Transactional
  public SavedViewSummary updateView(UUID viewId, String userId, SavedViewPatch patch) {
    var savedView = getLockedOwnedView(viewId, userId);
    savedView.setName(patch.name());
    savedView = savedViewRepository.save(savedView);
    log.info("Updated saved view {} name", viewId);

    return new SavedViewSummary(
        savedView, savedViewTransactionRepository.countByViewId(savedView.getId()));
  }

  /** Deletes an owner-scoped saved view and its cascaded memberships. */
  @Transactional
  public void deleteView(UUID viewId, String userId) {
    savedViewRepository.delete(getLockedOwnedView(viewId, userId));
    log.info("Deleted saved view {}", viewId);
  }

  /** Returns deterministic transaction IDs for an owner-scoped saved view. */
  @Transactional(readOnly = true)
  public List<Long> getViewTransactions(UUID viewId, String userId) {
    getOwnedView(viewId, userId);
    return savedViewTransactionRepository.findTransactionIds(viewId);
  }

  /** Applies disjoint membership additions and removals atomically. */
  @Transactional
  public void updateViewTransactions(
      UUID viewId, String userId, SavedViewMembershipDelta membershipDelta) {
    getLockedOwnedView(viewId, userId);
    var addTransactionIds = canonicalIds(membershipDelta.addTransactionIds());
    var removeTransactionIds = canonicalIds(membershipDelta.removeTransactionIds());
    rejectOverlap(addTransactionIds, removeTransactionIds);
    lockAndValidateAdditions(userId, addTransactionIds);

    var addedCount = savedViewTransactionRepository.insertAll(viewId, addTransactionIds);
    var removedCount = 0;
    if (!removeTransactionIds.isEmpty()) {
      removedCount =
          savedViewTransactionRepository.deleteByViewIdAndTransactionIdIn(
              viewId, removeTransactionIds);
    }
    if (addedCount > 0 || removedCount > 0) {
      savedViewRepository.touch(viewId, Instant.now());
    }
    log.info(
        "Applied saved view {} membership delta with {} additions and {} removals",
        viewId,
        addedCount,
        removedCount);
  }

  private SavedView getOwnedView(UUID viewId, String userId) {
    return savedViewRepository
        .findByIdAndUserId(viewId, userId)
        .orElseThrow(
            () -> new ResourceNotFoundException("Saved view not found with id: " + viewId));
  }

  private SavedView getLockedOwnedView(UUID viewId, String userId) {
    return savedViewRepository
        .lockByIdAndUserId(viewId, userId)
        .orElseThrow(
            () -> new ResourceNotFoundException("Saved view not found with id: " + viewId));
  }

  private void lockAndValidateAdditions(String userId, List<Long> transactionIds) {
    if (transactionIds.isEmpty()) {
      return;
    }

    var lockedTransactions =
        transactionRepository.lockActiveByOwnerIdAndIdIn(userId, transactionIds);
    if (lockedTransactions.size() != transactionIds.size()) {
      throw new BusinessException(
          "Saved-view membership contains unavailable transactions",
          BudgetAnalyzerError.SAVED_VIEW_MEMBERSHIP_STALE.name());
    }
  }

  private void rejectOverlap(List<Long> addTransactionIds, List<Long> removeTransactionIds) {
    var overlappingIds = new HashSet<>(addTransactionIds);
    overlappingIds.retainAll(removeTransactionIds);
    if (!overlappingIds.isEmpty()) {
      throw new InvalidRequestException(
          "addTransactionIds and removeTransactionIds must be disjoint");
    }
  }

  private List<Long> canonicalIds(Collection<Long> transactionIds) {
    return transactionIds.stream().distinct().sorted().toList();
  }
}
