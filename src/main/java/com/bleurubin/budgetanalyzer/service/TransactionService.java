package com.bleurubin.budgetanalyzer.service;

import com.bleurubin.budgetanalyzer.domain.Transaction;
import java.util.List;

public interface TransactionService {
  Transaction createTransaction(Transaction transaction);

  List<Transaction> createTransactions(List<Transaction> transactions);
}
