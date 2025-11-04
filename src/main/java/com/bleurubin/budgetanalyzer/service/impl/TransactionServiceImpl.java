package com.bleurubin.budgetanalyzer.service.impl;

import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.bleurubin.budgetanalyzer.api.request.TransactionFilter;
import com.bleurubin.budgetanalyzer.domain.Transaction;
import com.bleurubin.budgetanalyzer.repository.TransactionRepository;
import com.bleurubin.budgetanalyzer.repository.spec.TransactionSpecifications;
import com.bleurubin.budgetanalyzer.service.TransactionService;
import com.bleurubin.service.exception.ResourceNotFoundException;

/** Implementation of TransactionService for managing financial transactions. */
@Service
public class TransactionServiceImpl implements TransactionService {

  private final TransactionRepository transactionRepository;

  /**
   * Constructs a new TransactionServiceImpl.
   *
   * @param transactionRepository the transaction repository
   */
  public TransactionServiceImpl(TransactionRepository transactionRepository) {
    this.transactionRepository = transactionRepository;
  }

  @Override
  @Transactional
  public Transaction createTransaction(Transaction transaction) {
    return transactionRepository.save(transaction);
  }

  @Override
  @Transactional
  public List<Transaction> createTransactions(List<Transaction> transactions) {
    return transactionRepository.saveAll(transactions);
  }

  @Override
  public Transaction getTransaction(Long id) {
    return transactionRepository
        .findByIdActive(id)
        .orElseThrow(() -> new ResourceNotFoundException("Transaction not found with id: " + id));
  }

  @Override
  @Transactional
  public Transaction updateTransaction(Long id, Transaction transaction) {
    return null;
  }

  // this is a soft delete
  @Override
  @Transactional
  public void deleteTransaction(Long id) {
    var transaction = getTransaction(id);
    transaction.markDeleted();

    transactionRepository.save(transaction);
  }

  @Override
  public List<Transaction> search(TransactionFilter filter) {
    return transactionRepository.findAllActive(TransactionSpecifications.withFilter(filter));
  }
}
