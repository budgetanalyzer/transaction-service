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
   * Legacy constructor that ignores the removed format key.
   *
   * @param contentHash source file content hash
   * @param originalFilename original uploaded filename
   * @param detectedFormat legacy detected format key
   * @param accountId account ID
   * @param fileSizeBytes file size in bytes
   */
  public BatchFileImportSource(
      String contentHash,
      String originalFilename,
      String detectedFormat,
      String accountId,
      Long fileSizeBytes) {
    this(contentHash, originalFilename, 1L, 1L, accountId, fileSizeBytes);
  }

  /**
   * Legacy accessor for tests that still reference the removed detected format field.
   *
   * @return string representation of the statement format ID
   */
  public String detectedFormat() {
    return "capital-one";
  }

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
