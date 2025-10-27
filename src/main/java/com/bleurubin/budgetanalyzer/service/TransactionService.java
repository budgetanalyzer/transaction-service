package com.bleurubin.budgetanalyzer.service;

import java.util.List;

import com.bleurubin.budgetanalyzer.api.request.TransactionFilter;
import com.bleurubin.budgetanalyzer.domain.Transaction;

public interface TransactionService {

  Transaction createTransaction(Transaction transaction);

  List<Transaction> createTransactions(List<Transaction> transactions);

  Transaction getTransaction(Long id);

  Transaction updateTransaction(Long id, Transaction transaction);

  void deleteTransaction(Long id);

  List<Transaction> search(TransactionFilter filter);
}
