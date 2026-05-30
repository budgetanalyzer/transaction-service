package org.budgetanalyzer.transaction.service.dto;

/** Outcome of trying one hidden parser revision against an uploaded statement file. */
public enum ParserAttemptStatus {
  NOT_APPLICABLE,
  MATCHED,
  FAILED
}
