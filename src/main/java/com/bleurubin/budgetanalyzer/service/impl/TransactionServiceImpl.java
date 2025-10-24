package com.bleurubin.budgetanalyzer.service.impl;

import com.bleurubin.budgetanalyzer.api.request.TransactionFilter;
import com.bleurubin.budgetanalyzer.domain.Transaction;
import com.bleurubin.budgetanalyzer.repository.TransactionRepository;
import com.bleurubin.budgetanalyzer.repository.spec.TransactionSpecifications;
import com.bleurubin.budgetanalyzer.service.SoftDeleteOperations;
import com.bleurubin.budgetanalyzer.service.TransactionService;
import java.util.List;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class TransactionServiceImpl
    implements TransactionService, SoftDeleteOperations<Transaction, Long> {

  private final TransactionRepository transactionRepository;

  public TransactionServiceImpl(TransactionRepository transactionRepository) {
    this.transactionRepository = transactionRepository;
  }

  @Override
  public JpaSpecificationExecutor<Transaction> getRepository() {
    return transactionRepository;
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
    return findById(id)
        .orElseThrow(() -> new RuntimeException("Transaction not found with id: " + id));
  }

  @Override
  @Transactional
  public Transaction updateTransaction(Long id, Transaction transaction) {
    return null;
  }

  @Override
  @Transactional
  public void deleteTransaction(Long id) {
    var transaction = getTransaction(id);
    transaction.setDeleted(true);

    transactionRepository.save(transaction);
  }

  @Override
  public List<Transaction> search(TransactionFilter filter) {
    return findAll(TransactionSpecifications.withFilter(filter));
  }
}
