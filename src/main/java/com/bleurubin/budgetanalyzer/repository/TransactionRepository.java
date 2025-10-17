package com.bleurubin.budgetanalyzer.repository;

import com.bleurubin.budgetanalyzer.domain.Transaction;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

public interface TransactionRepository
    extends JpaRepository<Transaction, Long>, JpaSpecificationExecutor<Transaction> {}
