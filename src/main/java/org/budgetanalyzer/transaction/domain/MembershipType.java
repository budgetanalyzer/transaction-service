package org.budgetanalyzer.transaction.domain;

/** Indicates how a transaction is included in a saved view. */
public enum MembershipType {
  /** Transaction matches the view's filter criteria. */
  MATCHED,

  /** Transaction was explicitly pinned to the view. */
  PINNED
}
