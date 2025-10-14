package com.bleurubin.budgetanalyzer.service.impl;

import com.bleurubin.budgetanalyzer.domain.Transaction;
import com.bleurubin.budgetanalyzer.repository.TransactionRepository;
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
}
