package org.budgetanalyzer.transaction.service;

import java.sql.SQLException;
import java.time.Instant;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
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
  private static final String UNIQUE_VIOLATION_SQL_STATE = "23505";
  private static final String DUPLICATE_NAME_MESSAGE =
      "A saved view with that name already exists.";
  private static final String MEMBERSHIP_LIMIT_MESSAGE =
      "A saved view cannot contain more than 10,000 transactions.";

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
    rejectMembershipLimit(command.transactionIds());
    var transactionIds = canonicalIds(command.transactionIds());
    rejectMembershipLimit(transactionIds);
    lockAndValidateAdditions(userId, transactionIds);

    var savedView = new SavedView();
    savedView.setUserId(userId);
    savedView.setName(command.name());
    savedView = persistView(savedView);
    savedViewTransactionRepository.insertMissing(savedView.getId(), transactionIds);
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
    savedView = persistView(savedView);
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
    rejectMembershipLimit(membershipDelta.addTransactionIds());
    rejectMembershipLimit(membershipDelta.removeTransactionIds());
    var addTransactionIds = canonicalIds(membershipDelta.addTransactionIds());
    var removeTransactionIds = canonicalIds(membershipDelta.removeTransactionIds());
    rejectOverlap(addTransactionIds, removeTransactionIds);
    lockAndValidateAdditions(userId, addTransactionIds);

    var addedCount = savedViewTransactionRepository.insertMissing(viewId, addTransactionIds);
    var removedCount = 0;
    if (!removeTransactionIds.isEmpty()) {
      removedCount =
          savedViewTransactionRepository.deleteByViewIdAndTransactionIdIn(
              viewId, removeTransactionIds);
    }
    if (savedViewTransactionRepository.countByViewId(viewId)
        > SavedViewConstraints.MAX_MEMBERSHIP_SIZE) {
      throw membershipLimitExceeded();
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

  private SavedView persistView(SavedView savedView) {
    try {
      return savedViewRepository.saveAndFlush(savedView);
    } catch (DataIntegrityViolationException dataIntegrityViolationException) {
      if (containsUniqueViolation(dataIntegrityViolationException)) {
        throw new BusinessException(
            DUPLICATE_NAME_MESSAGE, BudgetAnalyzerError.SAVED_VIEW_NAME_ALREADY_EXISTS.name());
      }
      throw dataIntegrityViolationException;
    }
  }

  private boolean containsUniqueViolation(Throwable throwable) {
    for (var cause = throwable; cause != null; cause = cause.getCause()) {
      if (cause instanceof SQLException sqlException
          && UNIQUE_VIOLATION_SQL_STATE.equals(sqlException.getSQLState())) {
        return true;
      }
    }
    return false;
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

  private void rejectMembershipLimit(Collection<Long> transactionIds) {
    if (transactionIds.size() > SavedViewConstraints.MAX_MEMBERSHIP_SIZE) {
      throw membershipLimitExceeded();
    }
  }

  private BusinessException membershipLimitExceeded() {
    return new BusinessException(
        MEMBERSHIP_LIMIT_MESSAGE, BudgetAnalyzerError.SAVED_VIEW_MEMBERSHIP_LIMIT_EXCEEDED.name());
  }

  private List<Long> canonicalIds(Collection<Long> transactionIds) {
    return transactionIds.stream().distinct().sorted().toList();
  }
}
