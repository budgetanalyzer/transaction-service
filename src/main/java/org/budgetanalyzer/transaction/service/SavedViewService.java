package org.budgetanalyzer.transaction.service;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import org.budgetanalyzer.service.exception.ResourceNotFoundException;
import org.budgetanalyzer.transaction.domain.SavedView;
import org.budgetanalyzer.transaction.domain.Transaction;
import org.budgetanalyzer.transaction.repository.SavedViewRepository;
import org.budgetanalyzer.transaction.repository.TransactionRepository;
import org.budgetanalyzer.transaction.repository.spec.TransactionSpecifications;
import org.budgetanalyzer.transaction.service.dto.SavedViewCommand;
import org.budgetanalyzer.transaction.service.dto.SavedViewPatch;
import org.budgetanalyzer.transaction.service.dto.TransactionCriteria;
import org.budgetanalyzer.transaction.service.dto.ViewMembership;

/** Service for managing saved views (smart collections) of transactions. */
@Service
public class SavedViewService {

  private static final Logger log = LoggerFactory.getLogger(SavedViewService.class);

  private final SavedViewRepository savedViewRepository;
  private final TransactionRepository transactionRepository;

  /**
   * Constructs a new SavedViewService.
   *
   * @param savedViewRepository the saved view repository
   * @param transactionRepository the transaction repository
   */
  public SavedViewService(
      SavedViewRepository savedViewRepository, TransactionRepository transactionRepository) {
    this.savedViewRepository = savedViewRepository;
    this.transactionRepository = transactionRepository;
  }

  /**
   * Creates a new saved view for the given user.
   *
   * @param userId the user ID
   * @param command the create command
   * @return the created saved view
   */
  @Transactional
  public SavedView createView(String userId, SavedViewCommand command) {
    log.info("Creating saved view '{}' for user {}", command.name(), userId);

    var view = new SavedView();
    view.setUserId(userId);
    view.setName(command.name());
    view.setCriteria(command.criteria());
    view.setOpenEnded(command.openEnded());

    return savedViewRepository.save(view);
  }

  /**
   * Gets all saved views for the given user.
   *
   * @param userId the user ID
   * @return the list of saved views
   */
  public List<SavedView> getViewsForUser(String userId) {
    return savedViewRepository.findByUserIdOrderByCreatedAtDesc(userId);
  }

  /**
   * Gets a saved view by ID, verifying ownership.
   *
   * @param viewId the view ID
   * @param userId the user ID
   * @return the saved view
   * @throws ResourceNotFoundException if the view is not found or doesn't belong to the user
   */
  public SavedView getView(UUID viewId, String userId) {
    return savedViewRepository
        .findByIdAndUserId(viewId, userId)
        .orElseThrow(
            () -> new ResourceNotFoundException("Saved view not found with id: " + viewId));
  }

  /**
   * Updates a saved view.
   *
   * @param viewId the view ID
   * @param userId the user ID
   * @param patch the update patch
   * @return the updated saved view
   */
  @Transactional
  public SavedView updateView(UUID viewId, String userId, SavedViewPatch patch) {
    var view = getView(viewId, userId);

    if (patch.name() != null) {
      view.setName(patch.name());
    }
    if (patch.criteria() != null) {
      view.setCriteria(patch.criteria());
    }
    if (patch.openEnded() != null) {
      view.setOpenEnded(patch.openEnded());
    }

    log.info("Updated saved view {} for user {}", viewId, userId);
    return savedViewRepository.save(view);
  }

  /**
   * Deletes a saved view.
   *
   * @param viewId the view ID
   * @param userId the user ID
   */
  @Transactional
  public void deleteView(UUID viewId, String userId) {
    var view = getView(viewId, userId);
    savedViewRepository.delete(view);
    log.info("Deleted saved view {} for user {}", viewId, userId);
  }

  /**
   * Gets the transaction IDs for a saved view, grouped by membership type.
   *
   * <p>Returns only active (non-deleted) transaction IDs organized as:
   *
   * <ul>
   *   <li><b>matched</b>: Transactions matching view criteria (excluding excluded IDs)
   *   <li><b>pinned</b>: Transactions explicitly pinned (excluding those already matched)
   *   <li><b>excluded</b>: Transactions explicitly excluded from matched set
   * </ul>
   *
   * @param viewId the view ID
   * @param userId the user ID
   * @return the transaction IDs grouped by membership type
   */
  public ViewMembership getViewTransactions(UUID viewId, String userId) {
    var view = getView(viewId, userId);
    return resolveViewMembership(view);
  }

  /**
   * Counts the transactions that would be returned by a view.
   *
   * <p>Only counts active (non-deleted) transactions. The count is: (matching - excluded) + pinned,
   * where all IDs are filtered to active transactions only.
   *
   * @param view the saved view
   * @return the count
   */
  public long countViewTransactions(SavedView view) {
    var matchingTransactions = findMatchingTransactions(view);

    // Get IDs of matching transactions
    var matchingIds =
        matchingTransactions.stream().map(Transaction::getId).collect(Collectors.toSet());

    // Filter pinned IDs to active transactions only
    var pinnedActiveTransactions = findTransactionsByIds(view.getPinnedIds(), view.getUserId());
    var pinnedActiveIds =
        pinnedActiveTransactions.stream().map(Transaction::getId).collect(Collectors.toSet());

    // Filter excluded IDs to active transactions only
    var excludedActiveTransactions = findTransactionsByIds(view.getExcludedIds(), view.getUserId());
    var excludedActiveIds =
        excludedActiveTransactions.stream().map(Transaction::getId).collect(Collectors.toSet());

    // Count = (matching - excluded) + pinned
    var finalIds = new java.util.HashSet<>(matchingIds);
    finalIds.removeAll(excludedActiveIds);
    finalIds.addAll(pinnedActiveIds);

    return finalIds.size();
  }

  /**
   * Pins a transaction to a view.
   *
   * @param viewId the view ID
   * @param userId the user ID
   * @param transactionId the transaction ID to pin
   * @return the updated saved view
   */
  @Transactional
  public SavedView pinTransaction(UUID viewId, String userId, Long transactionId) {
    var view = getView(viewId, userId);

    if (!isTransactionActiveAndOwnedByUser(transactionId, userId)) {
      throw new ResourceNotFoundException("Transaction not found with id: " + transactionId);
    }

    view.pinTransaction(transactionId);
    log.info("Pinned transaction {} to view {} for user {}", transactionId, viewId, userId);
    return savedViewRepository.save(view);
  }

  /**
   * Removes a pin from a view.
   *
   * @param viewId the view ID
   * @param userId the user ID
   * @param transactionId the transaction ID to unpin
   * @return the updated saved view
   */
  @Transactional
  public SavedView unpinTransaction(UUID viewId, String userId, Long transactionId) {
    var view = getView(viewId, userId);
    view.unpinTransaction(transactionId);
    log.info("Unpinned transaction {} from view {} for user {}", transactionId, viewId, userId);
    return savedViewRepository.save(view);
  }

  /**
   * Excludes a transaction from a view.
   *
   * @param viewId the view ID
   * @param userId the user ID
   * @param transactionId the transaction ID to exclude
   * @return the updated saved view
   */
  @Transactional
  public SavedView excludeTransaction(UUID viewId, String userId, Long transactionId) {
    var view = getView(viewId, userId);

    if (!isTransactionActiveAndOwnedByUser(transactionId, userId)) {
      throw new ResourceNotFoundException("Transaction not found with id: " + transactionId);
    }

    view.excludeTransaction(transactionId);
    log.info("Excluded transaction {} from view {} for user {}", transactionId, viewId, userId);
    return savedViewRepository.save(view);
  }

  /**
   * Bulk-pins transactions to a view.
   *
   * @param viewId the view ID
   * @param userId the user ID
   * @param ids the transaction IDs to pin
   * @return result containing updated count and IDs not found for this user
   */
  @Transactional
  public BulkViewUpdateResult bulkPinTransactions(UUID viewId, String userId, List<Long> ids) {
    var view = getView(viewId, userId);
    var notFoundIds = new java.util.ArrayList<Long>();
    var validIds = new LinkedHashSet<Long>();
    var updatedCount = 0;

    for (var id : ids) {
      if (isTransactionActiveAndOwnedByUser(id, userId)) {
        validIds.add(id);
        updatedCount++;
      } else {
        notFoundIds.add(id);
      }
    }

    view.pinTransactions(validIds);
    savedViewRepository.save(view);

    return new BulkViewUpdateResult(updatedCount, notFoundIds);
  }

  /**
   * Bulk-excludes transactions from a view.
   *
   * @param viewId the view ID
   * @param userId the user ID
   * @param ids the transaction IDs to exclude
   * @return result containing updated count and IDs not found for this user
   */
  @Transactional
  public BulkViewUpdateResult bulkExcludeTransactions(UUID viewId, String userId, List<Long> ids) {
    var view = getView(viewId, userId);
    var notFoundIds = new java.util.ArrayList<Long>();
    var validIds = new LinkedHashSet<Long>();
    var updatedCount = 0;

    for (var id : ids) {
      if (isTransactionActiveAndOwnedByUser(id, userId)) {
        validIds.add(id);
        updatedCount++;
      } else {
        notFoundIds.add(id);
      }
    }

    view.excludeTransactions(validIds);
    savedViewRepository.save(view);

    return new BulkViewUpdateResult(updatedCount, notFoundIds);
  }

  /**
   * Removes an exclusion from a view.
   *
   * @param viewId the view ID
   * @param userId the user ID
   * @param transactionId the transaction ID to unexclude
   * @return the updated saved view
   */
  @Transactional
  public SavedView unexcludeTransaction(UUID viewId, String userId, Long transactionId) {
    var view = getView(viewId, userId);
    view.unexcludeTransaction(transactionId);
    log.info(
        "Removed exclusion of transaction {} from view {} for user {}",
        transactionId,
        viewId,
        userId);
    return savedViewRepository.save(view);
  }

  private ViewMembership resolveViewMembership(SavedView view) {
    // Get transactions matching criteria (already filters soft-deleted)
    var matchingTransactions = findMatchingTransactions(view);

    // Extract matching IDs
    var matchingIds =
        matchingTransactions.stream().map(Transaction::getId).collect(Collectors.toSet());

    // Filter pinned IDs to active transactions only
    var pinnedActiveTransactions = findTransactionsByIds(view.getPinnedIds(), view.getUserId());
    var pinnedActiveIds =
        pinnedActiveTransactions.stream().map(Transaction::getId).collect(Collectors.toSet());

    // Filter excluded IDs to active transactions only
    var excludedActiveTransactions = findTransactionsByIds(view.getExcludedIds(), view.getUserId());
    var excludedActiveIds =
        excludedActiveTransactions.stream().map(Transaction::getId).collect(Collectors.toSet());

    // Build final lists (sorted)
    // matched = matchingIds - excludedIds
    var matched =
        matchingIds.stream().filter(id -> !excludedActiveIds.contains(id)).sorted().toList();

    // pinned = pinnedActiveIds - matchingIds
    var pinned = pinnedActiveIds.stream().filter(id -> !matchingIds.contains(id)).sorted().toList();

    // excluded = excludedActiveIds
    var excluded = excludedActiveIds.stream().sorted().toList();

    return new ViewMembership(matched, pinned, excluded);
  }

  private List<Transaction> findMatchingTransactions(SavedView view) {
    var criteria =
        TransactionCriteria.fromViewCriteria(
            view.getCriteria(), view.getUserId(), view.isOpenEnded());
    return transactionRepository.findAllNotDeleted(
        TransactionSpecifications.withCriteria(criteria));
  }

  private List<Transaction> findTransactionsByIds(Collection<Long> ids, String ownerId) {
    return ids.stream()
        .map(transactionRepository::findByIdNotDeleted)
        .filter(java.util.Optional::isPresent)
        .map(java.util.Optional::get)
        .filter(transaction -> ownerId.equals(transaction.getOwnerId()))
        .toList();
  }

  private boolean isTransactionActiveAndOwnedByUser(Long transactionId, String userId) {
    return transactionRepository
        .findByIdNotDeleted(transactionId)
        .map(transaction -> userId.equals(transaction.getOwnerId()))
        .orElse(false);
  }

  /**
   * Result object for bulk saved-view pin/exclude operations.
   *
   * @param updatedCount the number of transactions successfully pinned or excluded
   * @param notFoundIds transaction IDs that were missing, deleted, or owned by another user
   */
  public record BulkViewUpdateResult(int updatedCount, List<Long> notFoundIds) {}
}
