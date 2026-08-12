package org.budgetanalyzer.transaction.service.dto;

import java.util.List;

import org.budgetanalyzer.transaction.domain.Transaction;

/** Aggregate service-layer result for an ordered grouped batch import. */
public record BatchImportResult(
    int created, int duplicatesSkipped, int duplicatesImported, List<BatchImportFileResult> files) {

  /** Creates an immutable grouped batch import result. */
  public BatchImportResult {
    files = List.copyOf(files);
  }

  /** Returns every created transaction in source-file order. */
  public List<Transaction> createdTransactions() {
    return files.stream().flatMap(file -> file.createdTransactions().stream()).toList();
  }
}
