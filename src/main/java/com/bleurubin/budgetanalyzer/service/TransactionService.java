package com.bleurubin.budgetanalyzer.service;

import java.util.List;

import com.bleurubin.budgetanalyzer.api.request.TransactionFilter;
import com.bleurubin.budgetanalyzer.domain.Transaction;

/** Service for managing financial transactions. */
public interface TransactionService {

  /**
   * Creates a new transaction.
   *
   * @param transaction the transaction to create
   * @return the created transaction
   */
  Transaction createTransaction(Transaction transaction);

  /**
   * Creates multiple transactions in batch.
   *
   * @param transactions the list of transactions to create
   * @return the list of created transactions
   */
  List<Transaction> createTransactions(List<Transaction> transactions);

  /**
   * Retrieves a transaction by its ID.
   *
   * @param id the transaction ID
   * @return the transaction
   */
  Transaction getTransaction(Long id);

  /**
   * Updates an existing transaction.
   *
   * @param id the transaction ID
   * @param transaction the updated transaction data
   * @return the updated transaction
   */
  Transaction updateTransaction(Long id, Transaction transaction);

  /**
   * Soft-deletes a transaction by marking it as deleted.
   *
   * @param id the transaction ID
   */
  void deleteTransaction(Long id);

  /**
   * Searches for transactions matching the filter criteria.
   *
   * @param filter the search filter criteria
   * @return the list of matching transactions
   */
  List<Transaction> search(TransactionFilter filter);
}
