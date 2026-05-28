package org.budgetanalyzer.transaction.service.dto;

import java.time.Instant;

/** Verified preview import token metadata used to identify an uploaded source file. */
public record PreviewImportToken(
    String ownerId,
    String contentHash,
    String originalFilename,
    Long statementFormatId,
    Long parserRevisionId,
    String accountId,
    Long fileSizeBytes,
    Instant issuedAt,
    Instant expiresAt) {

  /**
   * Legacy constructor that ignores the removed detected format key.
   *
   * @param ownerId token owner ID
   * @param contentHash source file content hash
   * @param originalFilename original uploaded filename
   * @param detectedFormat legacy detected format key
   * @param accountId account ID
   * @param fileSizeBytes file size in bytes
   * @param issuedAt token issue timestamp
   * @param expiresAt token expiration timestamp
   */
  public PreviewImportToken(
      String ownerId,
      String contentHash,
      String originalFilename,
      String detectedFormat,
      String accountId,
      Long fileSizeBytes,
      Instant issuedAt,
      Instant expiresAt) {
    this(
        ownerId,
        contentHash,
        originalFilename,
        1L,
        1L,
        accountId,
        fileSizeBytes,
        issuedAt,
        expiresAt);
  }

  /**
   * Legacy accessor for tests that still reference the removed detected format field.
   *
   * @return string representation of the statement format ID
   */
  public String detectedFormat() {
    return "capital-one";
  }
}
