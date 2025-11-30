package org.budgetanalyzer.transaction.service;

import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import org.budgetanalyzer.service.exception.ResourceNotFoundException;
import org.budgetanalyzer.transaction.api.request.TransactionFilter;
import org.budgetanalyzer.transaction.domain.Transaction;
import org.budgetanalyzer.transaction.repository.TransactionRepository;
import org.budgetanalyzer.transaction.repository.spec.TransactionSpecifications;

/** Service for managing financial transactions. */
@Service
public class TransactionService {

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
   * Creates a new transaction.
   *
   * @param transaction the transaction to create
   * @return the created transaction
   */
  @Transactional
  public Transaction createTransaction(Transaction transaction) {
    return transactionRepository.save(transaction);
  }

  /**
   * Creates multiple transactions in batch.
   *
   * @param transactions the list of transactions to create
   * @return the list of created transactions
   */
  @Transactional
  public List<Transaction> createTransactions(List<Transaction> transactions) {
    return transactionRepository.saveAll(transactions);
  }

  /**
   * Retrieves a transaction by its ID.
   *
   * @param id the transaction ID
   * @return the transaction
   */
  public Transaction getTransaction(Long id) {
    return transactionRepository
        .findByIdActive(id)
        .orElseThrow(() -> new ResourceNotFoundException("Transaction not found with id: " + id));
  }

  /**
   * Updates mutable fields of an existing transaction.
   *
   * <p>Only updates fields that are non-null in the provided transaction object. Immutable fields
   * (date, amount, type, currencyIsoCode, bankName) cannot be updated.
   *
   * @param id the transaction ID
   * @param description the new description (null to keep existing)
   * @param accountId the new account ID (null to keep existing)
   * @return the updated transaction
   */
  @Transactional
  public Transaction updateTransaction(Long id, String description, String accountId) {
    var existingTransaction = getTransaction(id);

    if (description != null) {
      existingTransaction.setDescription(description);
    }

    if (accountId != null) {
      existingTransaction.setAccountId(accountId);
    }

    return transactionRepository.save(existingTransaction);
  }

  /**
   * Soft-deletes a transaction by marking it as deleted.
   *
   * @param id the transaction ID
   * @param deletedBy the user ID of who is performing the deletion
   */
  @Transactional
  public void deleteTransaction(Long id, String deletedBy) {
    var transaction = getTransaction(id);
    transaction.markDeleted(deletedBy);

    transactionRepository.save(transaction);
  }

  /**
   * Bulk soft-deletes multiple transactions by marking them as deleted.
   *
   * <p>This method processes all provided IDs and attempts to soft-delete each transaction. Unlike
   * single delete, this method does not throw an exception for non-existent IDs. Instead, it
   * returns a result object containing both the count of successfully deleted transactions and a
   * list of IDs that were not found.
   *
   * <p>All deletions occur within a single transaction. If any error occurs during processing
   * (other than "not found"), all changes will be rolled back.
   *
   * @param ids the list of transaction IDs to delete
   * @param deletedBy the user ID of who is performing the deletion
   * @return a BulkDeleteResult containing the count of deleted items and list of not found IDs
   */
  @Transactional
  public BulkDeleteResult bulkDeleteTransactions(List<Long> ids, String deletedBy) {
    var notFoundIds = new java.util.ArrayList<Long>();
    var deletedCount = 0;

    for (Long id : ids) {
      var transactionOpt = transactionRepository.findByIdActive(id);

      if (transactionOpt.isEmpty()) {
        notFoundIds.add(id);
      } else {
        var transaction = transactionOpt.get();
        transaction.markDeleted(deletedBy);
        transactionRepository.save(transaction);
        deletedCount++;
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
   * Searches for transactions matching the filter criteria.
   *
   * @param filter the search filter criteria
   * @return the list of matching transactions
   */
  public List<Transaction> search(TransactionFilter filter) {
    return transactionRepository.findAllActive(TransactionSpecifications.withFilter(filter));
  }
}
