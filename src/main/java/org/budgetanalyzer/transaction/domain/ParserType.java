package org.budgetanalyzer.transaction.domain;

/** Parser engine type for a hidden statement parser revision. */
public enum ParserType {
  /** Static Java handler selected by an internal handler key. */
  STATIC_HANDLER,

  /** Deterministic CSV parser configured by column names and parsing rules. */
  CSV_COLUMN_CONFIG,

  /** Future deterministic text-PDF table parser. */
  PDF_TEXT_TABLE_CONFIG
}
