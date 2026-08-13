package org.budgetanalyzer.transaction.service.dto;

import java.util.List;

import org.budgetanalyzer.transaction.domain.Transaction;

/** Service-layer batch import result for one source file. */
public record BatchImportFileResult(
    String sourceFile,
    List<Transaction> createdTransactions,
    int duplicatesSkipped,
    int duplicatesImported) {

  /** Creates an immutable per-file batch import result. */
  public BatchImportFileResult {
    createdTransactions = List.copyOf(createdTransactions);
  }
}
