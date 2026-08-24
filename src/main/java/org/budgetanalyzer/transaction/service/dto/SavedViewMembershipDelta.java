package org.budgetanalyzer.transaction.service.dto;

import java.util.Collection;

/** Atomic static-membership additions and removals. */
public record SavedViewMembershipDelta(
    Collection<Long> addTransactionIds, Collection<Long> removeTransactionIds) {}
