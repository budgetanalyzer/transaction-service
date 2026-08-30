package org.budgetanalyzer.transaction.api.request;

/** Shared constraints for saved-view membership request arrays. */
final class SavedViewRequestConstraints {

  static final int MAX_TRANSACTION_IDS_PER_ARRAY = 10_000;

  private SavedViewRequestConstraints() {}
}
