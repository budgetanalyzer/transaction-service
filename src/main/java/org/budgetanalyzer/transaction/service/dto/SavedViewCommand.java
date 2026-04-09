package org.budgetanalyzer.transaction.service.dto;

import org.budgetanalyzer.transaction.domain.ViewCriteria;

/** Service-layer command to create a saved view. */
public record SavedViewCommand(String name, ViewCriteria criteria, boolean openEnded) {}
