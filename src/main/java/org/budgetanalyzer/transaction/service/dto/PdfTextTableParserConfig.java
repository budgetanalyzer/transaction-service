package org.budgetanalyzer.transaction.service.dto;

import java.util.List;

/** Deterministic text-PDF table parser configuration stored on a parser revision. */
public record PdfTextTableParserConfig(
    PdfTextTableFileType fileType,
    List<String> headerMustContain,
    Integer minimumRows,
    String dateHeader,
    String dateFormat,
    String descriptionHeader,
    String amountHeader,
    String debitHeader,
    String creditHeader,
    String typeHeader,
    PdfTextTableNegativeMeans negativeMeans,
    PdfTextTableYearSource yearSource) {

  /** Current schema version for persisted PDF text-table parser configurations. */
  public static final int SCHEMA_VERSION = 1;
}
