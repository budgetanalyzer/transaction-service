package org.budgetanalyzer.transaction.service.dto;

/** Source used to supply a missing year for PDF text-table transaction dates. */
public enum PdfTextTableYearSource {
  /** Transaction date text includes the year. */
  EXPLICIT_DATE,

  /** Transaction date text omits the year and uses the statement period context. */
  STATEMENT_PERIOD
}
