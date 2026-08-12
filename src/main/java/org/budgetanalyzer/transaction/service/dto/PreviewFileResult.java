package org.budgetanalyzer.transaction.service.dto;

import java.util.List;

/** Service-layer result of previewing one source file within a grouped preview. */
public record PreviewFileResult(
    String sourceFile,
    Long statementFormatId,
    String previewImportToken,
    PreviewFileImportStatus fileImport,
    List<PreviewTransaction> transactions) {

  /** Creates an immutable per-file preview result. */
  public PreviewFileResult {
    transactions = List.copyOf(transactions);
  }
}
