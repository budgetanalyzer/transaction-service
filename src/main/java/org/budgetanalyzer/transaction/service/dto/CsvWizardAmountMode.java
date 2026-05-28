package org.budgetanalyzer.transaction.service.dto;

/** Supported CSV wizard amount-column mapping modes. */
public enum CsvWizardAmountMode {
  /** One amount column plus a type column that identifies credit or debit rows. */
  SINGLE_AMOUNT_WITH_TYPE,

  /** Separate debit and credit amount columns where only one is populated per row. */
  DEBIT_CREDIT_COLUMNS
}
