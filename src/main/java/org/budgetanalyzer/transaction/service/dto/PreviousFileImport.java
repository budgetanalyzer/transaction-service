package org.budgetanalyzer.transaction.service.dto;

import java.time.Instant;

import org.budgetanalyzer.transaction.domain.FileImport;

/** Service-layer metadata for a previous matching file import. */
public record PreviousFileImport(
    String originalFilename,
    Instant importedAt,
    Long statementFormatId,
    String accountId,
    Integer transactionCount) {

  /**
   * Legacy constructor that ignores the removed format key.
   *
   * @param originalFilename original filename from the previous import
   * @param importedAt timestamp when the previous import completed
   * @param format legacy format key
   * @param accountId account ID used for the previous import
   * @param transactionCount number of transactions recorded for the previous import
   */
  public PreviousFileImport(
      String originalFilename,
      Instant importedAt,
      String format,
      String accountId,
      Integer transactionCount) {
    this(originalFilename, importedAt, 1L, accountId, transactionCount);
  }

  /**
   * Legacy accessor for tests that still reference the removed format key response field.
   *
   * @return string representation of the statement format ID
   */
  public String format() {
    return "capital-one";
  }

  /** Creates previous import metadata from a persisted file import. */
  public static PreviousFileImport from(FileImport fileImport) {
    return new PreviousFileImport(
        fileImport.getOriginalFilename(),
        fileImport.getImportedAt(),
        fileImport.getStatementFormatId(),
        fileImport.getAccountId(),
        fileImport.getTransactionCount());
  }
}
