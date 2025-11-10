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
   */
  @Transactional
  public void deleteTransaction(Long id) {
    var transaction = getTransaction(id);
    transaction.markDeleted();

    transactionRepository.save(transaction);
  }

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
