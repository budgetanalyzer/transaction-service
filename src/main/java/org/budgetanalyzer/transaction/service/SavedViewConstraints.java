package org.budgetanalyzer.transaction.service;

/** Service-owned constraints for saved views. */
public final class SavedViewConstraints {

  /** Maximum number of transaction memberships allowed for one saved view. */
  public static final int MAX_MEMBERSHIP_SIZE = 10_000;

  private SavedViewConstraints() {}
}
