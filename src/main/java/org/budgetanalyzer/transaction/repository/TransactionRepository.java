package org.budgetanalyzer.transaction.repository;

import org.springframework.data.jpa.repository.JpaRepository;

import org.budgetanalyzer.core.repository.SoftDeleteOperations;
import org.budgetanalyzer.transaction.domain.Transaction;

public interface TransactionRepository
    extends JpaRepository<Transaction, Long>, SoftDeleteOperations<Transaction> {}
