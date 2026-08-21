package org.budgetanalyzer.transaction.service;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import org.budgetanalyzer.service.api.FieldError;
import org.budgetanalyzer.service.exception.BusinessException;
import org.budgetanalyzer.service.exception.ResourceNotFoundException;
import org.budgetanalyzer.transaction.api.request.TransactionFilter;
import org.budgetanalyzer.transaction.domain.FileImport;
import org.budgetanalyzer.transaction.domain.Transaction;
import org.budgetanalyzer.transaction.domain.TransactionDuplicateIdentity;
import org.budgetanalyzer.transaction.repository.TransactionRepository;
import org.budgetanalyzer.transaction.repository.spec.TransactionSpecifications;
import org.budgetanalyzer.transaction.service.dto.BatchFileImportSource;
import org.budgetanalyzer.transaction.service.dto.BatchImportFile;
import org.budgetanalyzer.transaction.service.dto.BatchImportFileResult;
import org.budgetanalyzer.transaction.service.dto.BatchImportResult;
import org.budgetanalyzer.transaction.service.dto.PreviewTransaction;
import org.budgetanalyzer.transaction.service.dto.TransactionCriteria;

/** Service for managing financial transactions. */
@Service
public class TransactionService {

  private static final Logger log = LoggerFactory.getLogger(TransactionService.class);

  private final TransactionRepository transactionRepository;
  private final FileImportTrackingService fileImportTrackingService;
  private final TransactionDuplicateMatcher transactionDuplicateMatcher =
      new TransactionDuplicateMatcher();

  /**
   * Constructs a new TransactionService.
   *
   * @param transactionRepository the transaction repository
   * @param fileImportTrackingService the file import tracking service
   */
  public TransactionService(
      TransactionRepository transactionRepository,
      FileImportTrackingService fileImportTrackingService) {
    this.transactionRepository = transactionRepository;
    this.fileImportTrackingService = fileImportTrackingService;
  }

  /**
   * Retrieves a transaction by its ID, enforcing ownership unless the caller can act on any.
   *
   * @param id the transaction ID
   * @param userId the ID of the requesting user
   * @param canActOnAny whether the caller has the corresponding {@code :any} permission
   * @return the transaction
   * @throws ResourceNotFoundException if the transaction does not exist or the user is not the
   *     owner
   */
  public Transaction getTransaction(Long id, String userId, boolean canActOnAny) {
    return getTransactionWithOwnerCheck(id, userId, canActOnAny);
  }

  /**
   * Updates mutable fields of an existing transaction, enforcing ownership unless the caller can
   * act on any.
   *
   * <p>Only updates fields that are non-null in the provided transaction object. Immutable fields
   * (date, amount, type, currencyIsoCode, bankName) cannot be updated.
   *
   * @param id the transaction ID
   * @param userId the ID of the requesting user
   * @param canActOnAny whether the caller has the corresponding {@code :any} permission
   * @param description the new description (null to keep existing)
   * @param accountId the new account ID (null to keep existing)
   * @return the updated transaction
   */
  @Transactional
  public Transaction updateTransaction(
      Long id, String userId, boolean canActOnAny, String description, String accountId) {
    var existingTransaction = getTransactionWithOwnerCheck(id, userId, canActOnAny);

    if (description != null) {
      existingTransaction.setDescription(description);
    }

    if (accountId != null) {
      existingTransaction.setAccountId(accountId);
    }

    return transactionRepository.save(existingTransaction);
  }

  /**
   * Soft-deletes a transaction by marking it as deleted, enforcing ownership unless the caller can
   * act on any.
   *
   * @param id the transaction ID
   * @param userId the ID of the requesting user (also used as deletedBy)
   * @param canActOnAny whether the caller has the corresponding {@code :any} permission
   */
  @Transactional
  public void deleteTransaction(Long id, String userId, boolean canActOnAny) {
    var transaction = getTransactionWithOwnerCheck(id, userId, canActOnAny);
    transaction.markDeleted(userId);

    transactionRepository.save(transaction);
  }

  /**
   * Bulk soft-deletes multiple transactions by marking them as deleted.
   *
   * <p>This method processes all provided IDs and attempts to soft-delete each transaction. When
   * the caller cannot act on any, transactions owned by other users are treated as not found
   * (returning 404 rather than 403 to avoid leaking resource existence). Unlike single delete, this
   * method does not throw an exception for non-existent IDs. Instead, it returns a result object
   * containing both the count of successfully deleted transactions and a list of IDs that were not
   * found.
   *
   * <p>All deletions occur within a single transaction. If any error occurs during processing
   * (other than "not found"), all changes will be rolled back.
   *
   * @param ids the list of transaction IDs to delete
   * @param userId the ID of the requesting user (also used as deletedBy)
   * @param canActOnAny whether the caller has the corresponding {@code :any} permission
   * @return a BulkDeleteResult containing the count of deleted items and list of not found IDs
   */
  @Transactional
  public BulkDeleteResult bulkDeleteTransactions(
      List<Long> ids, String userId, boolean canActOnAny) {
    var notFoundIds = new ArrayList<Long>();
    var transactionsById = activeBulkDeleteCandidatesById(ids, userId, canActOnAny);
    var transactionsToDelete = new ArrayList<Transaction>();

    for (var id : ids) {
      var transaction = transactionsById.remove(id);
      if (transaction == null) {
        notFoundIds.add(id);
        continue;
      }

      transaction.markDeleted(userId);
      transactionsToDelete.add(transaction);
    }

    if (!transactionsToDelete.isEmpty()) {
      transactionRepository.saveAll(transactionsToDelete);
    }

    return new BulkDeleteResult(transactionsToDelete.size(), notFoundIds);
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
    return transactionRepository.findAllNotDeleted(TransactionSpecifications.byOwner(userId));
  }

  /**
   * Searches for transactions matching the filter criteria with pagination.
   *
   * <p>This method does not apply owner scoping — it returns all matching transactions regardless
   * of owner. Authorization is enforced at the controller layer via {@code transactions:read:any}.
   *
   * @param filter the search filter criteria
   * @param pageable pagination and sorting parameters
   * @return a page of matching transactions
   */
  public Page<Transaction> search(TransactionFilter filter, Pageable pageable) {
    var spec = TransactionSpecifications.withCriteria(TransactionCriteria.fromFilter(filter));
    return transactionRepository.findAllNotDeleted(spec, pageable);
  }

  /**
   * Counts active transactions matching the filter criteria for a specific user.
   *
   * @param filter the search filter criteria
   * @param userId the ID of the transaction owner to scope the count to
   * @return the count of matching transactions
   */
  public long countNotDeletedForUser(TransactionFilter filter, String userId) {
    var criteria = TransactionCriteria.fromFilter(filter).withOwnerId(userId);
    var spec = TransactionSpecifications.withCriteria(criteria);
    return transactionRepository.countNotDeleted(spec);
  }

  /**
   * Counts active transactions matching the filter criteria across all users.
   *
   * <p>Authorization is enforced at the controller layer via {@code transactions:read:any}.
   *
   * @param filter the search filter criteria
   * @return the count of matching transactions
   */
  public long countNotDeleted(TransactionFilter filter) {
    var spec = TransactionSpecifications.withCriteria(TransactionCriteria.fromFilter(filter));
    return transactionRepository.countNotDeleted(spec);
  }

  /**
   * Imports ordered transaction groups with per-file source metadata.
   *
   * <p>This method implements the batch import with all-or-nothing semantics:
   *
   * <ul>
   *   <li>Jakarta Bean Validation handles field presence and format at the controller layer
   *   <li>Business validation for every file and row is completed before database work
   *   <li>Duplicates are detected by strict financial identity fields and normalized description
   *       equality against persisted rows and completed earlier files, never within one file
   *   <li>Accepted transactions are persisted atomically and linked to their own source file
   * </ul>
   *
   * <p>Duplicate detection is scoped per-owner, allowing different users to import the same
   * transactions independently.
   *
   * <p>Each file group that creates transactions records or reuses its own {@code file_import} row.
   * A group that creates no transactions has a successful zero-created result when another group
   * creates at least one transaction, but it does not create provenance.
   *
   * @param batchImportFiles the ordered source file groups to import
   * @param userId the ID of the user who will own the imported transactions
   * @return aggregate and ordered per-file import results
   * @throws BatchValidationException if any transaction fails business validation
   */
  @Transactional
  public BatchImportResult batchImport(List<BatchImportFile> batchImportFiles, String userId) {
    var transactionCount =
        batchImportFiles.stream().mapToInt(file -> file.transactions().size()).sum();
    log.info(
        "Starting grouped batch import of {} files and {} transactions",
        batchImportFiles.size(),
        transactionCount);

    validateBusinessRules(batchImportFiles);

    var allPreviewTransactions =
        batchImportFiles.stream().flatMap(file -> file.transactions().stream()).toList();
    var existingCandidatesByKey =
        transactionDuplicateMatcher.findExistingCandidatesByKey(
            transactionRepository, allPreviewTransactions, userId);
    log.debug("Found duplicate candidates for {} key(s)", existingCandidatesByKey.size());

    var earlierFileTransactionsByCandidateKey =
        new HashMap<TransactionDuplicateIdentity, List<PreviewTransaction>>();
    var evaluatedFiles = new ArrayList<EvaluatedBatchImportFile>(batchImportFiles.size());
    var aggregateCreated = 0;
    var aggregateDuplicatesSkipped = 0;
    var aggregateDuplicatesImported = 0;

    for (var batchImportFile : batchImportFiles) {
      var acceptedTransactions = new ArrayList<Transaction>();
      var acceptedPreviewTransactions = new ArrayList<PreviewTransaction>();
      var fileDuplicatesSkipped = 0;
      var fileDuplicatesImported = 0;

      for (var previewTransaction : batchImportFile.transactions()) {
        var transactionCandidateKey =
            TransactionDuplicateMatcher.duplicateIdentity(previewTransaction);
        var matchesPersisted =
            transactionDuplicateMatcher.matchesExistingTransaction(
                previewTransaction,
                existingCandidatesByKey.getOrDefault(transactionCandidateKey, List.of()));
        var matchesEarlierFile =
            !matchesPersisted
                && transactionDuplicateMatcher.matchesSeenTransaction(
                    previewTransaction,
                    earlierFileTransactionsByCandidateKey.getOrDefault(
                        transactionCandidateKey, List.of()));
        var duplicate = matchesPersisted || matchesEarlierFile;

        if (duplicate && !previewTransaction.allowDuplicate()) {
          fileDuplicatesSkipped++;
          continue;
        }

        if (duplicate) {
          fileDuplicatesImported++;
        }

        var transaction = mapToEntity(previewTransaction);
        transaction.setOwnerId(userId);
        acceptedTransactions.add(transaction);
        acceptedPreviewTransactions.add(previewTransaction);
      }

      addEarlierFileTransactions(
          earlierFileTransactionsByCandidateKey, acceptedPreviewTransactions);
      evaluatedFiles.add(
          new EvaluatedBatchImportFile(
              batchImportFile,
              acceptedTransactions,
              fileDuplicatesSkipped,
              fileDuplicatesImported));
      aggregateCreated += acceptedTransactions.size();
      aggregateDuplicatesSkipped += fileDuplicatesSkipped;
      aggregateDuplicatesImported += fileDuplicatesImported;
    }

    rejectEmptyImport(aggregateCreated, aggregateDuplicatesSkipped);

    var allTransactionsToCreate = new ArrayList<Transaction>(aggregateCreated);
    for (var evaluatedFile : evaluatedFiles) {
      if (evaluatedFile.transactions().isEmpty()) {
        continue;
      }

      var fileImport =
          resolveFileImport(
              evaluatedFile.batchImportFile().source(),
              userId,
              evaluatedFile.transactions().size());
      evaluatedFile.transactions().forEach(transaction -> transaction.setFileImport(fileImport));
      allTransactionsToCreate.addAll(evaluatedFile.transactions());
    }

    var createdTransactions = transactionRepository.saveAll(allTransactionsToCreate);
    var fileResults = new ArrayList<BatchImportFileResult>(evaluatedFiles.size());
    var createdTransactionIndex = 0;
    for (var evaluatedFile : evaluatedFiles) {
      var nextCreatedTransactionIndex =
          createdTransactionIndex + evaluatedFile.transactions().size();
      fileResults.add(
          new BatchImportFileResult(
              evaluatedFile.batchImportFile().source().originalFilename(),
              createdTransactions.subList(createdTransactionIndex, nextCreatedTransactionIndex),
              evaluatedFile.duplicatesSkipped(),
              evaluatedFile.duplicatesImported()));
      createdTransactionIndex = nextCreatedTransactionIndex;
    }

    log.info(
        "Grouped batch import completed: {} created, {} duplicates skipped, {} duplicates imported",
        createdTransactions.size(),
        aggregateDuplicatesSkipped,
        aggregateDuplicatesImported);

    return new BatchImportResult(
        createdTransactions.size(),
        aggregateDuplicatesSkipped,
        aggregateDuplicatesImported,
        fileResults);
  }

  private void addEarlierFileTransactions(
      Map<TransactionDuplicateIdentity, List<PreviewTransaction>>
          earlierFileTransactionsByCandidateKey,
      List<PreviewTransaction> acceptedPreviewTransactions) {
    for (var previewTransaction : acceptedPreviewTransactions) {
      var transactionCandidateKey =
          TransactionDuplicateMatcher.duplicateIdentity(previewTransaction);
      earlierFileTransactionsByCandidateKey
          .computeIfAbsent(transactionCandidateKey, key -> new ArrayList<>())
          .add(previewTransaction);
    }
  }

  private FileImport resolveFileImport(
      BatchFileImportSource fileImportSource, String userId, int createdTransactionCount) {
    var fileCheckResult =
        fileImportTrackingService.checkHash(fileImportSource.contentHash(), userId);
    if (fileCheckResult.existingImport().isPresent()) {
      log.info("Linking batch import to previously imported source file");
      return fileCheckResult.existingImport().get();
    }

    return fileImportTrackingService.recordImport(
        fileImportSource.contentHash(),
        fileImportSource.originalFilename(),
        fileImportSource.statementFormatId(),
        fileImportSource.parserRevisionId(),
        fileImportSource.accountId(),
        fileImportSource.fileSizeBytes(),
        createdTransactionCount,
        userId);
  }

  private void rejectEmptyImport(int created, int duplicatesSkipped) {
    if (created > 0) {
      return;
    }

    var reason =
        duplicatesSkipped > 0
            ? "All submitted rows were skipped as duplicates. Set allowDuplicate=true only for "
                + "rows that should be intentionally imported."
            : "No transactions were available to import.";
    throw new BusinessException(
        reason, BudgetAnalyzerError.BATCH_IMPORT_NO_TRANSACTIONS_CREATED.name());
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
   * @param batchImportFiles the ordered source file groups to validate
   * @throws BatchValidationException if any transaction fails validation
   */
  private void validateBusinessRules(List<BatchImportFile> batchImportFiles) {
    var errors = new ArrayList<FieldError>();
    var today = LocalDate.now();
    var maxAllowedDate = today.plusDays(1);

    for (int fileIndex = 0; fileIndex < batchImportFiles.size(); fileIndex++) {
      var batchImportFile = batchImportFiles.get(fileIndex);
      for (var transactionIndex = 0;
          transactionIndex < batchImportFile.transactions().size();
          transactionIndex++) {
        var previewTransaction = batchImportFile.transactions().get(transactionIndex);
        var date = previewTransaction.date();
        var field = "files[" + fileIndex + "].transactions[" + transactionIndex + "].date";

        if (date.getYear() < 2000) {
          errors.add(
              FieldError.forField(
                  field,
                  "Transaction date "
                      + date
                      + " in source file '"
                      + batchImportFile.source().originalFilename()
                      + "' is before year 2000. Transactions before 2000 are not supported.",
                  date));
        } else if (date.isAfter(maxAllowedDate)) {
          errors.add(
              FieldError.forField(
                  field,
                  "Transaction date "
                      + date
                      + " in source file '"
                      + batchImportFile.source().originalFilename()
                      + "' is more than 1 day in the future. Future-dated transactions are not "
                      + "allowed.",
                  date));
        }
      }
    }

    if (!errors.isEmpty()) {
      log.warn("Batch validation failed with {} error(s)", errors.size());
      throw new BatchValidationException(errors);
    }
  }

  private LinkedHashMap<Long, Transaction> activeBulkDeleteCandidatesById(
      List<Long> ids, String userId, boolean canActOnAny) {
    var requestedUniqueIds = new LinkedHashSet<>(ids);
    var transactions =
        canActOnAny
            ? transactionRepository.findActiveByIdIn(requestedUniqueIds)
            : transactionRepository.findActiveByOwnerIdAndIdIn(userId, requestedUniqueIds);
    var transactionsById = new LinkedHashMap<Long, Transaction>();
    for (var transaction : transactions) {
      transactionsById.put(transaction.getId(), transaction);
    }

    return transactionsById;
  }

  /**
   * Maps a preview transaction to a transaction entity.
   *
   * @param previewTransaction the preview transaction
   * @return the transaction entity
   */
  private Transaction mapToEntity(PreviewTransaction previewTransaction) {
    var transaction = new Transaction();
    transaction.setDate(previewTransaction.date());
    transaction.setDescription(previewTransaction.description());
    transaction.setAmount(previewTransaction.amount());
    transaction.setType(previewTransaction.type());
    transaction.setBankName(previewTransaction.bankName());
    transaction.setCurrencyIsoCode(previewTransaction.currencyIsoCode());
    transaction.setAccountId(previewTransaction.accountId());
    // PreviewTransaction category is not stored because Transaction has no category field.
    return transaction;
  }

  /**
   * Retrieves a transaction and validates ownership. Callers without the corresponding {@code :any}
   * permission can only access their own transactions. Ownership violations throw
   * ResourceNotFoundException (404) rather than 403 to avoid leaking resource existence.
   *
   * @param id the transaction ID
   * @param userId the ID of the requesting user
   * @param canActOnAny whether the caller has the corresponding {@code :any} permission
   * @return the transaction
   * @throws ResourceNotFoundException if the transaction does not exist or the user is not the
   *     owner
   */
  private Transaction getTransactionWithOwnerCheck(Long id, String userId, boolean canActOnAny) {
    var transaction =
        transactionRepository
            .findByIdNotDeleted(id)
            .orElseThrow(
                () -> new ResourceNotFoundException("Transaction not found with id: " + id));
    if (!canActOnAny && !transaction.getOwnerId().equals(userId)) {
      throw new ResourceNotFoundException("Transaction not found with id: " + id);
    }
    return transaction;
  }

  private record EvaluatedBatchImportFile(
      BatchImportFile batchImportFile,
      List<Transaction> transactions,
      int duplicatesSkipped,
      int duplicatesImported) {}
}
