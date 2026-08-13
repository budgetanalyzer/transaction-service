package org.budgetanalyzer.transaction.service.dto;

import java.util.List;

/** Service-layer batch import group for one verified source file. */
public record BatchImportFile(BatchFileImportSource source, List<PreviewTransaction> transactions) {

  /** Creates an immutable source file group. */
  public BatchImportFile {
    transactions = List.copyOf(transactions);
  }
}
