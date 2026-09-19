package org.budgetanalyzer.transaction.service.dto;

import java.math.BigDecimal;
import java.time.LocalDate;

import org.budgetanalyzer.transaction.domain.TransactionType;

/** Values used to create a single owner-scoped transaction. */
public record CreateTransactionCommand(
    LocalDate date,
    String description,
    BigDecimal amount,
    String currencyIsoCode,
    TransactionType type,
    String bankName,
    String accountId) {}
