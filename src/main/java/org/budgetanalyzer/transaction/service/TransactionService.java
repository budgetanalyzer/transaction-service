package org.budgetanalyzer.transaction.service;

import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import org.budgetanalyzer.service.api.FieldError;
import org.budgetanalyzer.service.exception.ResourceNotFoundException;
import org.budgetanalyzer.transaction.api.request.TransactionFilter;
import org.budgetanalyzer.transaction.api.response.PreviewTransaction;
import org.budgetanalyzer.transaction.domain.Transaction;
import org.budgetanalyzer.transaction.repository.TransactionRepository;
import org.budgetanalyzer.transaction.repository.spec.TransactionSpecifications;

/** Service for managing financial transactions. */
@Service
public class TransactionService {

  private static final Logger log = LoggerFactory.getLogger(TransactionService.class);

  private final TransactionRepository transactionRepository;

  /**
   * Constructs a new TransactionService.
   *
   * @param transactionRepository the transaction repository
   */
  public TransactionService(TransactionRepository transactionRepository) {
    this.transactionRepository = transactionRepository;
  }

  /**
   * Creates a new transaction, assigning ownership to the specified user.
   *
   * @param transaction the transaction to create
   * @param userId the ID of the user who will own this transaction
   * @return the created transaction
   */
  @Transactional
  public Transaction createTransaction(Transaction transaction, String userId) {
    transaction.setOwnerId(userId);
    return transactionRepository.save(transaction);
  }

  /**
   * Creates multiple transactions in batch, assigning ownership to the specified user.
   *
   * @param transactions the list of transactions to create
   * @param userId the ID of the user who will own these transactions
   * @return the list of created transactions
   */
  @Transactional
  public List<Transaction> createTransactions(List<Transaction> transactions, String userId) {
    transactions.forEach(t -> t.setOwnerId(userId));
    return transactionRepository.saveAll(transactions);
  }

  /**
   * Retrieves a transaction by its ID, enforcing ownership for non-admin users.
   *
   * @param id the transaction ID
   * @param userId the ID of the requesting user
   * @param isAdmin whether the requesting user has admin role
   * @return the transaction
   * @throws ResourceNotFoundException if the transaction does not exist or the user is not the
   *     owner
   */
  public Transaction getTransaction(Long id, String userId, boolean isAdmin) {
    return getTransactionWithOwnerCheck(id, userId, isAdmin);
  }

  /**
   * Updates mutable fields of an existing transaction, enforcing ownership for non-admin users.
   *
   * <p>Only updates fields that are non-null in the provided transaction object. Immutable fields
   * (date, amount, type, currencyIsoCode, bankName) cannot be updated.
   *
   * @param id the transaction ID
   * @param userId the ID of the requesting user
   * @param isAdmin whether the requesting user has admin role
   * @param description the new description (null to keep existing)
   * @param accountId the new account ID (null to keep existing)
   * @return the updated transaction
   */
  @Transactional
  public Transaction updateTransaction(
      Long id, String userId, boolean isAdmin, String description, String accountId) {
    var existingTransaction = getTransactionWithOwnerCheck(id, userId, isAdmin);

    if (description != null) {
      existingTransaction.setDescription(description);
    }

    if (accountId != null) {
      existingTransaction.setAccountId(accountId);
    }

    return transactionRepository.save(existingTransaction);
  }

  /**
   * Soft-deletes a transaction by marking it as deleted, enforcing ownership for non-admin users.
   *
   * @param id the transaction ID
   * @param userId the ID of the requesting user (also used as deletedBy)
   * @param isAdmin whether the requesting user has admin role
   */
  @Transactional
  public void deleteTransaction(Long id, String userId, boolean isAdmin) {
    var transaction = getTransactionWithOwnerCheck(id, userId, isAdmin);
    transaction.markDeleted(userId);

    transactionRepository.save(transaction);
  }

  /**
   * Bulk soft-deletes multiple transactions by marking them as deleted.
   *
   * <p>This method processes all provided IDs and attempts to soft-delete each transaction. For
   * non-admin users, transactions owned by other users are treated as not found (returning 404
   * rather than 403 to avoid leaking resource existence). Unlike single delete, this method does
   * not throw an exception for non-existent IDs. Instead, it returns a result object containing
   * both the count of successfully deleted transactions and a list of IDs that were not found.
   *
   * <p>All deletions occur within a single transaction. If any error occurs during processing
   * (other than "not found"), all changes will be rolled back.
   *
   * @param ids the list of transaction IDs to delete
   * @param userId the ID of the requesting user (also used as deletedBy)
   * @param isAdmin whether the requesting user has admin role
   * @return a BulkDeleteResult containing the count of deleted items and list of not found IDs
   */
  @Transactional
  public BulkDeleteResult bulkDeleteTransactions(List<Long> ids, String userId, boolean isAdmin) {
    var notFoundIds = new ArrayList<Long>();
    var deletedCount = 0;

    for (Long id : ids) {
      var transactionOpt = transactionRepository.findByIdActive(id);

      if (transactionOpt.isEmpty()) {
        notFoundIds.add(id);
      } else {
        var transaction = transactionOpt.get();
        if (!isAdmin && !transaction.getOwnerId().equals(userId)) {
          notFoundIds.add(id);
        } else {
          transaction.markDeleted(userId);
          transactionRepository.save(transaction);
          deletedCount++;
        }
      }
    }

    return new BulkDeleteResult(deletedCount, notFoundIds);
  }

  /**
   * Result object for bulk delete operations.
   *
   * @param deletedCount the number of transactions successfully deleted
   * @param notFoundIds the list of IDs that were not found or already deleted
   */
  public record BulkDeleteResult(int deletedCount, List<Long> notFoundIds) {}

  /**
   * Retrieves all active transactions owned by the specified user.
   *
   * @param userId the ID of the user whose transactions to retrieve
   * @return the list of transactions owned by the user
   */
  public List<Transaction> getTransactions(String userId) {
    return transactionRepository.findAllActive(TransactionSpecifications.byOwner(userId));
  }

  /**
   * Searches for transactions matching the filter criteria with pagination.
   *
   * <p>This method does not apply owner scoping — it returns all matching transactions regardless
   * of owner. Access is restricted to admin users via {@code @PreAuthorize}.
   *
   * @param filter the search filter criteria
   * @param pageable pagination and sorting parameters
   * @return a page of matching transactions
   */
  @PreAuthorize("hasRole('ADMIN')")
  public Page<Transaction> search(TransactionFilter filter, Pageable pageable) {
    var spec = TransactionSpecifications.withFilter(filter);
    return transactionRepository.findAllActive(spec, pageable);
  }

  /**
   * Counts active transactions matching the filter criteria for a specific user.
   *
   * @param filter the search filter criteria
   * @param userId the ID of the transaction owner to scope the count to
   * @return the count of matching transactions
   */
  public long countActiveForUser(TransactionFilter filter, String userId) {
    var spec = TransactionSpecifications.withFilter(filter);
    spec = spec.and(TransactionSpecifications.byOwner(userId));
    return transactionRepository.countActive(spec);
  }

  /**
   * Counts active transactions matching the filter criteria across all users.
   *
   * @param filter the search filter criteria
   * @return the count of matching transactions
   */
  @PreAuthorize("hasRole('ADMIN')")
  public long countActive(TransactionFilter filter) {
    var spec = TransactionSpecifications.withFilter(filter);
    return transactionRepository.countActive(spec);
  }

  /**
   * Imports a batch of transactions from preview DTOs, assigning ownership to the specified user.
   *
   * <p>This method implements the batch import with all-or-nothing semantics:
   *
   * <ul>
   *   <li>Jakarta Bean Validation handles field presence/format at controller layer (400)
   *   <li>Business validation (date rules) is performed here (422 if fails)
   *   <li>Duplicates (matching date + amount + description) are detected and skipped
   *   <li>Non-duplicate transactions are persisted atomically
   * </ul>
   *
   * <p>Duplicate detection is scoped per-owner, allowing different users to import the same
   * transactions independently.
   *
   * @param transactions the list of transaction DTOs to import
   * @param userId the ID of the user who will own the imported transactions
   * @return result containing created transactions and duplicate count
   * @throws BatchValidationException if any transaction fails business validation
   */
  @Transactional
  public BatchImportResult batchImport(List<PreviewTransaction> transactions, String userId) {
    log.info("Starting batch import of {} transactions", transactions.size());

    // Phase 1: Business validation (beyond Jakarta Bean Validation)
    validateBusinessRules(transactions);

    // Phase 2: Check for duplicates in the database
    var transactionKeys =
        transactions.stream().map(this::buildDuplicateKey).collect(Collectors.toSet());

    var existingKeys = transactionRepository.findExistingDuplicateKeys(transactionKeys, userId);
    log.debug("Found {} existing duplicate keys", existingKeys.size());

    // Phase 3: Filter out duplicates and persist non-duplicates
    var toCreate = new ArrayList<Transaction>();
    var seenKeys = new HashSet<String>(); // Track duplicates within the batch too
    var duplicatesSkipped = 0;

    for (var dto : transactions) {
      var key = buildDuplicateKey(dto);

      if (existingKeys.contains(key) || seenKeys.contains(key)) {
        duplicatesSkipped++;
        continue;
      }

      seenKeys.add(key);
      var entity = mapToEntity(dto);
      entity.setOwnerId(userId);
      toCreate.add(entity);
    }

    var created = transactionRepository.saveAll(toCreate);

    log.info(
        "Batch import completed: {} created, {} duplicates skipped",
        created.size(),
        duplicatesSkipped);

    return new BatchImportResult(created, duplicatesSkipped);
  }

  /**
   * Validates business rules for all transactions in the batch.
   *
   * <p>Business rules validated:
   *
   * <ul>
   *   <li>Transaction date must not be before year 2000 (EUR exchange rate limitations)
   *   <li>Transaction date must not be more than 1 day in the future
   * </ul>
   *
   * @param transactions the transactions to validate
   * @throws BatchValidationException if any transaction fails validation
   */
  private void validateBusinessRules(List<PreviewTransaction> transactions) {
    var errors = new ArrayList<FieldError>();
    var today = LocalDate.now();
    var maxAllowedDate = today.plusDays(1);

    for (int i = 0; i < transactions.size(); i++) {
      var dto = transactions.get(i);
      var date = dto.date();

      if (date != null) {
        if (date.getYear() < 2000) {
          errors.add(
              FieldError.of(
                  i,
                  "date",
                  "Transaction date "
                      + date
                      + " is before year 2000. "
                      + "Transactions before 2000 are not supported.",
                  date));
        } else if (date.isAfter(maxAllowedDate)) {
          errors.add(
              FieldError.of(
                  i,
                  "date",
                  "Transaction date "
                      + date
                      + " is more than 1 day in the future. "
                      + "Future-dated transactions are not allowed.",
                  date));
        }
      }
    }

    if (!errors.isEmpty()) {
      log.warn("Batch validation failed with {} error(s)", errors.size());
      throw new BatchValidationException(errors);
    }
  }

  /**
   * Builds a duplicate key from a transaction DTO.
   *
   * @param dto the transaction DTO
   * @return composite key in format "date|amount|description"
   */
  private String buildDuplicateKey(PreviewTransaction dto) {
    // Use setScale(2) to match PostgreSQL NUMERIC(38,2) formatting
    return dto.date()
        + "|"
        + dto.amount().setScale(2, RoundingMode.HALF_UP).toPlainString()
        + "|"
        + dto.description();
  }

  /**
   * Maps a preview DTO to a transaction entity.
   *
   * @param dto the preview DTO
   * @return the transaction entity
   */
  private Transaction mapToEntity(PreviewTransaction dto) {
    var transaction = new Transaction();
    transaction.setDate(dto.date());
    transaction.setDescription(dto.description());
    transaction.setAmount(dto.amount());
    transaction.setType(dto.type());
    transaction.setBankName(dto.bankName());
    transaction.setCurrencyIsoCode(dto.currencyIsoCode());
    transaction.setAccountId(dto.accountId());
    // Note: category from preview DTO is not stored (Transaction entity doesn't have this field)
    // Note: fileImport is null for batch imports (not file-based)
    return transaction;
  }

  /**
   * Retrieves a transaction and validates ownership. Non-admin users can only access their own
   * transactions. Ownership violations throw ResourceNotFoundException (404) rather than 403 to
   * avoid leaking resource existence.
   *
   * @param id the transaction ID
   * @param userId the ID of the requesting user
   * @param isAdmin whether the requesting user has admin role
   * @return the transaction
   * @throws ResourceNotFoundException if the transaction does not exist or the user is not the
   *     owner
   */
  private Transaction getTransactionWithOwnerCheck(Long id, String userId, boolean isAdmin) {
    var transaction =
        transactionRepository
            .findByIdActive(id)
            .orElseThrow(
                () -> new ResourceNotFoundException("Transaction not found with id: " + id));
    if (!isAdmin && !transaction.getOwnerId().equals(userId)) {
      throw new ResourceNotFoundException("Transaction not found with id: " + id);
    }
    return transaction;
  }

  /**
   * Result of a batch import operation.
   *
   * @param createdTransactions the list of transactions that were created
   * @param duplicatesSkipped the count of transactions that were skipped as duplicates
   */
  public record BatchImportResult(List<Transaction> createdTransactions, int duplicatesSkipped) {}
}
