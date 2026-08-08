package org.budgetanalyzer.transaction.service.dto;

/**
 * Resolved active membership and override counts for a saved view.
 *
 * @param membership effective transaction membership grouped by presentation type
 * @param activePinnedCount number of active stored pinned transaction IDs
 * @param activeExcludedCount number of active stored excluded transaction IDs
 */
public record SavedViewResolution(
    ViewMembership membership, int activePinnedCount, int activeExcludedCount) {

  /**
   * Returns the number of transactions effectively visible in the saved view.
   *
   * @return matched transactions plus presented pinned transactions
   */
  public long transactionCount() {
    return (long) membership.matched().size() + membership.pinned().size();
  }
}
