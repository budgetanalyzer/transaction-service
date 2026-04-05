package org.budgetanalyzer.transaction.service;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import org.budgetanalyzer.service.exception.ResourceNotFoundException;
import org.budgetanalyzer.transaction.api.request.CreateSavedViewRequest;
import org.budgetanalyzer.transaction.api.request.TransactionFilter;
import org.budgetanalyzer.transaction.api.request.UpdateSavedViewRequest;
import org.budgetanalyzer.transaction.domain.SavedView;
import org.budgetanalyzer.transaction.domain.Transaction;
import org.budgetanalyzer.transaction.domain.ViewCriteria;
import org.budgetanalyzer.transaction.repository.SavedViewRepository;
import org.budgetanalyzer.transaction.repository.TransactionRepository;
import org.budgetanalyzer.transaction.repository.spec.TransactionSpecifications;
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
   * @param request the create request
   * @return the created saved view
   */
  @Transactional
  public SavedView createView(String userId, CreateSavedViewRequest request) {
    log.info("Creating saved view '{}' for user {}", request.name(), userId);

    var view = new SavedView();
    view.setUserId(userId);
    view.setName(request.name());
    view.setCriteria(request.criteria().toDomain());
    view.setOpenEnded(request.openEnded());

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
   * @param request the update request
   * @return the updated saved view
   */
  @Transactional
  public SavedView updateView(UUID viewId, String userId, UpdateSavedViewRequest request) {
    var view = getView(viewId, userId);

    if (request.name() != null) {
      view.setName(request.name());
    }
    if (request.criteria() != null) {
      view.setCriteria(request.criteria().toDomain());
    }
    if (request.openEnded() != null) {
      view.setOpenEnded(request.openEnded());
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

    // Verify transaction exists
    transactionRepository
        .findByIdNotDeleted(transactionId)
        .orElseThrow(
            () -> new ResourceNotFoundException("Transaction not found with id: " + transactionId));

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

    // Verify transaction exists
    transactionRepository
        .findByIdNotDeleted(transactionId)
        .orElseThrow(
            () -> new ResourceNotFoundException("Transaction not found with id: " + transactionId));

    view.excludeTransaction(transactionId);
    log.info("Excluded transaction {} from view {} for user {}", transactionId, viewId, userId);
    return savedViewRepository.save(view);
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
    var filter = criteriaToFilter(view.getCriteria(), view.isOpenEnded(), view.getUserId());
    return transactionRepository.findAllNotDeleted(TransactionSpecifications.withFilter(filter));
  }

  private List<Transaction> findTransactionsByIds(Collection<Long> ids, String ownerId) {
    return ids.stream()
        .map(transactionRepository::findByIdNotDeleted)
        .filter(java.util.Optional::isPresent)
        .map(java.util.Optional::get)
        .filter(transaction -> ownerId.equals(transaction.getOwnerId()))
        .toList();
  }

  TransactionFilter criteriaToFilter(ViewCriteria criteria, boolean openEnded, String ownerId) {
    // Determine effective end date
    LocalDate effectiveEndDate = criteria.endDate();
    if (openEnded && effectiveEndDate == null) {
      effectiveEndDate = LocalDate.now();
    }

    // Build account ID filter (join multiple IDs for LIKE query, or use first one)
    String accountIdFilter = null;
    if (criteria.accountIds() != null && !criteria.accountIds().isEmpty()) {
      // For simplicity, use the first account ID. For multiple, we'd need OR conditions.
      accountIdFilter = criteria.accountIds().iterator().next();
    }

    // Build bank name filter (use first value for now)
    String bankNameFilter = null;
    if (criteria.bankNames() != null && !criteria.bankNames().isEmpty()) {
      bankNameFilter = criteria.bankNames().iterator().next();
    }

    // Build currency filter (use first value for now)
    String currencyFilter = null;
    if (criteria.currencyIsoCodes() != null && !criteria.currencyIsoCodes().isEmpty()) {
      currencyFilter = criteria.currencyIsoCodes().iterator().next();
    }

    return new TransactionFilter(
        null, // id
        ownerId,
        accountIdFilter, // accountId
        bankNameFilter, // bankName
        criteria.startDate(), // dateFrom
        effectiveEndDate, // dateTo
        currencyFilter, // currencyIsoCode
        criteria.minAmount(), // minAmount
        criteria.maxAmount(), // maxAmount
        null, // type
        criteria.searchText(), // description
        null, // createdAfter
        null, // createdBefore
        null, // updatedAfter
        null // updatedBefore
        );
  }
}
