package org.budgetanalyzer.transaction.domain;

/** Visibility scope for a saved statement format. */
public enum StatementFormatScope {
  /** Built-in application-maintained statement format. */
  SYSTEM,

  /** User-owned custom statement format. */
  USER
}
