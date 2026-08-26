package org.budgetanalyzer.transaction.service.dto;

import java.util.Collection;

/** Service command for creating a static saved view. */
public record SavedViewCommand(String name, Collection<Long> transactionIds) {}
