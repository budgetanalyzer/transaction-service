package org.budgetanalyzer.transaction.service.dto;

/** Reason a preview transaction was marked as a duplicate. */
public enum PreviewDuplicateReason {
  /** The preview transaction matches an active transaction already stored for the owner. */
  EXISTING_TRANSACTION,

  /** The preview transaction duplicates an earlier transaction in the same preview payload. */
  IN_BATCH
}
