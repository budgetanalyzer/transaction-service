package org.budgetanalyzer.transaction.service.dto;

import org.budgetanalyzer.transaction.domain.SavedView;

/** Saved-view metadata paired with its static membership count. */
public record SavedViewSummary(SavedView savedView, long transactionCount) {}
