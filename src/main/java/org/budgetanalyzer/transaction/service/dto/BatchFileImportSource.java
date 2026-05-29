package org.budgetanalyzer.transaction.service.dto;

/** Source file metadata verified from a preview import token for batch import recording. */
public record BatchFileImportSource(
    String contentHash,
    String originalFilename,
    Long statementFormatId,
    Long parserRevisionId,
    String accountId,
    Long fileSizeBytes) {

  /**
   * Creates a batch file import source from a verified preview import token.
   *
   * @param previewImportToken the verified preview import token
   * @return source file metadata for batch import recording
   */
  public static BatchFileImportSource from(PreviewImportToken previewImportToken) {
    return new BatchFileImportSource(
        previewImportToken.contentHash(),
        previewImportToken.originalFilename(),
        previewImportToken.statementFormatId(),
        previewImportToken.parserRevisionId(),
        previewImportToken.accountId(),
        previewImportToken.fileSizeBytes());
  }
}
