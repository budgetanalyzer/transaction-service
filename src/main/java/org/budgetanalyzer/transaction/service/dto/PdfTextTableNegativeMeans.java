package org.budgetanalyzer.transaction.service.dto;

/** Direction represented by a negative value in a signed PDF amount column. */
public enum PdfTextTableNegativeMeans {
  /** Negative signed amounts should be imported as credits. */
  CREDIT,

  /** Negative signed amounts should be imported as debits. */
  DEBIT
}
