package org.budgetanalyzer.transaction.service.dto;

import java.time.Instant;

import org.budgetanalyzer.transaction.domain.FileImport;

/** Service-layer metadata for a previous matching file import. */
public record PreviousFileImport(
    String originalFilename,
    Instant importedAt,
    String format,
    String accountId,
    Integer transactionCount) {

  /** Creates previous import metadata from a persisted file import. */
  public static PreviousFileImport from(FileImport fileImport) {
    return new PreviousFileImport(
        fileImport.getOriginalFilename(),
        fileImport.getImportedAt(),
        fileImport.getFormat(),
        fileImport.getAccountId(),
        fileImport.getTransactionCount());
  }
}
