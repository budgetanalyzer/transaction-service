package com.bleurubin.budgetanalyzer.service.impl;

import com.bleurubin.budgetanalyzer.api.request.TransactionFilter;
import com.bleurubin.budgetanalyzer.domain.Transaction;
import com.bleurubin.budgetanalyzer.repository.TransactionRepository;
import com.bleurubin.budgetanalyzer.repository.spec.TransactionSpecifications;
import com.bleurubin.budgetanalyzer.service.TransactionService;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class TransactionServiceImpl implements TransactionService {

  private final TransactionRepository transactionRepository;

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
        .findById(id)
        .orElseThrow(() -> new RuntimeException("User not found with id: " + id));
  }

  @Override
  public List<Transaction> search(TransactionFilter filter) {
    return transactionRepository.findAll(TransactionSpecifications.withFilter(filter));
  }
}
